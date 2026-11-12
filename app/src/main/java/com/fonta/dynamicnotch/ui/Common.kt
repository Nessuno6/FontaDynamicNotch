package com.fonta.dynamicnotch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.fonta.dynamicnotch.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fonta.dynamicnotch.data.KnobShape
import com.fonta.dynamicnotch.data.SliderFillStyle
import com.fonta.dynamicnotch.data.Theme
import com.fonta.dynamicnotch.services.MediaRepo
import kotlin.math.min
import kotlin.random.Random

/** One UI icons (github.com/thatjoshguy67/oneui-icons, MIT), converted to vector drawables. */
object Ic {
    val home = R.drawable.oneui_home
    val folder = R.drawable.oneui_folder
    val brightness = R.drawable.oneui_brightness
    val timer = R.drawable.oneui_timer
    val stopwatch = R.drawable.oneui_stopwatch
    val counter = R.drawable.oneui_calculation
    val add = R.drawable.oneui_add
    val mute = R.drawable.oneui_sound_mute
    val sound = R.drawable.oneui_sound
    val device = R.drawable.oneui_device
    val bluetooth = R.drawable.oneui_bluetooth
    val headphones = R.drawable.oneui_headphones
    val cast = R.drawable.oneui_smart_view
    val shuffle = R.drawable.oneui_shuffle
    /** One UI 9's media output (what the lock screen's player shows over its progress bar). */
    val output = R.drawable.ic_media_output
    val previous = R.drawable.ic_media_previous
    val play = R.drawable.oneui_control_play
    val pause = R.drawable.oneui_control_pause
    val next = R.drawable.ic_media_next
    val repeat = R.drawable.oneui_repeat
    val music = R.drawable.oneui_music
    val document = R.drawable.oneui_document
    val left = R.drawable.oneui_keyboard_arrow_left
    val right = R.drawable.oneui_keyboard_arrow_right
    val up = R.drawable.oneui_arrow_up
    val down = R.drawable.oneui_arrow_down
    val delete = R.drawable.oneui_delete
    val share = R.drawable.oneui_share
    val quickShare = R.drawable.oneui_quick_share
    val alarm = R.drawable.oneui_alarm
    val check = R.drawable.oneui_checkbox_checked
    val error = R.drawable.oneui_error
    val live = R.drawable.oneui_notification
    val bento = R.drawable.oneui_widget
    val arrowLeft = R.drawable.oneui_arrow_left
    val arrowRight = R.drawable.oneui_arrow_right
    val close = R.drawable.oneui_close

    /** One UI's icon for a BentoBar item type, where it has one; null: BentoBar's own icon. */
    fun forBento(type: String): Int? = when (type) {
        "battery" -> R.drawable.oneui_battery_filled
        "cpu" -> R.drawable.oneui_speed
        "memory" -> R.drawable.oneui_manage_storage
        "storage" -> R.drawable.oneui_sd_card
        "heat" -> R.drawable.oneui_temperature
        "displays" -> R.drawable.oneui_monitor
        "network" -> R.drawable.oneui_wifi
        "calendar" -> R.drawable.oneui_calendar
        "event" -> R.drawable.oneui_event
        "clock" -> R.drawable.oneui_time
        "timer", "countdown" -> R.drawable.oneui_timer
        "weather" -> R.drawable.oneui_weather_partly_sunny
        "flight" -> R.drawable.oneui_airplane_mode
        "media" -> R.drawable.oneui_music
        "sound" -> R.drawable.oneui_volume
        "clipboard" -> R.drawable.oneui_clipboard
        "tools" -> R.drawable.oneui_tool
        "folder" -> R.drawable.oneui_folder
        "text" -> R.drawable.oneui_text
        else -> null
    }
}

/** Draws one of the [Ic] icons, tinted. */
@Composable
fun Glyph(id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    androidx.compose.material3.Icon(painterResource(id), contentDescription, modifier, tint)
}

/** Single-line text with tabular digits. */
@Composable
fun Label(
    text: String,
    color: Color,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
) {
    Text(
        text, modifier = modifier, color = color, fontSize = size, fontWeight = weight, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = align,
        style = TextStyle(fontFeatureSettings = "tnum"),
    )
}

/** A square icon button used all over the notch. */
@Composable
fun PanelButton(
    icon: Int,
    theme: Theme,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = theme.controlIconSize.dp,
    tint: Color = theme.iconColor,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(theme.buttonRadius.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(iconSize))
    }
}

