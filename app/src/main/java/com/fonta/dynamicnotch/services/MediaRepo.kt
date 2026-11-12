package com.fonta.dynamicnotch.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fonta.dynamicnotch.data.SettingsStore
import java.util.concurrent.Executors

/** Notification access is what lets the app see and control media sessions. No notifications are read. */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        LiveRepo.listener = this
        MediaRepo.attach(this)
        LiveRepo.reset(this, runCatching { activeNotifications }.getOrNull())
    }

    override fun onListenerDisconnected() {
        if (LiveRepo.listener === this) LiveRepo.listener = null
        MediaRepo.detach()
        LiveRepo.clear()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null) LiveRepo.posted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null) LiveRepo.removed(sbn)
    }
}

data class TrackInfo(
    val key: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val packageName: String,
)

/**
 * Now playing, from whichever app owns the active media session (Spotify, YouTube Music, …).
 * Event-driven: nothing polls while the notch is closed.
 */
object MediaRepo {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private var controller: MediaController? = null
    private val colorCache = LinkedHashMap<String, RGB?>()
    private var artKey: String? = null
    private val pauseExpiry = Runnable { pauseExpired = true }

    var hasAccess by mutableStateOf(false)
        private set
    var track by mutableStateOf<TrackInfo?>(null)
        private set
    var artwork by mutableStateOf<Bitmap?>(null)
        private set
    var artworkColor by mutableStateOf<RGB?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var canSeek by mutableStateOf(false)
        private set
    var shuffleAction by mutableStateOf<PlaybackState.CustomAction?>(null)
        private set
    var repeatAction by mutableStateOf<PlaybackState.CustomAction?>(null)
        private set
    private var pauseExpired by mutableStateOf(false)

    // Position is interpolated locally from the last reported (position, time, speed).
    private var basePosition = 0L
    private var baseTime = 0L
    private var speed = 0f

    /** Playing, or paused for less than the "paused music stays visible" setting. */
    val isMusicActive: Boolean get() = track != null && (isPlaying || !pauseExpired)

    fun positionMs(): Long {
        val d = track?.durationMs ?: 0
        val p = if (isPlaying) basePosition + ((SystemClock.elapsedRealtime() - baseTime) * speed).toLong() else basePosition
        return if (d > 0) p.coerceIn(0, d) else p.coerceAtLeast(0)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list ?: emptyList()) }

    fun attach(context: Context) {
        val appContext = context.applicationContext
        app = appContext
        detach()
        val m = appContext.getSystemService(MediaSessionManager::class.java)
        val c = ComponentName(appContext, MediaListenerService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, c, main)
            manager = m
            component = c
            hasAccess = true
            onSessions(m.getActiveSessions(c))
        } catch (_: SecurityException) {
            hasAccess = false
        }
    }

    fun detach() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        manager = null
        controller = null
        hasAccess = false
        track = null
        artwork = null
        artworkColor = null
        isPlaying = false
    }

    private fun onSessions(list: List<MediaController>) {
        callbacks.keys.filter { old -> list.none { it.sessionToken == old.sessionToken } }.forEach { old ->
            runCatching { old.unregisterCallback(callbacks.remove(old)!!) }
        }
        for (c in list) {
            if (callbacks.keys.any { it.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = pickAndRefresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = pickAndRefresh()
                override fun onSessionDestroyed() = pickAndRefresh()
            }
            c.registerCallback(cb, main)
            callbacks[c] = cb
        }
        pickAndRefresh()
    }

    private fun pickAndRefresh() {
        val all = callbacks.keys.toList()
        val playing = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val current = controller?.let { cur -> all.firstOrNull { it.sessionToken == cur.sessionToken } }
        controller = when {
            current != null && current.playbackState?.state == PlaybackState.STATE_PLAYING -> current
            playing != null -> playing
            current != null && current.metadata != null -> current
            else -> all.firstOrNull { it.metadata != null }
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || md == null || title.isNullOrBlank()) {
            track = null
            artwork = null
            artworkColor = null
            isPlaying = false
            artKey = null
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val key = "${c.packageName}|$title|$artist|$album"
        track = TrackInfo(key, title, artist, md.getLong(MediaMetadata.METADATA_KEY_DURATION), c.packageName)

        val st = c.playbackState
        val nowPlaying = st?.state == PlaybackState.STATE_PLAYING
        if (nowPlaying != isPlaying || (!nowPlaying && !main.hasCallbacks(pauseExpiry) && !pauseExpired)) {
            main.removeCallbacks(pauseExpiry)
            pauseExpired = false
            if (!nowPlaying) {
                main.postDelayed(pauseExpiry, (SettingsStore.settings.pausedMusicVisibleMinutes * 60_000).toLong().coerceAtLeast(1000))
            }
        }
        isPlaying = nowPlaying
        basePosition = st?.position ?: 0
        baseTime = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        speed = if (nowPlaying) (st?.playbackSpeed ?: 1f).let { if (it == 0f) 1f else it } else 0f
        canSeek = ((st?.actions ?: 0) and PlaybackState.ACTION_SEEK_TO) != 0L && (track?.durationMs ?: 0) > 0
        val custom = st?.customActions ?: emptyList()
        shuffleAction = custom.firstOrNull { it.action.contains("shuffle", true) || it.name.toString().contains("shuffle", true) }
        repeatAction = custom.firstOrNull { it.action.contains("repeat", true) || it.name.toString().contains("repeat", true) }

        loadArtwork(key, md)
    }

    private fun loadArtwork(key: String, md: MediaMetadata) {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        // Players often send the text first and the cover a moment later under the same track.
        val newKey = key + "|" + (bmp?.generationId ?: uri ?: "none")
        if (newKey == artKey) return
        artKey = newKey
        if (bmp != null) {
            artwork = bmp
            extractColor(key, bmp)
        } else if (uri != null && uri.startsWith("content:")) {
            val ctx = app ?: return
            worker.execute {
                val loaded = runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        d.setTargetSampleSize(2)
                    }
                }.getOrNull()
                main.post {
                    if (artKey == newKey) {
                        artwork = loaded
                        if (loaded != null) extractColor(key, loaded) else artworkColor = null
                    }
                }
            }
        } else {
            artwork = null
            artworkColor = null
        }
    }

    private fun extractColor(key: String, bmp: Bitmap) {
        if (colorCache.containsKey(key)) {
            artworkColor = colorCache[key]
            return
        }
        worker.execute {
            val c = runCatching { ColorExtractor.dominant(bmp) }.getOrNull()
            main.post {
                colorCache[key] = c
                if (colorCache.size > 20) colorCache.remove(colorCache.keys.first())
                if (track?.key == key) artworkColor = c
            }
        }
    }

    // ---- Commands ----

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (isPlaying) t.pause() else t.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms)
        basePosition = ms
        baseTime = SystemClock.elapsedRealtime()
    }

    fun custom(action: PlaybackState.CustomAction?) {
        action ?: return
        controller?.transportControls?.sendCustomAction(action, null)
    }

    /** Opens the player that owns the session, or any music app when nothing is playing. */
    fun openPlayer(context: Context) {
        val c = controller
        runCatching {
            val pi = c?.sessionActivity
            if (pi != null) {
                pi.send()
                return
            }
            val pkg = c?.packageName
            val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
                ?: context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    val playerPackage: String? get() = controller?.packageName
}
