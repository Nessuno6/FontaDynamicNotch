package com.fonta.dynamicnotch.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fonta.dynamicnotch.R
import java.text.DateFormat
import java.util.Date

/** Timers, stopwatch and counters. All times are wall-clock, so they survive restarts and reboots. */
object ToolsStore {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val expiryCheck = Runnable { checkExpired() }

    var state by mutableStateOf(ToolsState())
        private set

    val ringingTimers: List<TimerPreset> get() = state.timers.filter { it.ringing }
    val isRinging: Boolean get() = state.timers.any { it.ringing }

    /** The running timer that ends first. */
    val soonestRunning: TimerPreset? get() = state.timers.filter { it.isRunning }.minByOrNull { it.endAt!! }

    fun init(context: Context) {
        app = context.applicationContext
        JsonFiles.read("state.json")?.let { text ->
            runCatching { AppJson.decodeFromString<ToolsState>(text) }.getOrNull()?.let { state = normalized(it) }
        }
        // Timers that ended while the app was not running: tell the user, no ringing.
        val now = System.currentTimeMillis()
        var changed = false
        val timers = state.timers.map { t ->
            when {
                t.ringing -> { changed = true; t.copy(ringing = false, endAt = null) }
                t.endAt != null && t.endAt < now - 60_000 -> {
                    changed = true
                    val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.endAt))
                    Alarm.notifyMissed(app, t, at)
                    t.copy(endAt = null)
                }
                else -> t
            }
        }
        if (changed) set(state.copy(timers = timers))
        reschedule()
    }

    private fun normalized(s: ToolsState): ToolsState {
        val timers = ToolsState.defaultTimers.map { d -> s.timers.firstOrNull { it.id == d.id } ?: d }
        val counters = s.counters.ifEmpty { listOf(Counter()) }
        return s.copy(
            timers = timers,
            selectedTimer = s.selectedTimer.coerceIn(0, timers.lastIndex),
            counters = counters,
            selectedCounter = s.selectedCounter.coerceIn(0, counters.lastIndex),
        )
    }

    private fun set(next: ToolsState) {
        if (next == state) return
        state = next
        JsonFiles.writeDebounced("state.json") { AppJson.encodeToString(ToolsState.serializer(), state) }
    }

    fun selectTool(tool: Tool) = set(state.copy(lastTool = tool))

    // ---- Timers ----

    fun selectTimer(index: Int) = set(state.copy(selectedTimer = index.coerceIn(0, state.timers.lastIndex)))

    private fun updateTimer(id: Int, f: (TimerPreset) -> TimerPreset) {
        set(state.copy(timers = state.timers.map { if (it.id == id) f(it) else it }))
        reschedule()
    }

    fun toggleTimer(id: Int) {
        val now = System.currentTimeMillis()
        updateTimer(id) { t ->
            when {
                t.ringing -> t
                t.endAt != null -> t.copy(endAt = null, pausedRemaining = t.remaining(now))
                else -> {
                    val left = t.pausedRemaining ?: t.duration
                    if (left <= 0) t else t.copy(endAt = now + (left * 1000).toLong(), pausedRemaining = null)
                }
            }
        }
    }

    fun resetTimer(id: Int) {
        updateTimer(id) { it.copy(endAt = null, pausedRemaining = null, ringing = false) }
        if (!isRinging) Alarm.stop(app)
    }

    fun editPreset(id: Int, name: String, duration: Double) {
        updateTimer(id) { it.copy(name = name, duration = duration.coerceAtLeast(1.0), endAt = null, pausedRemaining = null, ringing = false) }
    }

    fun stopAlarm() {
        set(state.copy(timers = state.timers.map { if (it.ringing) it.copy(ringing = false, endAt = null, pausedRemaining = null) else it }))
        Alarm.stop(app)
    }

    /** Rings every timer whose end has passed. Called by the in-app wake-up and the AlarmManager receiver. */
    fun checkExpired() {
        val now = System.currentTimeMillis()
        val due = state.timers.filter { it.isRunning && it.endAt!! <= now }
        if (due.isNotEmpty()) {
            set(state.copy(timers = state.timers.map { if (it in due) it.copy(ringing = true, endAt = null) else it }))
            Alarm.ring(app, ringingTimers)
        }
        reschedule()
    }

    /** One wake-up for the nearest end: a Handler while the app lives, AlarmManager as the backstop. */
    private fun reschedule() {
        main.removeCallbacks(expiryCheck)
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            app, 1, Intent(app, TimerAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = soonestRunning?.endAt
        if (next == null) {
            am.cancel(pi)
            return
        }
        main.postDelayed(expiryCheck, (next - System.currentTimeMillis()).coerceAtLeast(0) + 20)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) }
    }

    // ---- Stopwatch ----

    fun toggleStopwatch() {
        val now = System.currentTimeMillis()
        val sw = state.stopwatch
        set(state.copy(stopwatch = if (sw.isRunning) sw.copy(startAt = null, accumulated = sw.elapsed(now)) else sw.copy(startAt = now)))
    }

    fun lap() {
        val sw = state.stopwatch
        if (!sw.isRunning) return
        val total = sw.elapsed(System.currentTimeMillis())
        set(state.copy(stopwatch = sw.copy(laps = sw.laps + (total - sw.laps.sum()))))
    }

    fun resetStopwatch() = set(state.copy(stopwatch = StopwatchState()))

    // ---- Counters ----

    val currentCounter: Counter get() = state.counters[state.selectedCounter.coerceIn(0, state.counters.lastIndex)]

    fun stepCounterSelection(delta: Int) {
        val n = state.counters.size
        set(state.copy(selectedCounter = ((state.selectedCounter + delta) % n + n) % n))
    }

    fun changeCounter(delta: Int) {
        val id = currentCounter.id
        set(state.copy(counters = state.counters.map { if (it.id == id) it.copy(value = it.value + delta) else it }))
    }

    fun resetCounter() {
        val id = currentCounter.id
        set(state.copy(counters = state.counters.map { if (it.id == id) it.copy(value = 0) else it }))
    }

    fun addCounter(name: String) = set(state.copy(counters = state.counters + Counter(name = name.ifBlank { "Counter" })))

    fun renameCounter(id: String, name: String) =
        set(state.copy(counters = state.counters.map { if (it.id == id) it.copy(name = name) else it }))

    fun deleteCounter(id: String) {
        if (state.counters.size <= 1) return
        val list = state.counters.filter { it.id != id }
        set(state.copy(counters = list, selectedCounter = state.selectedCounter.coerceIn(0, list.lastIndex)))
    }

    fun moveCounter(index: Int, delta: Int) {
        val to = index + delta
        if (index !in state.counters.indices || to !in state.counters.indices) return
        val list = state.counters.toMutableList()
        list.add(to, list.removeAt(index))
        set(state.copy(counters = list))
    }
}

