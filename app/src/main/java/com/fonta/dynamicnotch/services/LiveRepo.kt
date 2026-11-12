package com.fonta.dynamicnotch.services

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap
import com.fonta.dynamicnotch.R
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.TimeFormat
import kotlin.math.abs

data class LiveAction(val title: String, val intent: PendingIntent)

/** One live notification (Samsung Now Bar item, Android Live Update, or an ongoing call/timer/download). */
data class LiveItem(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    /** A few characters for the closed notch ("5 min", "Gate B4"). */
    val chip: String?,
    val icon: Bitmap?,
    val color: Int?,
    val progress: Float?,
    val indeterminate: Boolean,
    /** Wall-clock moment the chronometer counts from (or down to), else null. */
    val chronoAt: Long?,
    val countDown: Boolean,
    val contentIntent: PendingIntent?,
    val actions: List<LiveAction>,
    val rank: Int,
    val postTime: Long,
    /** One UI's icon for what this is (a timer, a call, a recording…), when known; else the app's own [icon]. */
    val oneUi: Int? = null,
) {
    /** The running time, if this item has a clock. */
    fun timeText(now: Long): String? {
        val at = chronoAt ?: return null
        val s = if (countDown) (at - now) / 1000.0 else (now - at) / 1000.0
        return if (countDown) TimeFormat.countdown(s) else TimeFormat.coarse(s)
    }

    /** What the closed notch shows next to the icon. */
    fun wingText(now: Long): String? =
        timeText(now) ?: chip?.takeIf { it.length <= 8 } ?: progress?.let { "${(it * 100).toInt()}%" }
}

/**
 * Live notifications, read through the same notification access the music controls use.
 * Samsung's Now Bar is fed by ordinary notifications carrying `android.ongoingActivityNoti.*` extras;
 * Android's own Live Updates are "promoted ongoing" notifications. Both are picked up here, together
 * with plain ongoing notifications that have a clock or a progress bar (calls, downloads, navigation).
 */
object LiveRepo {
    private const val TAG = "FontaNotch"
    private const val OA = "android.ongoingActivityNoti."
    private var app: Context? = null
    private val byKey = LinkedHashMap<String, LiveItem>()
    /** Items the user removed from the notch that their app keeps up (an ongoing one): left out until it goes. */
    private val dismissed = HashSet<String>()
    /** The listener, to remove notifications with. */
    var listener: android.service.notification.NotificationListenerService? = null

    var items by mutableStateOf(listOf<LiveItem>())
        private set

    /** The item that gets the closed notch. */
    val top: LiveItem? get() = items.firstOrNull()

    fun reset(context: Context, active: Array<StatusBarNotification>?) {
        app = context.applicationContext
        byKey.clear()
        active?.forEach { sbn -> parse(sbn)?.let { byKey[sbn.key] = it } }
        publish()
    }

    fun clear() {
        byKey.clear()
        publish()
    }

    fun posted(context: Context, sbn: StatusBarNotification) {
        app = context.applicationContext
        if (sbn.key in dismissed) return
        val item = parse(sbn)
        if (item != null) byKey[sbn.key] = item else if (byKey.remove(sbn.key) == null) return
        publish()
    }

    fun removed(sbn: StatusBarNotification) {
        dismissed -= sbn.key
        if (byKey.remove(sbn.key) != null) publish()
    }

    /**
     * Removes a live notification: from the phone where Android lets it go (one the user may swipe
     * away), and from the notch in any case. One its app keeps up (a running timer, a call) stays in
     * the notification shade, but the notch leaves it out until it ends.
     */
    fun dismiss(item: LiveItem) {
        dismissed += item.key
        runCatching { listener?.cancelNotification(item.key) }
        if (byKey.remove(item.key) != null) publish()
    }

    /** Drops an app's items right away (the user hid it from the notch). */
    fun hide(pkg: String) {
        if (byKey.values.removeAll { it.pkg == pkg }) publish()
    }

    private fun publish() {
        items = byKey.values.sortedWith(compareBy<LiveItem> { it.rank }.thenByDescending { it.postTime })
    }

    private fun text(extras: Bundle, vararg keys: String): String? {
        for (k in keys) {
            @Suppress("DEPRECATION")
            val v = extras.get(k)
            if (v is CharSequence && v.isNotBlank()) return v.toString()
        }
        return null
    }

