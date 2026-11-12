package com.fonta.dynamicnotch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.fonta.dynamicnotch.data.SettingsStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fonta.dynamicnotch.data.Theme
import com.fonta.dynamicnotch.notch.NotchState
import com.fonta.dynamicnotch.services.BentoItem
import com.fonta.dynamicnotch.services.BentoRepo

/** An item's colour by BentoBar's tone: its warnings stay amber and red in the notch too. */
fun bentoColor(item: BentoItem, theme: Theme): Color = when (item.tone) {
    "alert" -> Color(0xFFFF6B60)
    "warn" -> Color(0xFFFFC53D)
    "accent" -> theme.accent
    else -> theme.primaryText
}

/** A BentoBar item's icon. Symbols come white and take the tone's colour; an app's own icon keeps its colours. */
@Composable
fun BentoIcon(item: BentoItem, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val bmp = item.icon
    val oneUi = Ic.forBento(item.type)
    if (oneUi != null) {
        Glyph(oneUi, null, modifier.size(size), tint)
    } else if (bmp != null) {
        val appIcon = item.type == "app" || item.type == "shortcut" || item.type == "folder"
        Image(
            remember(bmp) { bmp.asImageBitmap() }, null, modifier.size(size),
            colorFilter = if (appIcon) null else ColorFilter.tint(tint),
        )
    } else {
        Glyph(Ic.bento, null, modifier.size(size), tint)
    }
}

/** Puts an item beside the closed notch on one side (at its outer end), or takes it away ([left] null). */
fun pinBento(id: String, left: Boolean?) {
    SettingsStore.update { cur ->
        val (l, r) = cur.bentoSides
        val nl = l - id
        val nr = r - id
        when (left) {
            true -> cur.copy(bentoLeft = nl + id, bentoRight = nr, bentoWingItems = emptyList())
            false -> cur.copy(bentoLeft = nl, bentoRight = nr + id, bentoWingItems = emptyList())
            null -> cur.copy(bentoLeft = nl, bentoRight = nr, bentoWingItems = emptyList())
        }
    }
}

/**
 * The BentoBar tab: everything BentoBar's bar shows now, as tiles. A tap opens the item's own
 * BentoBar menu under it; press and hold to put it beside the closed notch, left or right.
 */
@Composable
fun BentoTab(theme: Theme, compact: Boolean) {
    val items = BentoRepo.items
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Label(
                when (BentoRepo.status) {
                    BentoRepo.Status.notInstalled -> "BentoBar is not installed"
                    BentoRepo.Status.needsPermission -> "Allow the notch to read BentoBar's items in its Settings"
                    BentoRepo.Status.sharingOff -> "Turn on BentoBar › Look › Share the bar's items"
                    else -> "BentoBar's bar is not showing anything right now"
                },
                theme.secondaryText, 13.sp,
            )
        }
        return
    }
    val (pinnedL, pinnedR) = SettingsStore.settings.bentoSides
    var editing by remember { mutableStateOf<String?>(null) }
    LazyVerticalGrid(
        GridCells.Adaptive(if (compact) 150.dp else 170.dp), Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Label("Tap for its BentoBar menu · hold to put it beside the notch", theme.secondaryText, 11.sp)
        }
        items(items, key = { it.id }) { item ->
            val color = bentoColor(item, theme)
            val where = when (item.id) { in pinnedL -> "Left"; in pinnedR -> "Right"; else -> null }
            var rect by remember { mutableStateOf(android.graphics.Rect()) }
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (item.active || editing == item.id) 0.16f else 0.08f))
                    .onGloballyPositioned { c ->
                        val p = c.positionInWindow()
                        rect = android.graphics.Rect(p.x.toInt(), p.y.toInt(), (p.x + c.size.width).toInt(), (p.y + c.size.height).toInt())
                    }
                    .pointerInput(item.id) {
                        detectTapGestures(
                            onTap = { if (editing == item.id) editing = null else BentoRepo.open(item, rect) },
                            onLongPress = { editing = item.id },
                        )
                    },
            ) {
                if (editing == item.id) {
                    // Where it goes: either side of the closed notch, or nowhere.
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        IconPill(Ic.arrowLeft, "Left", theme, Modifier.weight(1f), prominent = where == "Left") { pinBento(item.id, true); editing = null }
                        IconPill(Ic.arrowRight, "Right", theme, Modifier.weight(1f), prominent = where == "Right", iconAfter = true) { pinBento(item.id, false); editing = null }
                        if (where != null) IconPill(Ic.close, null, theme, Modifier.width(36.dp)) { pinBento(item.id, null); editing = null }
                    }
                } else {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        BentoIcon(item, 22.dp, color)
                        Column(Modifier.weight(1f)) {
                            Label(item.words, color, 13.sp, FontWeight.SemiBold)
                            if (item.words != item.title) Label(item.title, theme.secondaryText, 10.sp)
                        }
                        when {
                            where != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (where == "Left") Glyph(Ic.arrowLeft, null, Modifier.size(11.dp), theme.secondaryText)
                                Label("notch", theme.secondaryText, 10.sp)
                                if (where == "Right") Glyph(Ic.arrowRight, null, Modifier.size(11.dp), theme.secondaryText)
                            }
                            !item.inBar -> Label("hidden", theme.secondaryText, 10.sp)
                        }
                    }
                }
            }
        }
    }
}

/** One BentoBar item beside the closed notch: its icon and its live value, in its tone's colour. */
@Composable
fun BentoTile(item: BentoItem, theme: Theme, modifier: Modifier) {
    val color = bentoColor(item, theme)
    Row(
        modifier.padding(3.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = if (item.active) 0.20f else 0.10f))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        BentoIcon(item, 16.dp, color)
        item.text?.takeIf { it.isNotBlank() }?.let {
            androidx.compose.material3.Text(
                it, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}
