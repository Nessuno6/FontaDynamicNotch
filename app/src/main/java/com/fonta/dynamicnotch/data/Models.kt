package com.fonta.dynamicnotch.data

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.PI

/** A button in the open notch's top bar. */
@Serializable
enum class TopModule(val title: String) {
    homeFiles("Home / Files"),
    screenBrightness("Screen brightness"),
    timer("Timer"),
    stopwatch("Stopwatch"),
    counter("Counter");

    val tool: Tool?
        get() = when (this) {
            timer -> Tool.timer
            stopwatch -> Tool.stopwatch
            counter -> Tool.counter
            else -> null
        }

    companion object {
        val defaultLeft = listOf(homeFiles, screenBrightness)
        val defaultRight = listOf(timer, stopwatch, counter)
    }
}

@Serializable
enum class Tool { timer, stopwatch, counter }

enum class NotchTab { home, files, live, bento }

/** One spring, stored like the Mac app: response (seconds) and damping fraction. */
@Serializable
data class SpringSetting(val response: Double = 0.4, val dampingFraction: Double = 0.8) {
    fun <T> spec(): FiniteAnimationSpec<T> {
        val r = response.coerceIn(0.05, 3.0)
        val stiffness = ((2 * PI / r) * (2 * PI / r)).toFloat()
        return spring(dampingRatio = dampingFraction.coerceIn(0.1, 2.0).toFloat(), stiffness = stiffness)
    }
}

@Serializable
data class AnimationSettings(
    val open: SpringSetting = SpringSetting(0.42, 0.78),
    val close: SpringSetting = SpringSetting(0.35, 0.90),
    val wings: SpringSetting = SpringSetting(0.40, 0.80),
    val brightness: SpringSetting = SpringSetting(0.30, 0.85),
    val tabSwitch: SpringSetting = SpringSetting(0.35, 0.90),
    val alarmOpen: SpringSetting = SpringSetting(0.45, 0.70),
)

