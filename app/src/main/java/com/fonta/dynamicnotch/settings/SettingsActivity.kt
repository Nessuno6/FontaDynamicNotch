package com.fonta.dynamicnotch.settings

import com.fonta.dynamicnotch.ui.Glyph
import com.fonta.dynamicnotch.ui.Ic
import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fonta.dynamicnotch.data.Alarm
import com.fonta.dynamicnotch.data.AnimationSettings
import com.fonta.dynamicnotch.data.AppSettings
import com.fonta.dynamicnotch.data.CodableColor
import com.fonta.dynamicnotch.data.KnobShape
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.SliderFillStyle
import com.fonta.dynamicnotch.data.SpringSetting
import com.fonta.dynamicnotch.data.Theme
import com.fonta.dynamicnotch.data.ToolsStore
import com.fonta.dynamicnotch.data.TopModule
import com.fonta.dynamicnotch.notch.NotchService
import com.fonta.dynamicnotch.notch.NotchState
import com.fonta.dynamicnotch.services.BentoRepo
import com.fonta.dynamicnotch.services.BrightnessRepo
import com.fonta.dynamicnotch.services.MediaRepo
import com.fonta.dynamicnotch.services.NowBarControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                SettingsScreen()
            }
        }
    }
}

private val tabs = listOf("General", "Appearance", "Layout", "Animations", "Music", "Timers", "Counters", "Advanced")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Permission states are re-read whenever the user comes back from a system settings page.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                resumes++
                BrightnessRepo.refresh()
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Fonta's Dynamic Notch") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val s = SettingsStore.settings
                    when (tab) {
                        0 -> General(s, resumes)
                        1 -> Appearance(s)
                        2 -> Layout(s)
                        3 -> Animations(s)
                        4 -> Music(s)
                        5 -> Timers(s)
                        6 -> Counters()
                        7 -> Advanced(s, resumes)
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    format: (Double) -> String = { "%.2f".format(it) },
    steps: Int = 0,
    onChange: (Double) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(title, Modifier.weight(1f))
            Text(format(value), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.toFloat().coerceIn(range.start, range.endInclusive), { onChange(it.toDouble()) }, valueRange = range, steps = steps)
    }
}

private val dp0: (Double) -> String = { "${it.roundToInt()} dp" }
private val pct: (Double) -> String = { "${(it * 100).roundToInt()}%" }
private val secs: (Double) -> String = { "%.2f s".format(it) }

@Composable
private fun ColorRow(title: String, value: CodableColor, onChange: (CodableColor) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Gray).padding(1.dp).clip(CircleShape).background(Color.Black)) {
            Box(Modifier.fillMaxSize().background(value.color))
        }
    }
    if (open) {
        var c by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton({ onChange(c); open = false }) { Text("Done") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
            title = { Text(title) },
            text = {
                Column {
                    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(MaterialTheme.shapes.medium).background(Color.Black)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp).background(c.color))
                    }
                    SliderRow("Red", c.r, 0f..1f, pct) { c = c.copy(r = it) }
                    SliderRow("Green", c.g, 0f..1f, pct) { c = c.copy(g = it) }
                    SliderRow("Blue", c.b, 0f..1f, pct) { c = c.copy(b = it) }
                    SliderRow("Opacity", c.a, 0f..1f, pct) { c = c.copy(a = it) }
                }
            },
        )
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in options) FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
private fun TextPrompt(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ onDone(text.trim()); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
    )
}

private fun set(f: (AppSettings) -> AppSettings) = SettingsStore.update(f)

// ---------------------------------------------------------------------------------------------
// General
// ---------------------------------------------------------------------------------------------

private fun accessibilityOn(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ComponentName(context, NotchService::class.java) }
}

private fun mediaAccessOn(context: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, button: String = "Allow", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(
            if (granted) Ic.check else Ic.error, null,
            tint = if (granted) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) Button(onClick) { Text(button) }
    }
}

