package com.fonta.dynamicnotch.ui

import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fonta.dynamicnotch.data.AppSettings
import com.fonta.dynamicnotch.data.Theme
import com.fonta.dynamicnotch.data.TimeFormat
import com.fonta.dynamicnotch.data.Tool
import com.fonta.dynamicnotch.data.ToolsStore
import com.fonta.dynamicnotch.notch.NotchState
import com.fonta.dynamicnotch.notch.rememberNow
import com.fonta.dynamicnotch.services.ShelfItem
import com.fonta.dynamicnotch.services.ShelfStore

/** The right-hand (or, on phones, bottom) panel: timer, stopwatch or counter. */
@Composable
fun ToolPanel(theme: Theme, settings: AppSettings, tools: List<Tool>, modifier: Modifier) {
    val tool = currentTool(settings) ?: return
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(10.dp)) {
        Crossfade(tool, animationSpec = settings.animations.tabSwitch.spec(), label = "tool") { t ->
            when (t) {
                Tool.timer -> TimerPanel(theme)
                Tool.stopwatch -> StopwatchPanel(theme)
                Tool.counter -> CounterPanel(theme)
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") tools
}

@Composable
private fun TimerPanel(theme: Theme) {
    val st = ToolsStore.state
    val selected = st.timers[st.selectedTimer.coerceIn(0, st.timers.lastIndex)]
    val now = rememberNow(200, st.timers.any { it.isRunning })
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        // Five chips; a dot marks the ones that are running.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            st.timers.forEachIndexed { i, t ->
                val isSel = i == st.selectedTimer
                Box(
                    Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(7.dp))
                        .background(Color.White.copy(alpha = if (isSel) 0.24f else 0.08f))
                        .clickable { ToolsStore.selectTimer(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Label(
                        t.name.take(5), if (isSel) theme.primaryText else theme.secondaryText, 9.sp,
                        if (isSel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 2.dp),
                    )
                    if (t.isRunning || t.isPaused) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(2.dp).size(5.dp).clip(CircleShape)
                                .background(if (t.isRunning) Color(0xFF4CD964) else Color(0xFFFFCC00)),
                        )
                    }
                }
            }
        }
        Label(selected.name, theme.secondaryText, 11.sp)
        Label(TimeFormat.countdown(selected.remaining(now)), theme.primaryText, 30.sp, FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton(
                when { selected.isRunning -> "Pause"; selected.isPaused -> "Resume"; else -> "Start" },
                theme, Modifier.weight(1f), prominent = !selected.isRunning,
            ) { ToolsStore.toggleTimer(selected.id) }
            PillButton("Reset", theme, Modifier.weight(1f)) { ToolsStore.resetTimer(selected.id) }
        }
    }
}

@Composable
private fun StopwatchPanel(theme: Theme) {
    val sw = ToolsStore.state.stopwatch
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showLaps by remember { mutableStateOf(false) }
    // ~30 Hz, only while this panel is on screen and the stopwatch runs.
    LaunchedEffect(sw.isRunning) {
        now = System.currentTimeMillis()
        var last = 0L
        while (sw.isRunning) {
            withFrameMillis { t ->
                if (t - last >= 33) { last = t; now = System.currentTimeMillis() }
            }
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        if (showLaps && sw.laps.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().clickable { showLaps = false }) {
                itemsIndexed(sw.laps.reversed()) { i, lap ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label("Lap ${sw.laps.size - i}", theme.secondaryText, 11.sp)
                        Label(TimeFormat.stopwatch(lap), theme.primaryText, 11.sp)
                    }
                }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().clickable(enabled = sw.laps.isNotEmpty()) { showLaps = true },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Label(TimeFormat.stopwatch(sw.elapsed(now)), theme.primaryText, 28.sp, FontWeight.SemiBold)
                Label(
                    sw.laps.lastOrNull()?.let { "Lap ${sw.laps.size}  ${TimeFormat.stopwatch(it)}" } ?: "Stopwatch",
                    theme.secondaryText, 11.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val idle = !sw.isRunning && sw.accumulated == 0.0
            PillButton(
                when { sw.isRunning -> "Pause"; idle -> "Start"; else -> "Resume" },
                theme, Modifier.weight(1.3f), prominent = !sw.isRunning,
            ) { ToolsStore.toggleStopwatch() }
            PillButton("Lap", theme, Modifier.weight(1f)) { ToolsStore.lap() }
            PillButton("Reset", theme, Modifier.weight(1f)) { ToolsStore.resetStopwatch(); showLaps = false }
        }
    }
}

@Composable
private fun CounterPanel(theme: Theme) {
    val c = ToolsStore.currentCounter
    val many = ToolsStore.state.counters.size > 1
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PanelButton(Ic.left, theme, size = 26.dp, iconSize = 20.dp, enabled = many) { ToolsStore.stepCounterSelection(-1) }
            Label(c.name, theme.secondaryText, 12.sp, modifier = Modifier.weight(1f), align = TextAlign.Center)
            PanelButton(Ic.right, theme, size = 26.dp, iconSize = 20.dp, enabled = many) { ToolsStore.stepCounterSelection(1) }
        }
        Label(c.value.toString(), theme.primaryText, 32.sp, FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton("−1", theme, Modifier.weight(1f)) { ToolsStore.changeCounter(-1) }
            PillButton("+1", theme, Modifier.weight(1f), prominent = true) { ToolsStore.changeCounter(1) }
            PillButton("Reset", theme, Modifier.weight(1f)) { ToolsStore.resetCounter() }
        }
    }
}