/** All user settings. Every key has a default, so old files keep loading when settings are added. */
@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    // General
    /** The notch at all (the Quick Settings tile switches this). */
    val notchEnabled: Boolean = true,
    val showNotchWhenIdle: Boolean = true,
    val hideWhenStatusBarHidden: Boolean = true,
    val hoverOpenDelay: Double = 0.10,
    val closeDelay: Double = 0.0,
    val pausedMusicVisibleMinutes: Double = 10.0,
    /** Show live notifications (Now Bar items, Live Updates, calls, downloads…) in the notch. */
    val showLive: Boolean = true,
    /** Also pick up plain ongoing notifications that have a clock or a progress bar. */
    val liveIncludeOngoing: Boolean = true,
    val hiddenLivePackages: List<String> = emptyList(),
    /** Show BentoBar's items in the notch: a tab of their own, and the chosen ones beside the closed notch. */
    val bentoInNotch: Boolean = false,
    /** While BentoBar is in the notch, its own bar in the status bar is hidden (‹ included). */
    val hideBentoBar: Boolean = true,
    /** Ids of up to two BentoBar items for the closed notch's wings, used when nothing else needs a wing. */
    val bentoWingItems: List<String> = emptyList(),
    /** BentoBar tiles left and right of the closed notch, each in order: the first is the one nearest the notch. */
    val bentoLeft: List<String> = emptyList(),
    val bentoRight: List<String> = emptyList(),
    /** Turn off Samsung's own Now Bar and status-bar chip, so live items show only in the notch. */
    val hideNowBar: Boolean = false,
    /** Blur what is behind the open notch (glass) instead of a solid background. */
    val liveBlur: Boolean = true,
    /** Keep the closed notch frosted glass too, instead of solid black. */
    val blurWhenClosed: Boolean = true,
    /** How much of the background colour lies over the blur (0 = clear glass, 1 = solid). */
    val glassTint: Double = 0.6,
    /** A thin outline around the notch. Width in dp; 0 turns it off. */
    val borderWidth: Double = 1.0,
    val borderColor: CodableColor = CodableColor.white(),
    /** Round the notch like the screen's own corners instead of the theme's radii. */
    val matchDeviceCorners: Boolean = true,
    // Appearance
    val selectedThemeID: String = Theme.DEFAULT_ID,
    // Music
    val musicBarCount: Int = 5,
    val musicBarSpeed: Double = 1.0,
    val musicBarsUseArtworkColor: Boolean = true,
    /** One UI 9's waves instead of the bars: a moving wave beside the closed notch and a wavy progress bar. */
    val oneUiWaves: Boolean = false,
    val marqueeEnabled: Boolean = true,
    // Timers
    val alarmSoundUri: String = "",
    val alarmVolume: Double = 1.0,
    // Animations
    val animations: AnimationSettings = AnimationSettings(),
    // Layout & modules
    val leftModules: List<TopModule> = TopModule.defaultLeft,
    val rightModules: List<TopModule> = TopModule.defaultRight,
    val hiddenModules: List<TopModule> = emptyList(),
    val openWidth: Double = 640.0,
    val openHeight: Double = 214.0,
    val closedWingWidth: Double = 40.0,
    val notchWidth: Double = 104.0,
    val closedHeightExtra: Double = 0.0,
    // Advanced
    val showDebugOverlay: Boolean = false,
) {
    /** The tiles' ids by side. Settings saved before sides existed alternate, as they did then. */
    val bentoSides: Pair<List<String>, List<String>>
        get() = if (bentoLeft.isEmpty() && bentoRight.isEmpty() && bentoWingItems.isNotEmpty()) {
            bentoWingItems.filterIndexed { i, _ -> i % 2 == 0 } to bentoWingItems.filterIndexed { i, _ -> i % 2 == 1 }
        } else bentoLeft to bentoRight

    fun isVisible(m: TopModule) = m !in hiddenModules
    val visibleTools: List<Tool> get() = cleaned(rightModules, TopModule.defaultRight).filter(::isVisible).mapNotNull { it.tool }
    val visibleLeft: List<TopModule> get() = cleaned(leftModules, TopModule.defaultLeft).filter(::isVisible)

    companion object {
        /** Keeps a saved order valid: no duplicates, nothing from the other group, nothing missing. */
        fun cleaned(list: List<TopModule>, allowed: List<TopModule>): List<TopModule> {
            val kept = list.filter { it in allowed }.distinct()
            return kept + allowed.filter { it !in kept }
        }
    }
}

/** An sRGB colour that can be saved in JSON. */
@Serializable
data class CodableColor(val r: Double = 1.0, val g: Double = 1.0, val b: Double = 1.0, val a: Double = 1.0) {
    val color: Color get() = Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f), a.toFloat().coerceIn(0f, 1f))

    companion object {
        fun white(a: Double = 1.0) = CodableColor(1.0, 1.0, 1.0, a)
        val black = CodableColor(0.0, 0.0, 0.0)
        fun of(c: Color) = CodableColor(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble(), c.alpha.toDouble())
    }
}

@Serializable
enum class KnobShape { circle, capsule, none }

@Serializable
enum class SliderFillStyle { solid, artwork }

