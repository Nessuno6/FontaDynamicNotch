package com.fonta.dynamicnotch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.Theme
import com.fonta.dynamicnotch.notch.NotchState
import com.fonta.dynamicnotch.notch.rememberNow
import com.fonta.dynamicnotch.services.LiveItem
import com.fonta.dynamicnotch.services.LiveRepo

/** A live item's icon. Notification icons are single-colour shapes, so they are drawn white. */
@Composable
fun LiveIcon(item: LiveItem, size: Dp, modifier: Modifier = Modifier) {
    val bmp = item.icon
    val oneUi = item.oneUi
    if (oneUi != null) {
        Glyph(oneUi, null, modifier.size(size), Color.White)
    } else if (bmp != null) {
        Image(
            remember(bmp) { bmp.asImageBitmap() }, null, modifier.size(size),
            colorFilter = ColorFilter.tint(Color.White),
        )
    } else {
        Glyph(Ic.live, null, modifier.size(size), Color.White)
    }
}

/** The Live tab: every live notification as a card, with its clock, progress and buttons. */
@Composable
fun LiveTab(theme: Theme, compact: Boolean) {
    val items = LiveRepo.items
    val now = rememberNow(500, items.any { it.chronoAt != null })
    if (compact) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { LiveCard(it, theme, now, Modifier.fillMaxWidth()) }
        }
    } else {
        LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.key }) { LiveCard(it, theme, now, Modifier.width(300.dp).fillMaxHeight()) }
        }
    }
}

/** Tap = open the app, press and hold = hide this app's live items. */
@Composable
private fun LiveCard(item: LiveItem, theme: Theme, now: Long, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            // A clickable, not a tap detector: then the buttons inside (remove, the app's own) take their taps first.
            .combinedClickable(
                onClick = { LiveRepo.send(item.contentIntent); NotchState.close() },
                onLongClick = {
                    SettingsStore.update { it.copy(hiddenLivePackages = (it.hiddenLivePackages + item.pkg).distinct()) }
                    LiveRepo.hide(item.pkg)
                },
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(item.color?.let { Color(it).copy(alpha = 1f) } ?: Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { LiveIcon(item, 20.dp) }
            Column(Modifier.weight(1f)) {
                Label(item.title, theme.primaryText, 14.sp, FontWeight.SemiBold)
                Label(item.text.ifBlank { item.appName }, theme.secondaryText, 12.sp)
            }
            val big = item.timeText(now) ?: item.chip
            if (big != null) Label(big, theme.primaryText, if (big.length > 6) 16.sp else 22.sp, FontWeight.SemiBold)
            // Remove it: from the phone where its app allows, from the notch always.
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable { LiveRepo.dismiss(item) },
                contentAlignment = Alignment.Center,
            ) { Glyph(Ic.close, "Remove", Modifier.size(14.dp), theme.primaryText) }
        }
        if (item.progress != null || item.indeterminate) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(theme.sliderTrackColor.color)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(item.progress ?: 1f).clip(RoundedCornerShape(50))
                        .background(theme.sliderFillColor.color.copy(alpha = if (item.progress == null) 0.35f else 1f)),
                )
            }
        }
        if (item.actions.isNotEmpty()) {
            Spacer(Modifier.weight(1f, fill = false))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in item.actions) {
                    PillButton(a.title, theme, Modifier.weight(1f)) { LiveRepo.send(a.intent) }
                }
            }
        }
    }
}