/** Shown while a timer rings: the names and one big Stop that dismisses them all. */
@Composable
fun AlarmView(theme: Theme) {
    val ringing = ToolsStore.ringingTimers
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(Ic.alarm, null, tint = theme.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        for (t in ringing.take(4)) Label(t.name, theme.primaryText, 16.sp, FontWeight.SemiBold)
        Label(if (ringing.size > 1) "${ringing.size} timers finished" else "Timer finished", theme.secondaryText, 12.sp)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.width(200.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFFF453A))
                .clickable { ToolsStore.stopAlarm() },
            contentAlignment = Alignment.Center,
        ) { Label("Stop", Color.White, 17.sp, FontWeight.Bold) }
    }
}

// ---------------------------------------------------------------------------------------------
// Files tab: a temporary shelf. Files arrive through the share sheet ("Notch shelf").
// ---------------------------------------------------------------------------------------------

@Composable
fun FilesTab(theme: Theme, compact: Boolean) {
    val items = ShelfStore.items
    val picked = ShelfStore.selected.size
    val shelf: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            if (items.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                    Glyph(Ic.document, null, tint = theme.secondaryText, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(6.dp))
                    Label("The shelf is empty", theme.primaryText, 13.sp, FontWeight.SemiBold)
                    Label("Drag files onto the notch, or share them to “Notch shelf”", theme.secondaryText, 11.sp)
                }
            } else {
                LazyRow(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(items, key = { it.id }) { ShelfTile(it, theme) }
                }
            }
        }
    }
    val zones: @Composable (Modifier) -> Unit = { m ->
        DropZone(Ic.quickShare, if (picked > 0) "Share $picked" else "Quick Share", theme, items.isNotEmpty(), m) {
            ShelfStore.shareTargets()
            NotchState.close()
        }
        DropZone(Ic.delete, if (picked > 0) "Remove $picked" else "Clear all", theme, items.isNotEmpty(), m) {
            ShelfStore.removeTargets()
        }
    }
    if (compact) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shelf(Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                zones(Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            shelf(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(104.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                zones(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DropZone(icon: Int, label: String, theme: Theme, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val a = if (enabled) 1f else 0.4f
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Glyph(icon, null, tint = theme.iconColor.copy(alpha = a), modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Label(label, theme.primaryText.copy(alpha = a), 11.sp, FontWeight.Medium)
    }
}

/** Tap = select, double-tap = open, long-press and drag = drag the file out to another app. */
@Composable
private fun ShelfTile(item: ShelfItem, theme: Theme) {
    val selected = item.id in ShelfStore.selected
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) theme.accent.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(10.dp))
            .dragAndDropSource { _ ->
                // Close so the drop lands on the app underneath, not on the notch window.
                ShelfStore.draggingOut = true
                NotchState.close()
                DragAndDropTransferData(
                    ShelfStore.dragClip(item),
                    flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ,
                )
            }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { ShelfStore.toggleSelected(item.id) },
                    onDoubleTap = { ShelfStore.open(item); NotchState.close() },
                )
            }
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            if (item.thumb != null) {
                Image(remember(item.id) { item.thumb.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Glyph(Ic.document, null, tint = theme.iconColor.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Label(item.name, theme.primaryText, 10.sp)
    }
}