/** A visual theme. "Default" is built in and read-only; user themes are saved as themes/<id>.json. */
@Serializable
data class Theme(
    val id: String = DEFAULT_ID,
    val name: String = "Default",
    // Colours
    val fallbackBackgroundColor: CodableColor = CodableColor.black,
    val primaryTextColor: CodableColor = CodableColor.white(),
    val secondaryTextColor: CodableColor = CodableColor.white(0.6),
    val accentColor: CodableColor = CodableColor.white(),
    val iconTint: CodableColor = CodableColor.white(),
    val sliderTrackColor: CodableColor = CodableColor.white(0.2),
    val sliderFillColor: CodableColor = CodableColor.white(),
    val sliderKnobColor: CodableColor = CodableColor.white(),
    val useArtworkColor: Boolean = true,
    val artworkIntensity: Double = 1.0,
    /** Brightest the artwork background may get (relative luminance). 0.183 keeps white text at 4.5:1. */
    val maxBackgroundLuminance: Double = 0.183,
    // Corner rounding (dp)
    val topFilletRadius: Double = 12.0,
    val openBottomRadius: Double = 28.0,
    val closedBottomRadius: Double = 12.0,
    val coverRadiusOpen: Double = 12.0,
    val coverRadiusClosed: Double = 5.0,
    val buttonRadius: Double = 8.0,
    // Shadow (open state only)
    val shadowEnabled: Boolean = true,
    val shadowTint: CodableColor = CodableColor.black,
    val shadowOpacity: Double = 0.35,
    val shadowRadius: Double = 12.0,
    val shadowYOffset: Double = 4.0,
    // Icon sizes (dp)
    val topBarIconSize: Double = 17.0,
    val controlIconSize: Double = 24.0,
    // Sliders
    val sliderHeight: Double = 5.0,
    val knobSize: Double = 13.0,
    val knobShape: KnobShape = KnobShape.circle,
    val knobOnlyWhileDragging: Boolean = true,
    val sliderFillStyle: SliderFillStyle = SliderFillStyle.solid,
) {
    val isBuiltIn get() = id == DEFAULT_ID
    val primaryText get() = primaryTextColor.color
    val secondaryText get() = secondaryTextColor.color
    val accent get() = accentColor.color
    val iconColor get() = iconTint.color

    companion object {
        const val DEFAULT_ID = "default"
        fun newId(): String = UUID.randomUUID().toString()
    }
}

// ---- Tools state ----

@Serializable
data class TimerPreset(
    val id: Int,
    val name: String,
    val duration: Double,
    /** Wall-clock end (epoch ms) while running, else null. */
    val endAt: Long? = null,
    /** Seconds left while paused, else null. */
    val pausedRemaining: Double? = null,
    val ringing: Boolean = false,
) {
    val isRunning get() = endAt != null && !ringing
    val isPaused get() = pausedRemaining != null
    fun remaining(now: Long): Double = when {
        ringing -> 0.0
        endAt != null -> ((endAt - now) / 1000.0).coerceAtLeast(0.0)
        pausedRemaining != null -> pausedRemaining
        else -> duration
    }
}

@Serializable
data class StopwatchState(
    /** Wall-clock start (epoch ms) of the current run, else null. */
    val startAt: Long? = null,
    val accumulated: Double = 0.0,
    val laps: List<Double> = emptyList(),
) {
    val isRunning get() = startAt != null
    fun elapsed(now: Long): Double = accumulated + (startAt?.let { (now - it) / 1000.0 } ?: 0.0)
}

@Serializable
data class Counter(val id: String = UUID.randomUUID().toString(), val name: String = "Counter", val value: Int = 0)

@Serializable
data class ToolsState(
    val timers: List<TimerPreset> = defaultTimers,
    val selectedTimer: Int = 0,
    val stopwatch: StopwatchState = StopwatchState(),
    val counters: List<Counter> = listOf(Counter()),
    val selectedCounter: Int = 0,
    val lastTool: Tool = Tool.timer,
) {
    companion object {
        val defaultTimers = listOf(
            TimerPreset(0, "1 min", 60.0),
            TimerPreset(1, "5 min", 300.0),
            TimerPreset(2, "10 min", 600.0),
            TimerPreset(3, "Pomodoro", 1500.0),
            TimerPreset(4, "1 hour", 3600.0),
        )
    }
}

object TimeFormat {
    /** m:ss under an hour, h:mm:ss above. Rounds up so a fresh 5:00 timer reads 5:00. */
    fun countdown(seconds: Double): String = clock(kotlin.math.ceil(seconds.coerceAtLeast(0.0)).toLong())

    fun coarse(seconds: Double): String = clock(seconds.coerceAtLeast(0.0).toLong())

    fun clock(total: Long): String {
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** mm:ss.cc (h:mm:ss.cc after an hour). */
    fun stopwatch(seconds: Double): String {
        val cs = (seconds.coerceAtLeast(0.0) * 100).toLong()
        val h = cs / 360000
        val m = (cs % 360000) / 6000
        val s = (cs % 6000) / 100
        val c = cs % 100
        return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, c) else "%02d:%02d.%02d".format(m, s, c)
    }
}
