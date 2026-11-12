package com.fonta.dynamicnotch.services

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.pow

// ---------------------------------------------------------------------------------------------
// Colour
// ---------------------------------------------------------------------------------------------

data class RGB(val r: Double, val g: Double, val b: Double) {
    val color: Color get() = Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f))

    val luminance: Double
        get() {
            fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }

    /** Blends toward black: 1 = full colour, 0 = black. */
    fun scaled(intensity: Double): RGB {
        val k = intensity.coerceIn(0.0, 1.0)
        return RGB(r * k, g * k, b * k)
    }

    /** Darkens until white text on top stays readable. */
    fun darkened(maxLuminance: Double): RGB {
        var c = this
        var i = 0
        while (c.luminance > maxLuminance && i < 40) {
            c = RGB(c.r * 0.93, c.g * 0.93, c.b * 0.93)
            i++
        }
        return c
    }

    /** A brighter version for the music bars on the black closed notch. */
    val lightenedForBars: RGB
        get() {
            val max = maxOf(r, g, b)
            if (max < 0.001) return RGB(1.0, 1.0, 1.0)
            val k = 0.95 / max
            val c = RGB(r * k, g * k, b * k)
            // Mix a little white in so very saturated colours stay visible.
            return RGB(c.r * 0.8 + 0.2, c.g * 0.8 + 0.2, c.b * 0.8 + 0.2)
        }
}

/** Dominant colour of the cover: k-means on a 40×40 copy, preferring a large, colourful cluster. */
object ColorExtractor {
    fun dominant(source: Bitmap): RGB? {
        val soft = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        val small = Bitmap.createScaledBitmap(soft ?: return null, 40, 40, true)
        val px = IntArray(1600)
        small.getPixels(px, 0, 40, 0, 0, 40, 40)
        val pts = px.map { doubleArrayOf(((it shr 16) and 0xFF) / 255.0, ((it shr 8) and 0xFF) / 255.0, (it and 0xFF) / 255.0) }
        val k = 5
        val centers = Array(k) { pts[(it * 1600 / k + 800 / k) % 1600].copyOf() }
        val assign = IntArray(pts.size)
        repeat(10) {
            for (i in pts.indices) {
                var best = 0
                var bestD = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d = (0..2).sumOf { (pts[i][it] - centers[c][it]).pow(2) }
                    if (d < bestD) { bestD = d; best = c }
                }
                assign[i] = best
            }
            for (c in 0 until k) {
                val sum = DoubleArray(3)
                var n = 0
                for (i in pts.indices) if (assign[i] == c) { for (j in 0..2) sum[j] += pts[i][j]; n++ }
                if (n > 0) for (j in 0..2) centers[c][j] = sum[j] / n
            }
        }
        val counts = IntArray(k)
        assign.forEach { counts[it]++ }
        val order = (0 until k).sortedByDescending { counts[it] }
        for (c in order) {
            val (r, g, b) = centers[c]
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max <= 0) 0.0 else (max - min) / max
            if (counts[c] > 60 && sat > 0.2 && max in 0.15..0.9) return RGB(r, g, b)
        }
        val avg = DoubleArray(3)
        pts.forEach { for (j in 0..2) avg[j] += it[j] }
        return RGB(avg[0] / pts.size, avg[1] / pts.size, avg[2] / pts.size)
    }
}

// ---------------------------------------------------------------------------------------------
// Audio: media volume, mute, current output
// ---------------------------------------------------------------------------------------------

enum class OutputKind { speaker, bluetooth, wired, cast }

object AudioRepo {
    private lateinit var app: Context
    private lateinit var am: AudioManager
    private val main = Handler(Looper.getMainLooper())

