package com.fonta.dynamicnotch.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fonta.dynamicnotch.data.AppSettings
import com.fonta.dynamicnotch.data.NotchTab
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.Theme
import com.fonta.dynamicnotch.data.TimeFormat
import com.fonta.dynamicnotch.data.Tool
import com.fonta.dynamicnotch.data.ToolsStore
import com.fonta.dynamicnotch.data.TopModule
import com.fonta.dynamicnotch.notch.CoverSlot
import com.fonta.dynamicnotch.notch.NotchGeometry
import com.fonta.dynamicnotch.notch.NotchState
import com.fonta.dynamicnotch.services.AudioRepo
import com.fonta.dynamicnotch.services.BrightnessRepo
import com.fonta.dynamicnotch.services.LiveRepo
import com.fonta.dynamicnotch.services.MediaRepo
import com.fonta.dynamicnotch.services.OutputKind
import kotlinx.coroutines.delay

/** Everything inside the open notch: the top bar and the Home tab, Files tab, or the alarm while ringing. */
@Composable
fun OpenContent(g: NotchGeometry, theme: Theme, settings: AppSettings) {
    val density = LocalDensity.current
    // On open, show the running timer/stopwatch if any; otherwise the last tool used.
    LaunchedEffect(Unit) {
        when {
            ToolsStore.soonestRunning != null && Tool.timer in settings.visibleTools -> ToolsStore.selectTool(Tool.timer)
            ToolsStore.state.stopwatch.isRunning && Tool.stopwatch in settings.visibleTools -> ToolsStore.selectTool(Tool.stopwatch)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(g, theme, settings)
            val screen = when {
                ToolsStore.isRinging -> "alarm"
                NotchState.tab == NotchTab.files -> "files"
                NotchState.tab == NotchTab.live && LiveRepo.items.isNotEmpty() -> "live"
                NotchState.tab == NotchTab.bento && settings.bentoInNotch -> "bento"
                else -> "home"
            }
            Crossfade(
                screen, animationSpec = settings.animations.tabSwitch.spec(), label = "tab",
                modifier = Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
            ) { s ->
                when (s) {
                    "alarm" -> AlarmView(theme)
                    "files" -> FilesTab(theme, g.compact)
                    "live" -> LiveTab(theme, g.compact)
                    "bento" -> BentoTab(theme, g.compact)
                    else -> HomeTab(g, theme, settings)
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") density
}

@Composable
private fun HomeTab(g: NotchGeometry, theme: Theme, settings: AppSettings) {
    val tools = settings.visibleTools
    if (g.compact) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MediaPanel(theme, settings, compact = true, modifier = Modifier.weight(1f).fillMaxWidth())
            if (tools.isNotEmpty()) ToolPanel(theme, settings, tools, Modifier.fillMaxWidth().height(132.dp))
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaPanel(theme, settings, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
            if (tools.isNotEmpty()) ToolPanel(theme, settings, tools, Modifier.width(196.dp).fillMaxHeight())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

private fun toolIcon(tool: Tool): Int = when (tool) {
    Tool.timer -> Ic.timer
    Tool.stopwatch -> Ic.stopwatch
    Tool.counter -> Ic.counter
}

@Composable
private fun TopBar(g: NotchGeometry, theme: Theme, settings: AppSettings) {
    val density = LocalDensity.current
    var brightnessOpen by remember { mutableStateOf(false) }
    with(density) {
        Row(Modifier.fillMaxWidth().height(g.topBarH.toDp()), verticalAlignment = Alignment.CenterVertically) {
            // Left of the notch
            Row(
                Modifier.width((g.notchLeft - g.openLeft).toDp()).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (m in settings.visibleLeft) {
                    when (m) {
                        TopModule.homeFiles -> AnimatedVisibility(!(g.compact && brightnessOpen)) { TabSwitch(theme) }
                        TopModule.screenBrightness -> if (BrightnessRepo.available) {
                            BrightnessControl(theme, settings, brightnessOpen) { brightnessOpen = it }
                        }
                        else -> {}
                    }
                }
            }
            Spacer(Modifier.weight(1f)) // the notch itself
            // Right of the notch
            Row(
                Modifier.width((g.openRight - g.notchRight).toDp()).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
            ) {
                val current = currentTool(settings)
                for (tool in settings.visibleTools) {
                    val selected = tool == current && NotchState.tab == NotchTab.home
                    PanelButton(
                        toolIcon(tool), theme, size = 32.dp, iconSize = theme.topBarIconSize.dp,
                        tint = if (selected) theme.accent else theme.iconColor.copy(alpha = 0.55f),
                        background = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                    ) {
                        ToolsStore.selectTool(tool)
                        NotchState.tab = NotchTab.home
                    }
                }
            }
        }
    }
}

fun currentTool(settings: AppSettings): Tool? {
    val tools = settings.visibleTools
    return ToolsStore.state.lastTool.takeIf { it in tools } ?: tools.firstOrNull()
}

/** The Home / Files pair of icons. */
@Composable
private fun TabSwitch(theme: Theme) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).padding(2.dp)) {
        // The Live tab is there only while something is live.
        val tabs = listOf(NotchTab.home to Ic.home, NotchTab.files to Ic.folder) +
            (if (LiveRepo.items.isNotEmpty()) listOf(NotchTab.live to Ic.live) else emptyList()) +
            (if (SettingsStore.settings.bentoInNotch) listOf(NotchTab.bento to Ic.bento) else emptyList())
        for ((tab, icon) in tabs) {
            val selected = NotchState.tab == tab
            Box(
                Modifier.size(width = 32.dp, height = 26.dp).clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { NotchState.tab = tab },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(icon, null, tint = if (selected) theme.accent else theme.iconColor.copy(alpha = 0.55f), modifier = Modifier.size(theme.topBarIconSize.dp))
            }
        }
    }
}

/** Tap the sun to expand it into a brightness slider; tap again to collapse. */
@Composable
private fun BrightnessControl(theme: Theme, settings: AppSettings, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    val width by animateDpAsState(if (expanded) 112.dp else 32.dp, settings.animations.brightness.spec(), label = "brightness")
    Row(
        Modifier.width(width).height(30.dp).clip(RoundedCornerShape(50))
            .background(if (expanded) Color.White.copy(alpha = 0.14f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp, 30.dp).clip(CircleShape).clickable { onExpanded(!expanded) }, contentAlignment = Alignment.Center) {
            Glyph(Ic.brightness, null, tint = theme.iconColor.copy(alpha = if (expanded) 1f else 0.55f), modifier = Modifier.size(theme.topBarIconSize.dp))
        }
        if (expanded) {
            NotchSlider(BrightnessRepo.value, theme, Modifier.weight(1f).padding(end = 6.dp)) { BrightnessRepo.set(it) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Media
// ---------------------------------------------------------------------------------------------

@Composable
private fun MediaPanel(theme: Theme, settings: AppSettings, compact: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val track = MediaRepo.track
    if (compact) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoverSlot(Modifier.size(88.dp).clip(RoundedCornerShape(theme.coverRadiusOpen.dp)).clickable { MediaRepo.openPlayer(context); NotchState.close() })
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    VolumeRow(theme, showName = false)
                    TrackText(theme, settings, context)
                }
            }
            Spacer(Modifier.weight(1f))
            if (track != null) {
                ProgressRow(theme)
                Controls(theme, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverSlot(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(theme.coverRadiusOpen.dp)).clickable { MediaRepo.openPlayer(context); NotchState.close() })
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                VolumeRow(theme, showName = true)
                TrackText(theme, settings, context)
                if (track != null) {
                    ProgressRow(theme)
                    Controls(theme, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TrackText(theme: Theme, settings: AppSettings, context: Context) {
    val track = MediaRepo.track
    Column {
        when {
            track != null -> {
                val marquee = if (settings.marqueeEnabled) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                Label(track.title, theme.primaryText, 15.sp, FontWeight.SemiBold, modifier = marquee)
                Label(track.artist, theme.secondaryText, 13.sp)
            }
            !MediaRepo.hasAccess -> {
                Label("Music controls are off", theme.primaryText, 14.sp, FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                PillButton("Allow media access", theme, Modifier.fillMaxWidth()) {
                    NotchState.close()
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            else -> {
                Label("Nothing playing", theme.primaryText, 14.sp, FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                PillButton("Open music app", theme, Modifier.fillMaxWidth()) {
                    MediaRepo.openPlayer(context)
                    NotchState.close()
                }
            }
        }
    }
}

/** Speaker icon (tap = mute), media volume slider, and the output button. */
@Composable
private fun VolumeRow(theme: Theme, showName: Boolean) {
    val v = AudioRepo.volume
    val icon = when {
        AudioRepo.muted || v <= 0f -> Ic.mute
        v < 0.34f -> Ic.sound
        v < 0.67f -> Ic.sound
        else -> Ic.sound
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelButton(icon, theme, size = 28.dp, iconSize = 18.dp, tint = theme.iconColor.copy(alpha = 0.85f)) { AudioRepo.toggleMute() }
        NotchSlider(if (AudioRepo.muted) 0f else v, theme, Modifier.weight(1f).padding(horizontal = 2.dp)) { AudioRepo.changeVolume(it) }
        // One UI's media output icon, whatever plays the sound (its name says which).
        val out = Ic.output
        Row(
            Modifier.height(28.dp).clip(RoundedCornerShape(theme.buttonRadius.dp)).background(Color.White.copy(alpha = 0.12f))
                .clickable { AudioRepo.openOutputPicker(); NotchState.close() }.padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Glyph(out, null, tint = theme.iconColor, modifier = Modifier.size(16.dp))
            if (showName) Label(AudioRepo.outputName, theme.primaryText, 11.sp, modifier = Modifier.width(72.dp))
        }
    }
}

/** Draggable progress bar (seeks on release) with the current time and the length. */
@Composable
private fun ProgressRow(theme: Theme) {
    val track = MediaRepo.track ?: return
    var pos by remember { mutableLongStateOf(MediaRepo.positionMs()) }
    var drag by remember { mutableFloatStateOf(-1f) }
    // Interpolated locally; only runs while the notch is open.
    LaunchedEffect(track.key, MediaRepo.isPlaying) {
        while (true) {
            pos = MediaRepo.positionMs()
            delay(if (MediaRepo.isPlaying) 100 else 500)
        }
    }
    val dur = track.durationMs
    val shownMs = if (drag >= 0 && dur > 0) (drag * dur).toLong() else pos
    Column {
        NotchSlider(
            if (dur > 0) (shownMs.toFloat() / dur).coerceIn(0f, 1f) else 0f, theme, Modifier.fillMaxWidth().height(if (SettingsStore.settings.oneUiWaves) 30.dp else 20.dp),
            enabled = MediaRepo.canSeek,
            wavy = SettingsStore.settings.oneUiWaves, playing = MediaRepo.isPlaying,
            onCommit = { MediaRepo.seekTo((it * dur).toLong()); pos = (it * dur).toLong(); drag = -1f },
        ) { drag = it }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(TimeFormat.clock(shownMs / 1000), theme.secondaryText, 10.sp)
            Label(if (dur > 0) TimeFormat.clock(dur / 1000) else "", theme.secondaryText, 10.sp)
        }
    }
}

@Composable
private fun Controls(theme: Theme, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val dim = theme.iconColor.copy(alpha = 0.7f)
        MediaRepo.shuffleAction?.let { a -> PanelButton(Ic.shuffle, theme, iconSize = 20.dp, tint = dim) { MediaRepo.custom(a) } }
        PanelButton(Ic.previous, theme, size = 38.dp) { MediaRepo.previous() }
        PanelButton(
            if (MediaRepo.isPlaying) Ic.pause else Ic.play, theme,
            size = 38.dp, iconSize = (theme.controlIconSize + 6).dp,
        ) { MediaRepo.playPause() }
        PanelButton(Ic.next, theme, size = 38.dp) { MediaRepo.next() }
        MediaRepo.repeatAction?.let { a -> PanelButton(Ic.repeat, theme, iconSize = 20.dp, tint = dim) { MediaRepo.custom(a) } }
    }
}