/** A soft button with a One UI icon and, beside it, a word (or the icon alone). */
@Composable
fun IconPill(
    icon: Int,
    text: String?,
    theme: Theme,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    iconAfter: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = if (prominent) Color.Black else theme.primaryText
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(theme.buttonRadius.dp))
            .background(if (prominent) theme.accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        if (!iconAfter) Glyph(icon, null, Modifier.size(15.dp), fg)
        if (text != null) Label(text, fg, 13.sp, FontWeight.SemiBold)
        if (iconAfter) Glyph(icon, null, Modifier.size(15.dp), fg)
    }
}

/** A text button with a soft background (Start, Reset, +1 …). */
@Composable
fun PillButton(
    text: String,
    theme: Theme,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(theme.buttonRadius.dp))
            .background(if (prominent) theme.accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, if (prominent) Color.Black else theme.primaryText, 13.sp, FontWeight.SemiBold)
    }
}

/**
 * The notch's slider. `onChange` fires while dragging, `onCommit` once on release
 * (the progress bar seeks only on release).
 */
@Composable
fun NotchSlider(
    value: Float,
    theme: Theme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** One UI 9's progress bar: the played part a wave that moves while [playing], the rest a thin line. */
    wavy: Boolean = false,
    playing: Boolean = false,
    onCommit: (Float) -> Unit = {},
    onChange: (Float) -> Unit,
) {
    val (phase, waveHeight) = rememberWave(wavy && playing)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val shown = if (dragging) dragValue else value
    val fill = if (theme.sliderFillStyle == SliderFillStyle.artwork) {
        MediaRepo.artworkColor?.lightenedForBars?.color ?: theme.sliderFillColor.color
    } else theme.sliderFillColor.color

    Canvas(
        modifier
            .height(26.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = theme.knobSize.toFloat().dp.toPx() / 2
                    fun at(x: Float) = ((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    dragging = true
                    dragValue = at(down.position.x)
                    change(dragValue)
                    down.consume()
                    drag(down.id) {
                        dragValue = at(it.position.x)
                        change(dragValue)
                        it.consume()
                    }
                    commit(dragValue)
                    dragging = false
                }
            },
    ) {
        val knob = theme.knobSize.toFloat().dp.toPx()
        val h = theme.sliderHeight.toFloat().dp.toPx() * (if (dragging) 1.5f else 1f)
        val pad = knob / 2
        val w = size.width - 2 * pad
        val y = size.height / 2
        val r = CornerRadius(h / 2)
        val fw = w * shown.coerceIn(0f, 1f)
        if (wavy) {
            // One UI 9: a straight line, played part in colour; on the played part, soft see-through humps that
            // drift while music plays, rise from the line (never below it) and fade out towards both ends.
            val line = 4.dp.toPx()
            val color = fill.copy(alpha = if (enabled) fill.alpha else 0.35f)
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            if (fw > 0.5f) {
                waveLayers(pad, pad + fw, y - line / 2, (y - line / 2) * 0.95f * waveHeight.value * (if (dragging) 0.6f else 1f), color, phase.value)
            }
            drawLine(theme.sliderTrackColor.color, Offset(pad + fw, y), Offset(pad + w, y), line, cap)
            if (fw > 0.5f) drawLine(color, Offset(pad, y), Offset(pad + fw, y), line, cap)
            if (enabled && theme.knobShape != KnobShape.none) {
                // One UI's knob: the played colour in a white ring, with a soft glow of that colour.
                val k = Offset(pad + fw, y)
                drawCircle(color.copy(alpha = 0.28f * color.alpha), knob * 0.85f, k)
                drawCircle(Color.White, knob / 2 + 1.5.dp.toPx(), k)
                drawCircle(color, knob / 2 - 0.5.dp.toPx(), k)
            }
            return@Canvas
        }
        drawRoundRect(theme.sliderTrackColor.color, Offset(pad, y - h / 2), Size(w, h), r)
        if (fw > 0.5f) drawRoundRect(fill.copy(alpha = if (enabled) fill.alpha else 0.35f), Offset(pad, y - h / 2), Size(fw, h), r)
        val showKnob = theme.knobShape != KnobShape.none && enabled && (dragging || !theme.knobOnlyWhileDragging)
        if (showKnob) {
            val kx = pad + fw
            if (theme.knobShape == KnobShape.circle) {
                drawCircle(theme.sliderKnobColor.color, knob / 2, Offset(kx, y))
            } else {
                drawRoundRect(
                    theme.sliderKnobColor.color, Offset(kx - knob * 0.3f, y - knob * 0.8f),
                    Size(knob * 0.6f, knob * 1.6f), CornerRadius(knob * 0.3f),
                )
            }
        }
    }
}

/**
 * A wave's phase (radians) that runs while [playing], and how tall the wave stands (0…1): it settles
 * flat when the music pauses and rises again when it plays, as One UI 9's does.
 */
@Composable
fun rememberWave(playing: Boolean, speed: Float = 1f): Pair<androidx.compose.runtime.State<Float>, androidx.compose.runtime.State<Float>> {
    val phase = remember { mutableFloatStateOf(0f) }
    val height = androidx.compose.animation.core.animateFloatAsState(
        if (playing) 1f else 0f, androidx.compose.animation.core.tween(450), label = "waveHeight",
    )
    LaunchedEffect(playing, speed) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameMillis { t ->
                if (last != 0L) phase.floatValue = (phase.floatValue + (t - last) / 1000f * 2f * Math.PI.toFloat() * 0.9f * speed) % (2f * Math.PI.toFloat() * 1000f)
                last = t
            }
        }
    }
    return phase to height
}