@Composable
private fun PermissionsSection(resumes: Int) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") resumes
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun go(intent: Intent) = runCatching { context.startActivity(intent) }
    Section("Setup") {
        PermissionRow(
            "Show the notch", "Accessibility service: lets the notch sit on top of the status bar. Nothing on screen is read.",
            accessibilityOn(context) || NotchState.serviceRunning, "Turn on",
        ) { go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        PermissionRow(
            "Music controls", "Notification access: the only way Android lets an app see what is playing.",
            mediaAccessOn(context),
        ) { go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        PermissionRow(
            "Brightness slider", "Modify system settings: needed to change the screen brightness.",
            Settings.System.canWrite(context),
        ) { go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        PermissionRow(
            "Timer notifications", "Used when a timer ends while the notch cannot show it.",
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        ) { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

@Composable
private fun General(s: AppSettings, resumes: Int) {
    PermissionsSection(resumes)
    Section("Notch") {
        SwitchRow("Notch on", s.notchEnabled, "Also in Quick Settings: edit the tiles and add \"Dynamic Notch\".") { v -> set { it.copy(notchEnabled = v) } }
        SwitchRow("Show the notch when idle", s.showNotchWhenIdle, "Off: it stays invisible until music plays or a timer runs. Tapping the area still opens it.") { v -> set { it.copy(showNotchWhenIdle = v) } }
        SwitchRow("Hide in full-screen apps", s.hideWhenStatusBarHidden, "The notch disappears whenever the status bar does: full-screen videos, games, and apps that hide it.") { v -> set { it.copy(hideWhenStatusBarHidden = v) } }
        SwitchRow("Live blur behind the notch", s.liveBlur, if (NotchState.glassAvailable || !NotchState.serviceRunning) "Frosted glass over whatever is on screen." else "This device does not let apps blur other windows.") { v -> set { it.copy(liveBlur = v) } }
        if (s.liveBlur) SwitchRow("Blur the closed notch too", s.blurWhenClosed, "Off: the closed notch is solid black and blends with the camera.") { v -> set { it.copy(blurWhenClosed = v) } }
        if (s.liveBlur) SliderRow("Glass tint", s.glassTint, 0.1f..0.9f, pct) { v -> set { it.copy(glassTint = v) } }
        SliderRow("Paused music stays visible", s.pausedMusicVisibleMinutes, 1f..60f, { "${it.roundToInt()} min" }) { v -> set { it.copy(pausedMusicVisibleMinutes = v.roundToInt().toDouble()) } }
    }
    Section("Border and corners") {
        SliderRow("Border width", s.borderWidth, 0f..4f, { if (it < 0.05) "Off" else "%.1f dp".format(it) }) { v -> set { it.copy(borderWidth = v) } }
        if (s.borderWidth >= 0.05) ColorRow("Border color", s.borderColor) { c -> set { it.copy(borderColor = c) } }
        SwitchRow(
            "Match the screen's corners", s.matchDeviceCorners,
            "The notch's corners follow this device's screen corners (${(NotchState.geometry.cornerPx / NotchState.geometry.density.coerceAtLeast(0.1f)).roundToInt()} dp here). Off: the theme's corner radii are used.",
        ) { v -> set { it.copy(matchDeviceCorners = v) } }
    }
    Section("Live notifications") {
        SwitchRow("Show all live notifications in the notch", s.showLive, "Now Bar items, Live Updates, calls, timers, downloads. Uses the same notification access as the music controls.") { v -> set { it.copy(showLive = v) } }
        val context = LocalContext.current
        var nowBarError by remember { mutableStateOf<String?>(null) }
        SwitchRow("Hide Now Bar", s.hideNowBar, "Turns off Samsung's Now Bar on the lock screen and its chip in the status bar, so nothing shows twice.") { v ->
            NowBarControl.apply(context, hide = v) { problem ->
                nowBarError = problem
                // Keep the switch on even when only the chip could be hidden; off if nothing could.
                if (problem == null || problem != NowBarControl.HELP) set { it.copy(hideNowBar = v) }
            }
        }
        nowBarError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp)) }
        if (s.showLive) {
            SwitchRow("Include ordinary ongoing notifications", s.liveIncludeOngoing, "Anything ongoing with a clock or a progress bar, not only Now Bar items.") { v -> set { it.copy(liveIncludeOngoing = v) } }
            for (pkg in s.hiddenLivePackages) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pkg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ set { it.copy(hiddenLivePackages = it.hiddenLivePackages - pkg) } }) { Text("Show again") }
                }
            }
            Text(
                "Press and hold a live item in the notch to hide that app. Changes apply to notifications posted from now on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
    Section("BentoBar") {
        val context = LocalContext.current
        @Suppress("UNUSED_EXPRESSION") resumes
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) BentoRepo.set(context, SettingsStore.settings.bentoInNotch)
        }
        SwitchRow("Show BentoBar in the notch", s.bentoInNotch, "A tab with everything BentoBar's bar shows, and chosen items as small tiles beside the closed notch.") { v ->
            set { it.copy(bentoInNotch = v) }
            if (v && !BentoRepo.granted(context)) ask.launch(BentoRepo.PERMISSION) else BentoRepo.set(context, v)
        }
        if (s.bentoInNotch) {
            SwitchRow("Hide BentoBar's own bar", s.hideBentoBar, "BentoBar lives in the notch: its bar in the status bar is hidden, ‹ included. Its items are in the notch's BentoBar tab.") { v -> set { it.copy(hideBentoBar = v) } }
            val problem = when (BentoRepo.status) {
                BentoRepo.Status.notInstalled -> "BentoBar is not installed, or its version does not share its items yet."
                BentoRepo.Status.needsPermission -> "Android has not allowed the notch to read BentoBar's items yet."
                BentoRepo.Status.sharingOff -> "In BentoBar, turn on Look › Other apps › Share the bar's items with other apps."
                else -> null
            }
            if (problem != null) {
                Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
                if (BentoRepo.status == BentoRepo.Status.needsPermission) TextButton({ ask.launch(BentoRepo.PERMISSION) }) { Text("Allow") }
                TextButton({ BentoRepo.refresh() }) { Text("Check again") }
            } else {
                Text(
                    "Small tiles beside the closed notch. Tapping one opens its BentoBar menu. In each list the first tile is the one nearest the notch.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
                val (left, right) = s.bentoSides
                fun save(l: List<String>, r: List<String>) = set { it.copy(bentoLeft = l, bentoRight = r, bentoWingItems = emptyList()) }
                BentoTileList("Left of the notch", left, onChange = { save(it, right) }, otherSide = "Move to the right", onOther = { id -> save(left - id, right + id) })
                BentoTileList("Right of the notch", right, onChange = { save(left, it) }, otherSide = "Move to the left", onOther = { id -> save(left + id, right - id) })
                val unused = BentoRepo.items.filter { it.id !in left && it.id !in right }
                if (unused.isNotEmpty()) Text("Add a tile", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                for (item in unused) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title)
                            Text(item.words, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        TextButton({ save(left + item.id, right) }) { Text("Left") }
                        TextButton({ save(left, right + item.id) }) { Text("Right") }
                    }
                }
                if (BentoRepo.items.isEmpty()) Text("BentoBar's bar is not showing anything right now.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
    Section("Mouse and S Pen hover") {
        SliderRow("Open delay", s.hoverOpenDelay, 0f..1f, secs) { v -> set { it.copy(hoverOpenDelay = v) } }
        SliderRow("Close delay", s.closeDelay, 0f..1f, secs) { v -> set { it.copy(closeDelay = v) } }
        Text(
            "With a finger: tap the notch to open it, tap anywhere else to close it. Press and hold the open notch to come back to these settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/** One side's BentoBar tiles: reorder, send to the other side, or remove. */
@Composable
private fun BentoTileList(title: String, ids: List<String>, onChange: (List<String>) -> Unit, otherSide: String, onOther: (String) -> Unit) {
    Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
    if (ids.isEmpty()) Text("No tiles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ids.forEachIndexed { i, id ->
        val item = BentoRepo.items.firstOrNull { it.id == id }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item?.title ?: "Item $id")
                Text(item?.words ?: "not in BentoBar's bar right now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton({ onChange(ids.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Glyph(Ic.up, "Nearer the notch") }
            IconButton({ onChange(ids.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < ids.lastIndex) { Glyph(Ic.down, "Further from the notch") }
            TextButton({ onOther(id) }) { Text(otherSide.removePrefix("Move to the ").replaceFirstChar(Char::uppercase) + " side") }
            IconButton({ onChange(ids - id) }) { Glyph(Ic.delete, "Remove") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Appearance
// ---------------------------------------------------------------------------------------------

@Composable
private fun Appearance(s: AppSettings) {
    val context = LocalContext.current
    val theme = SettingsStore.theme
    var rename by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(SettingsStore.exportTheme(SettingsStore.theme).toByteArray()) }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { SettingsStore.importTheme(it.readBytes().decodeToString()) }
        }
    }
    Section("Themes") {
        for (t in SettingsStore.themes) {
            Row(Modifier.fillMaxWidth().clickable { set { it.copy(selectedThemeID = t.id) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(t.id == theme.id, { set { it.copy(selectedThemeID = t.id) } })
                Text(t.name, Modifier.weight(1f))
                if (t.isBuiltIn) Text("built in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ SettingsStore.duplicate(theme, if (theme.isBuiltIn) "My theme" else theme.name + " copy") }) { Text("Duplicate") }
            OutlinedButton({ import.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Import") }
            OutlinedButton({ export.launch(theme.name.replace(' ', '-') + ".json") }) { Text("Export") }
        }
        if (!theme.isBuiltIn) {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ rename = true }) { Text("Rename") }
                OutlinedButton({ SettingsStore.deleteTheme(theme.id) }) { Text("Delete") }
            }
        }
    }
    if (rename) TextPrompt("Rename theme", theme.name, { rename = false }) { n -> if (n.isNotBlank()) SettingsStore.saveTheme(theme.copy(name = n)) }

    if (theme.isBuiltIn) {
        Text(
            "The Default theme is read-only. Tap Duplicate to make an editable copy.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
        )
        return
    }
    fun edit(f: (Theme) -> Theme) = SettingsStore.saveTheme(f(SettingsStore.theme))

    Section("Colors") {
        ColorRow("Background (no artwork)", theme.fallbackBackgroundColor) { c -> edit { it.copy(fallbackBackgroundColor = c) } }
        ColorRow("Primary text", theme.primaryTextColor) { c -> edit { it.copy(primaryTextColor = c) } }
        ColorRow("Secondary text", theme.secondaryTextColor) { c -> edit { it.copy(secondaryTextColor = c) } }
        ColorRow("Accent", theme.accentColor) { c -> edit { it.copy(accentColor = c) } }
        ColorRow("Icons", theme.iconTint) { c -> edit { it.copy(iconTint = c) } }
        ColorRow("Slider track", theme.sliderTrackColor) { c -> edit { it.copy(sliderTrackColor = c) } }
        ColorRow("Slider fill", theme.sliderFillColor) { c -> edit { it.copy(sliderFillColor = c) } }
        ColorRow("Slider knob", theme.sliderKnobColor) { c -> edit { it.copy(sliderKnobColor = c) } }
        SwitchRow("Background follows the album cover", theme.useArtworkColor) { v -> edit { it.copy(useArtworkColor = v) } }
        SliderRow("Artwork color intensity", theme.artworkIntensity, 0f..1f, pct) { v -> edit { it.copy(artworkIntensity = v) } }
        SliderRow("Max background brightness", theme.maxBackgroundLuminance, 0.02f..0.4f) { v -> edit { it.copy(maxBackgroundLuminance = v) } }
    }
    Section("Corner rounding") {
        SliderRow("Top fillets", theme.topFilletRadius, 0f..24f, dp0) { v -> edit { it.copy(topFilletRadius = v) } }
        SliderRow("Open: bottom corners", theme.openBottomRadius, 0f..48f, dp0) { v -> edit { it.copy(openBottomRadius = v) } }
        SliderRow("Closed: bottom corners", theme.closedBottomRadius, 0f..24f, dp0) { v -> edit { it.copy(closedBottomRadius = v) } }
        SliderRow("Cover (open)", theme.coverRadiusOpen, 0f..40f, dp0) { v -> edit { it.copy(coverRadiusOpen = v) } }
        SliderRow("Cover (closed)", theme.coverRadiusClosed, 0f..16f, dp0) { v -> edit { it.copy(coverRadiusClosed = v) } }
        SliderRow("Buttons", theme.buttonRadius, 0f..20f, dp0) { v -> edit { it.copy(buttonRadius = v) } }
    }
    Section("Shadow") {
        SwitchRow("Shadow under the open notch", theme.shadowEnabled) { v -> edit { it.copy(shadowEnabled = v) } }
        ColorRow("Color", theme.shadowTint) { c -> edit { it.copy(shadowTint = c) } }
        SliderRow("Opacity", theme.shadowOpacity, 0f..1f, pct) { v -> edit { it.copy(shadowOpacity = v) } }
        SliderRow("Blur", theme.shadowRadius, 0f..40f, dp0) { v -> edit { it.copy(shadowRadius = v) } }
        SliderRow("Vertical offset", theme.shadowYOffset, 0f..20f, dp0) { v -> edit { it.copy(shadowYOffset = v) } }
    }
    Section("Icons and sliders") {
        SliderRow("Top bar icon size", theme.topBarIconSize, 12f..24f, dp0) { v -> edit { it.copy(topBarIconSize = v) } }
        SliderRow("Control icon size", theme.controlIconSize, 16f..32f, dp0) { v -> edit { it.copy(controlIconSize = v) } }
        SliderRow("Slider height", theme.sliderHeight, 2f..12f, dp0) { v -> edit { it.copy(sliderHeight = v) } }
        SliderRow("Knob size", theme.knobSize, 6f..22f, dp0) { v -> edit { it.copy(knobSize = v) } }
        ChoiceRow("Knob shape", KnobShape.entries, theme.knobShape, { it.name.replaceFirstChar(Char::uppercase) }) { v -> edit { it.copy(knobShape = v) } }
        SwitchRow("Show the knob only while dragging", theme.knobOnlyWhileDragging) { v -> edit { it.copy(knobOnlyWhileDragging = v) } }
        ChoiceRow("Slider fill", SliderFillStyle.entries, theme.sliderFillStyle, { if (it == SliderFillStyle.solid) "Solid" else "Artwork color" }) { v -> edit { it.copy(sliderFillStyle = v) } }
    }
}

// ---------------------------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------------------------

@Composable
private fun ModuleList(title: String, list: List<TopModule>, s: AppSettings, save: (List<TopModule>) -> Unit) {
    Section(title) {
        list.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, Modifier.weight(1f))
                IconButton({ save(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Glyph(Ic.up, "Move up") }
                IconButton({ save(list.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < list.lastIndex) { Glyph(Ic.down, "Move down") }
                Switch(s.isVisible(m), { on -> set { it.copy(hiddenModules = if (on) it.hiddenModules - m else it.hiddenModules + m) } })
            }
        }
    }
}

@Composable
private fun Layout(s: AppSettings) {
    ModuleList("Left of the notch", AppSettings.cleaned(s.leftModules, TopModule.defaultLeft), s) { l -> set { it.copy(leftModules = l) } }
    ModuleList("Right of the notch", AppSettings.cleaned(s.rightModules, TopModule.defaultRight), s) { l -> set { it.copy(rightModules = l) } }
    Section("Sizes") {
        SliderRow("Notch width (phones use 60%)", s.notchWidth, 60f..220f, dp0) { v -> set { it.copy(notchWidth = v) } }
        SliderRow("Extra notch height", s.closedHeightExtra, 0f..16f, dp0) { v -> set { it.copy(closedHeightExtra = v) } }
        SliderRow("Closed wing width", s.closedWingWidth, 28f..64f, dp0) { v -> set { it.copy(closedWingWidth = v) } }
        SliderRow("Open width (wide screens)", s.openWidth, 520f..900f, dp0) { v -> set { it.copy(openWidth = v) } }
        SliderRow("Open height (wide screens)", s.openHeight, 190f..320f, dp0) { v -> set { it.copy(openHeight = v) } }
        Text(
            "Screens narrower than the open width (phones in portrait) use a stacked layout that fills the width.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Animations
// ---------------------------------------------------------------------------------------------

@Composable
private fun SpringEditor(title: String, value: SpringSetting, default: SpringSetting, onChange: (SpringSetting) -> Unit) {
    Section(title) {
        SliderRow("Response", value.response, 0.1f..1.2f, secs) { onChange(value.copy(response = it)) }
        SliderRow("Damping", value.dampingFraction, 0.3f..1.2f) { onChange(value.copy(dampingFraction = it)) }
        TextButton({ onChange(default) }, enabled = value != default) { Text("Reset to default") }
    }
}

@Composable
private fun Animations(s: AppSettings) {
    val a = s.animations
    val d = AnimationSettings()
    val scope = rememberCoroutineScope()
    fun anim(f: (AnimationSettings) -> AnimationSettings) = set { it.copy(animations = f(it.animations)) }
    Button({
        scope.launch {
            NotchState.open()
            delay(1400)
            NotchState.close()
        }
    }, enabled = NotchState.serviceRunning) { Text("Preview open and close") }
    SpringEditor("Open", a.open, d.open) { v -> anim { it.copy(open = v) } }
    SpringEditor("Close", a.close, d.close) { v -> anim { it.copy(close = v) } }
    SpringEditor("Closed wings appear / disappear", a.wings, d.wings) { v -> anim { it.copy(wings = v) } }
    SpringEditor("Brightness slider expand / collapse", a.brightness, d.brightness) { v -> anim { it.copy(brightness = v) } }
    SpringEditor("Tab and tool switch", a.tabSwitch, d.tabSwitch) { v -> anim { it.copy(tabSwitch = v) } }
    SpringEditor("Alarm auto-open", a.alarmOpen, d.alarmOpen) { v -> anim { it.copy(alarmOpen = v) } }
}

// ---------------------------------------------------------------------------------------------
// Music
// ---------------------------------------------------------------------------------------------

@Composable
private fun Music(s: AppSettings) {
    val context = LocalContext.current
    Section("Now playing") {
        Text(
            if (MediaRepo.hasAccess) "Connected. The notch follows whichever app is playing (Spotify, YouTube Music, …)."
            else "Music controls are off. Allow notification access in General › Setup.",
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (!MediaRepo.hasAccess && mediaAccessOn(context)) {
            TextButton({ MediaRepo.attach(context) }) { Text("Reconnect") }
        }
        SwitchRow("Scroll long titles", s.marqueeEnabled) { v -> set { it.copy(marqueeEnabled = v) } }
    }
    Section("Music bars") {
        SwitchRow("One UI 9 waves", s.oneUiWaves, "A moving wave instead of the bars beside the closed notch, and a wavy progress bar while music plays, as on One UI 9.") { v -> set { it.copy(oneUiWaves = v) } }
        SliderRow("Number of bars", s.musicBarCount.toDouble(), 3f..8f, { "${it.roundToInt()}" }, steps = 4) { v -> set { it.copy(musicBarCount = v.roundToInt()) } }
        SliderRow("Speed", s.musicBarSpeed, 0.3f..3f, { "%.1f×".format(it) }) { v -> set { it.copy(musicBarSpeed = v) } }
        SwitchRow("Use the album cover's color", s.musicBarsUseArtworkColor) { v -> set { it.copy(musicBarsUseArtworkColor = v) } }
    }
}

// ---------------------------------------------------------------------------------------------
// Timers
// ---------------------------------------------------------------------------------------------

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    OutlinedTextField(
        if (value == 0) "" else value.toString(),
        { t -> onChange(t.filter(Char::isDigit).take(2).toIntOrNull() ?: 0) },
        modifier, label = { Text(label) }, singleLine = true, placeholder = { Text("0") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun Timers(s: AppSettings) {
    val context = LocalContext.current
    Section("Presets") {
        for (t in ToolsStore.state.timers) {
            val total = t.duration.toInt()
            fun save(name: String = t.name, h: Int = total / 3600, m: Int = total % 3600 / 60, sec: Int = total % 60) =
                ToolsStore.editPreset(t.id, name, (h * 3600 + m * 60 + sec).toDouble())
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(t.name, { save(name = it) }, Modifier.fillMaxWidth(), label = { Text("Timer ${t.id + 1}") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Hours", total / 3600, Modifier.weight(1f)) { save(h = it) }
                    NumberField("Minutes", total % 3600 / 60, Modifier.weight(1f)) { save(m = it.coerceAtMost(59)) }
                    NumberField("Seconds", total % 60, Modifier.weight(1f)) { save(sec = it.coerceAtMost(59)) }
                }
            }
        }
        Text("Editing a preset resets that timer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        if (r.resultCode == android.app.Activity.RESULT_OK) set { it.copy(alarmSoundUri = uri?.toString() ?: "") }
    }
    Section("Alarm") {
        val name = remember(s.alarmSoundUri) {
            runCatching { RingtoneManager.getRingtone(context, Alarm.soundUri())?.getTitle(context) }.getOrNull() ?: "Default alarm"
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Sound")
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton({
                pick.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alarm.soundUri()),
                )
            }) { Text("Choose") }
            OutlinedButton({ Alarm.play(context, loop = false) }) { Text("Preview") }
        }
        SliderRow("Alarm volume", s.alarmVolume, 0.1f..1f, pct) { v -> set { it.copy(alarmVolume = v) } }
    }
}

// ---------------------------------------------------------------------------------------------
// Counters
// ---------------------------------------------------------------------------------------------

@Composable
private fun Counters() {
    var renaming by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val counters = ToolsStore.state.counters
    Section("Counters") {
        counters.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { renaming = c.id }.padding(vertical = 8.dp)) {
                    Text(c.name)
                    Text("Value ${c.value} · tap to rename", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ ToolsStore.moveCounter(i, -1) }, enabled = i > 0) { Glyph(Ic.up, "Move up") }
                IconButton({ ToolsStore.moveCounter(i, 1) }, enabled = i < counters.lastIndex) { Glyph(Ic.down, "Move down") }
                IconButton({ ToolsStore.deleteCounter(c.id) }, enabled = counters.size > 1) { Glyph(Ic.delete, "Delete") }
            }
        }
        TextButton({ adding = true }) {
            Glyph(Ic.add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add counter")
        }
    }
    renaming?.let { id ->
        TextPrompt("Rename counter", counters.firstOrNull { it.id == id }?.name ?: "", { renaming = null }) { n -> if (n.isNotBlank()) ToolsStore.renameCounter(id, n) }
    }
    if (adding) TextPrompt("New counter", "", { adding = false }) { n -> ToolsStore.addCounter(n) }
}

// ---------------------------------------------------------------------------------------------
// Advanced
// ---------------------------------------------------------------------------------------------

@Composable
private fun Advanced(s: AppSettings, resumes: Int) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") resumes
    val g = NotchState.geometry
    Section("What works on this device") {
        @Composable fun line(label: String, ok: Boolean) = Row(Modifier.padding(vertical = 4.dp)) {
            Text(label, Modifier.weight(1f))
            Text(if (ok) "✓" else "✗", color = if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        line("Notch overlay running", NotchState.serviceRunning)
        line("Music controls", MediaRepo.hasAccess)
        line("Screen brightness", BrightnessRepo.available)
        line("Notifications", context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        line("Camera cutout at the top", g.hasRealNotch)
        Text(
            "Keyboard backlight, picking the audio output inside the notch and AirDrop exist only on the Mac. Here the output button opens Android's own picker, and the shelf shares through the Android share sheet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp),
        )
    }
    Section("Debug") {
        SwitchRow("Show geometry outlines", s.showDebugOverlay, "Yellow: notch · green: closed shape · cyan: open shape") { v -> set { it.copy(showDebugOverlay = v) } }
        if (NotchState.serviceRunning) {
            Text(
                "Screen ${g.screenW}×${g.screenH} px · notch ${(g.notchW / g.density).roundToInt()}×${(g.notchH / g.density).roundToInt()} dp · ${if (g.compact) "stacked" else "wide"} layout",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton({ SettingsStore.resetAll() }) { Text("Reset all settings") }
    }
}