    var volume by mutableStateOf(0f)
        private set
    var muted by mutableStateOf(false)
        private set
    var outputName by mutableStateOf("This device")
        private set
    var outputKind by mutableStateOf(OutputKind.speaker)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        am = app.getSystemService(AudioManager::class.java)
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = readVolume()
        }, filter, Context.RECEIVER_EXPORTED)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = readOutput()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = readOutput()
        }, main)
        readVolume()
        readOutput()
    }

    private val max get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun readVolume() {
        volume = am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
        muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
    }

    fun changeVolume(fraction: Float) {
        val index = (fraction.coerceIn(0f, 1f) * max).toInt()
        volume = fraction.coerceIn(0f, 1f)
        runCatching {
            if (index != am.getStreamVolume(AudioManager.STREAM_MUSIC)) am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }
    }

    fun toggleMute() {
        runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0) }
        readVolume()
    }

    private fun readOutput() {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun find(vararg types: Int) = devices.firstOrNull { it.type in types }
        val bt = find(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
        val wired = find(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val cast = find(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC)
        when {
            bt != null -> { outputKind = OutputKind.bluetooth; outputName = bt.productName?.toString()?.ifBlank { null } ?: "Bluetooth" }
            wired != null -> { outputKind = OutputKind.wired; outputName = "Headphones" }
            cast != null -> { outputKind = OutputKind.cast; outputName = cast.productName?.toString()?.ifBlank { null } ?: "Display" }
            else -> { outputKind = OutputKind.speaker; outputName = "This device" }
        }
    }

    /** Android has no public API to switch outputs, so this opens the system's own output picker. */
    /**
     * The system's audio output switcher. On Samsung, One UI's own panel (the one the Now Bar and the
     * volume panel open: what's playing, this device, displays, earbuds with their volume); elsewhere
     * Android's output dialog for the app that's playing, or the system-wide one.
     */
    fun openOutputPicker() {
        val pkg = MediaRepo.playerPackage
        runCatching {
            val intent = when {
                android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true) ->
                    Intent("com.android.systemui.action.OPEN_MEDIA_OUTPUT")
                pkg != null -> Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG").putExtra("package_name", pkg)
                else -> Intent("com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG")
            }
            app.sendBroadcast(intent.setPackage("com.android.systemui"))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen brightness (needs the "Modify system settings" permission)
// ---------------------------------------------------------------------------------------------

object BrightnessRepo {
    private lateinit var app: Context
    private const val GAMMA = 2.2

    var value by mutableStateOf(0.5f)
        private set
    var available by mutableStateOf(false)
        private set

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = read()
    }

    fun init(context: Context) {
        app = context.applicationContext
        app.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        refresh()
    }

    /** Re-checks the permission and re-reads the value (the slider calls this when it appears). */
    fun refresh() {
        available = Settings.System.canWrite(app)
        read()
    }

    private fun read() {
        val raw = runCatching { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        value = (raw / 255.0).coerceIn(0.0, 1.0).pow(1 / GAMMA).toFloat()
    }

    fun set(fraction: Float) {
        value = fraction.coerceIn(0f, 1f)
        val raw = (value.toDouble().pow(GAMMA) * 255).toInt().coerceIn(1, 255)
        runCatching { Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw) }
    }
}

// ---------------------------------------------------------------------------------------------
// File shelf: temporary copies of files shared to the notch. Emptied when the app restarts.
// ---------------------------------------------------------------------------------------------

data class ShelfItem(val id: String, val name: String, val mime: String, val file: File, val thumb: Bitmap?)

object ShelfStore {
    const val AUTHORITY = "com.fonta.dynamicnotch.files"
    private lateinit var app: Context
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dir get() = File(app.cacheDir, "shelf")

    var items by mutableStateOf(listOf<ShelfItem>())
        private set
    var selected by mutableStateOf(setOf<String>())
        private set
    /** Bumped when files arrive, so the notch can open on the Files tab. */
    var arrivals by mutableStateOf(0)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        io.execute { dir.deleteRecursively(); dir.mkdirs() }
    }

    /** True while one of the shelf's own files is being dragged out (so the notch does not take it back). */
    var draggingOut = false

    private class Incoming(val name: String, val mime: String, val stream: java.io.InputStream)

    /** Name, type and an open stream for [uri]. Opening is quick; the copy happens later, off the main thread. */
    private fun open(uri: Uri): Incoming? = runCatching {
        var name = "file"
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        if (name == "file") uri.lastPathSegment?.substringAfterLast('/')?.let { name = it }
        val mime = app.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        Incoming(name, mime, app.contentResolver.openInputStream(uri)!!)
    }.getOrNull()

    /**
     * Files dropped onto the notch. The permission to read them lasts only as long as the drop itself,
     * so every file is opened right here; the open streams stay readable while they are copied.
     * Returns how many files were taken.
     */
    fun addFromDrop(event: android.view.DragEvent): Int {
        val clip = event.clipData ?: return 0
        // Reading dropped files needs the drag's permission. Activities get it from
        // requestDragAndDropPermissions; a service's window has to take the transient form itself.
        val permission = takeDropPermission(event)
        val incoming = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.mapNotNull(::open)
        android.util.Log.d("FontaNotch", "drop: ${clip.itemCount} items, ${incoming.size} readable, permission=${permission != null}")
        if (incoming.isNotEmpty()) store({ incoming }) { permission?.release() } else permission?.release()
        return incoming.size
    }

    private fun takeDropPermission(event: android.view.DragEvent): android.view.DragAndDropPermissions? = runCatching {
        val cls = android.view.DragAndDropPermissions::class.java
        // obtain() and takeTransient() are hidden from apps; Activities reach them through
        // requestDragAndDropPermissions, which a service cannot call.
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/view/DragAndDropPermissions;")
        val p = cls.getMethod("obtain", android.view.DragEvent::class.java).invoke(null, event) as? android.view.DragAndDropPermissions
            ?: return null
        val ok = cls.getMethod("takeTransient").invoke(p) as Boolean
        if (ok) p else null
    }.onFailure { android.util.Log.w("FontaNotch", "drop permission: ${it.javaClass.simpleName} ${it.cause?.javaClass?.simpleName ?: ""}") }.getOrNull()

    fun add(uris: List<Uri>, onDone: () -> Unit = {}) {
        if (uris.isEmpty()) return onDone()
        store({ uris.mapNotNull(::open) }, onDone)
    }

    private fun store(source: () -> List<Incoming>, onDone: () -> Unit) {
        io.execute {
            val added = source().mapNotNull { inc ->
                runCatching {
                    val id = UUID.randomUUID().toString()
                    val folder = File(dir, id).apply { mkdirs() }
                    val file = File(folder, inc.name.replace('/', '_'))
                    inc.stream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ShelfItem(id, inc.name, inc.mime, file, thumbnail(file, inc.mime))
                }.getOrNull()
            }
            main.post {
                if (added.isNotEmpty()) {
                    items = items + added
                    arrivals++
                }
                onDone()
            }
        }
    }

    private fun thumbnail(file: File, mime: String): Bitmap? {
        if (!mime.startsWith("image/")) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    fun toggleSelected(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    /** The selection, or everything when nothing is selected. */
    private fun targets(): List<ShelfItem> = items.filter { it.id in selected }.ifEmpty { items }

    fun remove(ids: Set<String>) {
        val gone = items.filter { it.id in ids }
        items = items.filter { it.id !in ids }
        selected = selected - ids
        io.execute { gone.forEach { it.file.parentFile?.deleteRecursively() } }
    }

    fun removeTargets() = remove(targets().map { it.id }.toSet())

    fun uri(item: ShelfItem): Uri = FileProvider.getUriForFile(app, AUTHORITY, item.file)

    /** Android's answer to AirDrop: the share sheet (Quick Share lives there). */
    fun shareTargets() {
        val list = targets()
        if (list.isEmpty()) return
        val uris = ArrayList(list.map(::uri))
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(list[0].mime).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.clipData = ClipData.newUri(app.contentResolver, "files", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            app.startActivity(Intent.createChooser(send, "Share files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun open(item: ShelfItem) {
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri(item), item.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Clip for dragging an item out of the shelf into another app. */
    fun dragClip(item: ShelfItem): ClipData = ClipData.newUri(app.contentResolver, item.name, uri(item))
}
