package com.fonta.dynamicnotch.services

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/** One line of synced lyrics, shown from [timeMs] until the next line's time. */
data class LyricLine(val timeMs: Long, val text: String)

/** What LRCLIB has for a track: synced lines, or only plain text (no timing), or an instrumental. */
data class Lyrics(val synced: List<LyricLine>, val plain: String?, val instrumental: Boolean) {
    val isEmpty: Boolean get() = synced.isEmpty() && plain.isNullOrBlank() && !instrumental

    /** Index of the line playing at [ms], or -1 before the first one. */
    fun indexAt(ms: Long): Int {
        var lo = 0
        var hi = synced.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (synced[mid].timeMs <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** Index of the line to show at [positionMs], delayed by [delaySeconds] so lyrics wait for audio output. */
    fun indexAtPlayback(positionMs: Long, delaySeconds: Double = DEFAULT_DELAY_SECONDS): Int =
        indexAt(positionMs - (delaySeconds * 1000.0).toLong())

    companion object {
        const val DEFAULT_DELAY_SECONDS = 1.1
    }
}

/**
 * Lyrics from LRCLIB (lrclib.net: free, open, no account or key). Only the track's title, artist, album and length
 * are sent: for songs looked at in the open notch while "Show lyrics" is on, and for every song played a few
 * seconds while "Lyrics in the closed notch" is on too. Answers, misses included, are cached on the device so each
 * song is asked for once.
 */
object LyricsRepo {
    private const val TAG = "LyricsRepo"
    private const val API = "https://lrclib.net/api"
    private const val MISS_RETRY_MS = 24 * 60 * 60 * 1000L
    private val memory = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 30
    }
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir == null) cacheDir = File(context.applicationContext.cacheDir, "lyrics")
    }

    /** Already known for this track (null = not asked yet, or nothing found). */
    fun cached(track: TrackInfo): Lyrics? = synchronized(memory) { memory[track.key] }

    // The playing track's lyrics as Compose state, shared by the open panel and the closed notch's wing.
    private var currentKey by mutableStateOf<String?>(null)
    private var current by mutableStateOf<Lyrics?>(null)

    /** The line being sung, for the closed notch's lyric wing (kept current by NotchService while it shows). */
    var wingIndex by mutableIntStateOf(-1)

    /** This track's lyrics once loaded (by [load]), else whatever the memory cache has. */
    fun currentFor(track: TrackInfo): Lyrics? = if (currentKey == track.key) current else cached(track)

    /** Loads [track]'s lyrics and makes them [currentFor] it, if it is still the one playing. */
    suspend fun load(track: TrackInfo) {
        val l = get(track)
        if (MediaRepo.track?.key == track.key) {
            current = l
            currentKey = track.key
        }
    }

    /** The track's lyrics, from memory, then the disk cache, then LRCLIB. Null when there are none. */
    suspend fun get(track: TrackInfo): Lyrics? = withContext(Dispatchers.IO) {
        synchronized(memory) { if (memory.containsKey(track.key)) return@withContext memory[track.key] }
        val file = cacheDir?.let { File(it, hash(track.key) + ".json") }
        var known = false
        var result: Lyrics? = null
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()?.let { o ->
                val miss = o["miss"]?.jsonPrimitive?.booleanOrNull == true
                // A miss is asked again after a day: LRCLIB keeps growing.
                if (!miss || System.currentTimeMillis() - f.lastModified() < MISS_RETRY_MS) {
                    known = true
                    result = if (miss) null else parse(o)
                }
            }
        }
        if (!known) {
            val (found, obj) = fetch(track) ?: return@withContext null // network error: not cached, asked again next time
            runCatching {
                file?.parentFile?.mkdirs()
                file?.writeText(if (found != null) obj.toString() else """{"miss":true}""")
            }
            result = found
        }
        synchronized(memory) { memory[track.key] = result }
        result
    }

    /** Pair(lyrics or null when LRCLIB has none, raw JSON to cache), or null on a network error. */
    private fun fetch(track: TrackInfo): Pair<Lyrics?, JsonObject>? {
        val title = track.title
        val artist = track.artist
        val secs = (track.durationMs / 1000).takeIf { it > 0 }
        // Exact match first: LRCLIB pairs title, artist, album and length (±2 s).
        if (artist.isNotBlank() && secs != null) {
            val q = buildString {
                append("track_name=").append(enc(title))
                append("&artist_name=").append(enc(artist))
                if (track.album.isNotBlank()) append("&album_name=").append(enc(track.album))
                append("&duration=").append(secs)
            }
            when (val r = request("$API/get?$q")) {
                null -> return null
                is JsonObject -> parse(r).takeUnless { it.isEmpty }?.let { return it to r }
                else -> {}
            }
        }
        // Then a search, with titles cleaned of "(Official Video)", "- Remastered 2011" and the like and only the
        // first of several artists, picking the result closest in length (and synced over plain).
        val cleanTitle = clean(title)
        val firstArtist = artist.split(",", "&", " feat.", " ft.", " x ", " · ").first().trim()
        val q = "track_name=" + enc(cleanTitle) + (if (firstArtist.isNotBlank()) "&artist_name=" + enc(firstArtist) else "")
        val list = when (val r = request("$API/search?$q")) {
            null -> return null
            is JsonArray -> r.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        val best = list
            .filter { secs == null || (it["duration"]?.jsonPrimitive?.doubleOrNull?.let { d -> abs(d - secs) <= 4 } ?: false) }
            .sortedWith(compareBy({ it.str("syncedLyrics").isNullOrBlank() }, { abs((it["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) - (secs ?: 0)) }))
            .firstOrNull { !parse(it).isEmpty }
            ?: return null to JsonObject(emptyMap())
        return parse(best) to best
    }

    /** The parsed JSON (object or array), JsonObject(empty) on 404, or null on a network or server error. */
    private fun request(address: String): Any? = runCatching {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "DynamicNotch-Android")
        try {
            when (c.responseCode) {
                200 -> Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() })
                404 -> JsonObject(emptyMap())
                else -> null
            }
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.w(TAG, "Lyrics request failed: ${it.javaClass.simpleName}") }.getOrNull()

    private fun parse(o: JsonObject) = Lyrics(
        synced = parseLrc(o.str("syncedLyrics") ?: ""),
        plain = o.str("plainLyrics")?.trim()?.takeIf { it.isNotEmpty() },
        instrumental = o["instrumental"]?.jsonPrimitive?.booleanOrNull == true,
    )

    private val stamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** "[01:02.34]text" lines; a line may carry several stamps. Tags like [ar:…] are skipped. */
    fun parseLrc(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val stamps = ArrayList<Long>()
            var rest = raw.trim()
            while (true) {
                val m = stamp.find(rest) ?: break
                val (min, sec, frac) = m.destructured
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                stamps += min.toLong() * 60_000 + sec.toLong() * 1000 + ms
                rest = rest.substring(m.range.last + 1)
            }
            val text = rest.trim()
            for (t in stamps) out += LyricLine(t, text)
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun clean(title: String) = title
        .replace(Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|visualizer|remaster|live|explicit|clean|version|edit)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|live|mono|stereo|mix).*$""", RegexOption.IGNORE_CASE), "")
        .trim().ifEmpty { title }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