/**
 * One UI 9's media waves: three soft, see-through layers of humps standing on [base] between [x0] and
 * [x1], at most [maxH] tall, each drifting at its own pace with [phase] and fading out at both ends.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.waveLayers(x0: Float, x1: Float, base: Float, maxH: Float, color: Color, phase: Float) {
    if (x1 - x0 < 2f || maxH < 0.5f) return
    val two = 2f * Math.PI.toFloat()
    val light = androidx.compose.ui.graphics.lerp(color, Color.White, 0.45f)
    // (colour, wavelength in dp, drift speed, offset, height share)
    val layers = listOf(
        Layer(light.copy(alpha = 0.35f * color.alpha), 70f, 0.35f, 0.0f, 1.0f),
        Layer(color.copy(alpha = 0.45f * color.alpha), 48f, -0.55f, 2.1f, 0.8f),
        Layer(light.copy(alpha = 0.55f * color.alpha), 34f, 0.8f, 4.2f, 0.55f),
    )
    val step = 2f
    for (l in layers) {
        val len = l.lengthDp * density
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(x0, base)
        var x = x0
        while (true) {
            val t = (x - x0) / (x1 - x0)
            val env = Math.pow(kotlin.math.sin(Math.PI * t), 0.9).toFloat()
            val hump = 0.5f + 0.5f * kotlin.math.sin(x / len * two - phase * l.speed + l.offset)
            val swell = 0.6f + 0.4f * kotlin.math.sin(x / (len * 2.7f) * two + phase * l.speed * 0.5f + l.offset * 2f)
            path.lineTo(x, base - maxH * l.share * env * hump * hump * swell)
            if (x >= x1) break
            x = min(x1, x + step)
        }
        path.lineTo(x1, base)
        path.close()
        drawPath(path, l.color)
    }
}

private class Layer(val color: Color, val lengthDp: Float, val speed: Float, val offset: Float, val share: Float)

/** One UI 9's waves beside the closed notch, in place of the bars: the humps on a short line. */
@Composable
fun MusicWave(speed: Float, color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val (phase, height) = rememberWave(playing, speed)
    Canvas(modifier) {
        val line = 2.5.dp.toPx()
        val base = size.height * 0.78f
        val c = color.copy(alpha = if (playing) 1f else 0.6f)
        waveLayers(line / 2, size.width - line / 2, base - line / 2, (base - line / 2) * (0.2f + 0.8f * height.value), c, phase.value)
        drawLine(c, Offset(line / 2, base), Offset(size.width - line / 2, base), line, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The fake visualiser beside the closed notch. Animates (≤30 fps) only while music plays. */
@Composable
fun MusicBars(count: Int, speed: Float, color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val heights = remember(count) { FloatArray(count) { 0.25f + 0.5f * Random.nextFloat() } }
    val targets = remember(count) { FloatArray(count) { Random.nextFloat() } }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing, count, speed) {
        if (!playing) return@LaunchedEffect
        val next = LongArray(count)
        var last = 0L
        val sp = speed.coerceIn(0.2f, 4f)
        while (true) {
            withFrameMillis { t ->
                if (t - last >= 33) {
                    val dt = if (last == 0L) 0.033f else (t - last) / 1000f
                    last = t
                    for (i in 0 until count) {
                        if (t >= next[i]) {
                            targets[i] = 0.15f + Random.nextFloat() * 0.85f
                            next[i] = t + ((160 + Random.nextInt(240)) / sp).toLong()
                        }
                        heights[i] += (targets[i] - heights[i]) * min(1f, dt * 9f * sp)
                    }
                    frame++
                }
            }
        }
    }
    Canvas(modifier) {
        frame // redraw on every animation step
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            val bh = (size.height * heights[i]).coerceAtLeast(bw)
            drawRoundRect(
                color.copy(alpha = if (playing) 1f else 0.4f),
                Offset(i * (bw + gap), (size.height - bh) / 2), Size(bw, bh), CornerRadius(bw / 2),
            )
        }
    }
}
