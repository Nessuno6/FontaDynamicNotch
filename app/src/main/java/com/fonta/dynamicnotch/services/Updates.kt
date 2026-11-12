package com.fonta.dynamicnotch.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fonta.dynamicnotch.R
import com.fonta.dynamicnotch.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks this app's GitHub releases for a newer version: once a day while the notch runs (if "Check for updates"
 * is on), and whenever asked from Settings. Only the public "latest release" of this app's repository is asked
 * for; nothing about the device is sent. A newer one is offered with a notification that opens its download.
 */
object Updates {
    private const val TAG = "NotchUpdates"
    const val REPO = "Nessuno6/FontaDynamicNotch"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES = "https://github.com/$REPO/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 30
    private const val PREFS = "updates"
    const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Release(val version: String, val page: String, val apk: String?, val notes: String)

    /** The newest release found that is newer than this app, if any (shown in Settings). */
    var available by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** When the last check finished (0: never), and whether it failed. */
    var lastCheck by mutableStateOf(0L)
        private set
    var lastFailed by mutableStateOf(false)
        private set

    fun installed(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Numeric parts of a version or tag ("v0.2.1" → [0, 2, 1]); missing parts count as 0. */
    fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        .split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun newer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Settings' last state, read once at start. */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastCheck = p.getLong("last", 0L)
    }

    /** The daily check, when it's due and wanted. Quiet: a newer release is announced with a notification once. */
    suspend fun checkIfDue(context: Context) {
        if (!SettingsStore.settings.checkUpdates) return
        if (System.currentTimeMillis() - lastCheck < DAY_MS) return
        check(context, notify = true)
    }

    /** Asks GitHub now. [notify]: tell about a newer release with a notification (once per version). */
    suspend fun check(context: Context, notify: Boolean): Release? {
        if (checking) return available
        checking = true
        try {
            val latest = withContext(Dispatchers.IO) { fetch() }
            lastFailed = latest == null
            val now = System.currentTimeMillis()
            lastCheck = now
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.edit().putLong("last", now).apply()
            val newer = latest?.takeIf { newer(it.version, installed(context)) }
            available = newer
            if (newer != null && notify && p.getString("announced", null) != newer.version) {
                announce(context, newer)
                p.edit().putString("announced", newer.version).apply()
            }
            return newer
        } finally {
            checking = false
        }
    }

    private fun fetch(): Release? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DynamicNotch-Android")
        try {
            if (c.responseCode != 200) return@runCatching null
            val o = Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { (it.string("name") ?: "").endsWith(".apk") }?.string("browser_download_url")
            Release(tag.removePrefix("v"), o.string("html_url") ?: RELEASES, apk, o.string("body") ?: "")
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.w(TAG, "Update check failed: ${it.javaClass.simpleName}") }.getOrNull()

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    /** Updates from inside the app (settings/UpdateActivity), or opens the release page when it has no APK. */
    fun open(context: Context, r: Release) {
        runCatching { context.startActivity(updateIntent(context, r)) }
    }

    private fun updateIntent(context: Context, r: Release): Intent =
        if (r.apk != null) com.fonta.dynamicnotch.settings.UpdateActivity.intent(context, r.apk, r.version)
        else Intent(Intent.ACTION_VIEW, Uri.parse(r.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun announce(context: Context, r: Release) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val download = PendingIntent.getActivity(
            context, 3, updateIntent(context, r),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val page = PendingIntent.getActivity(
            context, 4, Intent(Intent.ACTION_VIEW, Uri.parse(r.page)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_notch)
            .setContentTitle("Dynamic Notch ${r.version} is available")
            .setContentText("You have ${installed(context)}. Tap to update.")
            .setContentIntent(download)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "What's new", page).build())
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}