    private fun parse(sbn: StatusBarNotification): LiveItem? {
        val ctx = app ?: return null
        val s = SettingsStore.settings
        if (!s.showLive || sbn.packageName == ctx.packageName || sbn.packageName in s.hiddenLivePackages) return null
        val n = sbn.notification ?: return null
        val x = n.extras ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val template = x.getString(Notification.EXTRA_TEMPLATE) ?: ""
        // Music is already the notch's home tab; SystemUI's Now Bar media placeholder carries nothing.
        if (sbn.packageName == "com.android.systemui" && x.getString(Notification.EXTRA_TITLE) == "MediaOngoingActivity") return null
        if (template.contains("MediaStyle") || x.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null

        val samsung = x.containsKey(OA + "style")
        val promoted = n.flags and Notification.FLAG_PROMOTED_ONGOING != 0 || template.endsWith("ProgressStyle")
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val stdMax = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val stdIndeterminate = x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val stdChrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val category = n.category
        val plain = ongoing && s.liveIncludeOngoing && (
            stdMax > 0 || stdIndeterminate || stdChrono ||
                category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_NAVIGATION ||
                category == Notification.CATEGORY_STOPWATCH || category == Notification.CATEGORY_PROGRESS
            )
        if (!samsung && !promoted && !plain) return null

        val title = text(x, OA + "nowbarPrimaryInfo", OA + "primaryInfo", Notification.EXTRA_TITLE)
        val body = text(x, OA + "nowbarSecondaryInfo", OA + "secondaryInfo", Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
        if (title == null && body == null) return null
        var chip = text(x, OA + "chipExpandedText", "android.shortCriticalText")

        // Progress: Samsung's fields first, then the standard ones.
        val oaMax = x.getInt(OA + "progressMax", 0)
        val progress = when {
            oaMax > 0 -> x.getInt(OA + "progress", 0) / oaMax.toFloat()
            stdMax > 0 -> x.getInt(Notification.EXTRA_PROGRESS, 0) / stdMax.toFloat()
            else -> null
        }?.coerceIn(0f, 1f)
        val indeterminate = x.getBoolean(OA + "progressIndeterminate", false) || stdIndeterminate

        // The clock: a standard chronometer, or Samsung's small remote view with a Chronometer inside.
        var chronoAt: Long? = null
        var countDown = false
        if (stdChrono && n.`when` > 0) {
            chronoAt = n.`when`
            countDown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        }
        @Suppress("DEPRECATION")
        (x.getParcelable(OA + "chronometerRemoteView") as? RemoteViews)?.let { rv ->
            readClock(ctx, rv)?.let { c ->
                if (c.running) { chronoAt = c.at; countDown = c.countDown } else if (c.text != null) chip = c.text
            }
        }

        @Suppress("DEPRECATION")
        val iconSource = (x.getParcelable(OA + "chipIcon") as? Icon)
            ?: (x.getParcelable(OA + "nowbarIcon") as? Icon)
            ?: n.smallIcon
        val icon = runCatching { iconSource?.loadDrawable(ctx)?.toBitmap(72, 72) }.getOrNull()
        val color = (x.getInt(OA + "chipBgColor", 0).takeIf { it != 0 } ?: n.color.takeIf { it != 0 })

        @Suppress("DEPRECATION")
        val appName = x.getString("android.substName")
            ?: runCatching { (x.getParcelable("android.appInfo") as? ApplicationInfo)?.loadLabel(ctx.packageManager)?.toString() }.getOrNull()
            ?: sbn.packageName

        val actions = n.actions.orEmpty()
            .filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(3).map { LiveAction(it.title.toString(), it.actionIntent) }

        // One UI's own icon where the kind of activity is known.
        val chronoTag = x.getString(OA + "chronometerRemoteViewTag") ?: ""
        val oneUi = when {
            chronoTag.contains("stopwatch", true) || category == Notification.CATEGORY_STOPWATCH -> R.drawable.oneui_stopwatch
            chronoTag.contains("timer", true) -> R.drawable.oneui_timer
            category == Notification.CATEGORY_ALARM -> R.drawable.oneui_alarm
            category == Notification.CATEGORY_CALL -> R.drawable.oneui_during_call
            category == Notification.CATEGORY_NAVIGATION -> R.drawable.oneui_current_location
            sbn.packageName.contains("screenrecorder", true) || sbn.packageName.contains("smartcapture", true) -> R.drawable.oneui_ic_screen_record
            category == Notification.CATEGORY_PROGRESS || (progress != null && template.endsWith("ProgressStyle").not() && stdMax > 0) -> R.drawable.oneui_download
            else -> null
        }
        val rank = when {
            category == Notification.CATEGORY_CALL -> 0
            chronoAt != null -> 1
            samsung || promoted -> 2
            else -> 3
        }
        return LiveItem(
            sbn.key, sbn.packageName, appName, title ?: appName, body ?: "", chip, icon, color, progress,
            indeterminate && progress == null, chronoAt, countDown, n.contentIntent, actions, rank, sbn.postTime, oneUi,
        )
    }

    private class Clock(val at: Long, val countDown: Boolean, val running: Boolean, val text: String?)

    /** Inflates Samsung's chronometer remote view just to read where its clock stands. */
    private fun readClock(ctx: Context, rv: RemoteViews): Clock? = runCatching {
        val root = rv.apply(ctx, FrameLayout(ctx))
        fun find(v: View): Chronometer? = when (v) {
            is Chronometer -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        fun anyText(v: View): String? = when (v) {
            is TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { anyText(v.getChildAt(it)) }
            else -> null
        }
        val c = find(root) ?: return@runCatching anyText(root)?.let { Clock(0, false, false, it) }
        val at = c.base - SystemClock.elapsedRealtime() + System.currentTimeMillis()
        // A stopped chronometer keeps its base but shows a frozen text; a running one is within a second of "now - base".
        val running = runCatching {
            Chronometer::class.java.getDeclaredField("mStarted").apply { isAccessible = true }.getBoolean(c)
        }.getOrElse {
            val shown = parseClock(c.text?.toString())
            val live = abs(SystemClock.elapsedRealtime() - c.base) / 1000
            shown == null || abs(shown - live) <= 2
        }
        Clock(at, c.isCountDown, running, c.text?.toString())
    }.onFailure { Log.w(TAG, "live clock unreadable: ${it.javaClass.simpleName}") }.getOrNull()

    private fun parseClock(t: String?): Long? {
        val parts = t?.trim()?.trimStart('-', '−')?.split(':')?.map { it.toLongOrNull() ?: return null } ?: return null
        return parts.fold(0L) { acc, v -> acc * 60 + v }
    }

    /** Fires a notification's own intent (open the app, or one of its buttons). */
    fun send(intent: PendingIntent?) {
        intent ?: return
        runCatching {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            intent.send(options.toBundle())
        }.onFailure { runCatching { intent.send() } }
    }
}