/** Fires when the nearest timer ends, even if the app process was asleep. Also re-arms after a reboot. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ToolsStore is initialised by NotchApp before this runs.
        when (intent.action) {
            Alarm.ACTION_STOP -> ToolsStore.stopAlarm()
            else -> ToolsStore.checkExpired()
        }
    }
}

/** Loops the alarm sound until dismissed, with a notification as the fallback way to stop it. */
object Alarm {
    const val ACTION_STOP = "com.fonta.dynamicnotch.STOP_ALARM"
    private const val CHANNEL = "timers"
    private const val RING_ID = 10
    private var player: MediaPlayer? = null

    private fun channel(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Timers", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
            )
        }
        return nm
    }

    fun soundUri(): Uri {
        val custom = SettingsStore.settings.alarmSoundUri
        if (custom.isNotBlank()) return Uri.parse(custom)
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    fun ring(context: Context, timers: List<TimerPreset>) {
        play(context, loop = true)
        val stop = PendingIntent.getBroadcast(
            context, 2, Intent(context, TimerAlarmReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(if (timers.size == 1) "⏲ ${timers[0].name} finished" else "⏲ ${timers.size} timers finished")
            .setContentText(timers.joinToString(", ") { it.name })
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        runCatching { channel(context).notify(RING_ID, n) }
    }

    /** Plays the alarm sound; `loop = false` is the Settings preview. */
    fun play(context: Context, loop: Boolean) {
        stopSound()
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(context, soundUri())
                isLooping = loop
                val v = SettingsStore.settings.alarmVolume.toFloat().coerceIn(0f, 1f)
                setVolume(v, v)
                setOnCompletionListener { if (!loop) stopSound() }
                prepare()
                start()
            }
        }
    }

    fun stopSound() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
    }

    fun stop(context: Context) {
        stopSound()
        runCatching { channel(context).cancel(RING_ID) }
    }

    fun notifyMissed(context: Context, timer: TimerPreset, at: String) {
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("⏲ ${timer.name} ended at $at")
            .setAutoCancel(true)
            .build()
        runCatching { channel(context).notify(100 + timer.id, n) }
    }
}
