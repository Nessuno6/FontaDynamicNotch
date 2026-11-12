package com.fonta.dynamicnotch.notch

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.TimeFormat
import com.fonta.dynamicnotch.data.ToolsStore
import com.fonta.dynamicnotch.services.BentoItem
import com.fonta.dynamicnotch.services.BentoRepo
import com.fonta.dynamicnotch.services.LiveItem
import com.fonta.dynamicnotch.services.LiveRepo
import com.fonta.dynamicnotch.services.MediaRepo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One thing beside the closed notch. */
sealed interface WingPart {
    val key: String

    /** The album cover's place (the cover itself is drawn by NotchRoot, so it can grow into the open panel). */
    data object Cover : WingPart { override val key = "cover" }
    data object Bars : WingPart { override val key = "bars" }
    /** The notch's own running timer or stopwatch: its time. */
    data object Time : WingPart { override val key = "time" }
    /** The timer's icon on the other side, when the time is split across the notch. */
    data object TimeIcon : WingPart { override val key = "time-icon" }
    /** A live item whole: its icon and its short text. */
    data class Live(val item: LiveItem) : WingPart { override val key = "live:" + item.key }
    /** A live item split across the notch: its icon on one side… */
    data class LiveIcon(val item: LiveItem) : WingPart { override val key = "live-icon:" + item.key }
    /** …and its text on the other. */
    data class LiveText(val item: LiveItem) : WingPart { override val key = "live-text:" + item.key }
    data class Tile(val item: BentoItem) : WingPart { override val key = "tile:" + item.id }
}

/** A part with its place: [x] is its left edge on screen, [w] its width. */
data class Placed(val part: WingPart, val x: Float, val w: Float)

/** Everything beside the closed notch, measured and placed. Both sides reach [side] px from the notch. */
data class WingLayout(val left: List<Placed>, val right: List<Placed>, val side: Float) {
    val visible: Boolean get() = left.isNotEmpty() || right.isNotEmpty()
    val all: List<Placed> get() = left + right
    val cover: Placed? get() = left.firstOrNull { it.part == WingPart.Cover }
    /** Something shown counts the seconds, so the closed notch needs a clock. */
    val ticks: Boolean get() = all.any { it.part is WingPart.Time || it.part is WingPart.LiveText || (it.part is WingPart.Live && it.part.item.chronoAt != null) }

    /** The BentoBar tile at a point of the closed notch, with its place on screen. */
    fun tileAt(x: Float, y: Float, height: Float): Pair<BentoItem, android.graphics.Rect>? {
        if (y > height) return null
        val hit = all.firstOrNull { it.part is WingPart.Tile && x >= it.x && x < it.x + it.w } ?: return null
        return (hit.part as WingPart.Tile).item to
            android.graphics.Rect(hit.x.toInt(), 0, (hit.x + hit.w).toInt(), height.toInt())
    }

    companion object {
        val EMPTY = WingLayout(emptyList(), emptyList(), 0f)
    }
}

/**
 * Lays out the closed notch's wings. Every part is measured for what it holds now (text by its
 * glyphs, so a ticking time does not make the notch twitch). The notch stays centred on the
 * camera, so both sides reach as far as the longer one; to leave as little empty glass as
 * possible, the parts that may go either side (the time, live items, the music bars) fill the
 * shorter side first, a lone live item or time is split across the notch (icon left, words
 * right) when that evens the sides, and what space is left on the shorter side is shared out
 * between its parts rather than left as a gap.
 */
object WingLayouts {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        fontFeatureSettings = "tnum"
    }
    private val digits = Regex("\\d")
    /** The One UI wave's width beside the closed notch (it needs more room than a few bars). */
    const val WAVE_DP = 36

    fun textSp(text: String): Float = if (text.length > 5) 12f else 14f

    /** The running timer's countdown, else the running stopwatch, else null. */
    fun timeText(now: Long): String? =
        ToolsStore.soonestRunning?.let { TimeFormat.countdown(it.remaining(now)) }
            ?: ToolsStore.state.stopwatch.takeIf { it.isRunning }?.let { TimeFormat.coarse(it.elapsed(now)) }

    /** Whether a clock is needed to keep the wings' sizes right. */
    fun needsClock(): Boolean =
        ToolsStore.soonestRunning != null || ToolsStore.state.stopwatch.isRunning || LiveRepo.items.any { it.chronoAt != null }

    private fun textWidth(text: String, density: Float, sp: Float = textSp(text)): Float {
        paint.textSize = sp * density * Resources.getSystem().configuration.fontScale
        return paint.measureText(digits.replace(text, "0"))
    }

    private class Side(val parts: ArrayList<WingPart> = ArrayList(), var width: Float = 0f)

    fun compute(): WingLayout {
        val g = NotchState.geometry
        if (g.screenW == 0) return WingLayout.EMPTY
        val s = SettingsStore.settings
        val d = g.density
        val h = g.closedH
        val now = NotchState.clock
        val pad = 9 * d
        val icon = 18 * d
        val count = s.musicBarCount.coerceIn(3, 8)
        val barsH = max(8 * d, h - 18 * d)

        fun width(p: WingPart): Float = when (p) {
            WingPart.Cover -> h
            // A BentoBar item: its icon and, beside it, its value.
            is WingPart.Tile -> {
                val text = p.item.text?.takeIf { it.isNotBlank() }
                2 * 3 * d + 2 * 6 * d + 16 * d + (if (text != null) 4 * d + textWidth(text, d, 12f) else 0f)
            }
            WingPart.Bars -> (if (s.oneUiWaves) WAVE_DP else count * 5 - 2) * d + 2 * min((h - barsH) / 2, 10 * d)
            WingPart.Time -> 2 * pad + textWidth(timeText(now) ?: "0:00", d)
            WingPart.TimeIcon, is WingPart.LiveIcon -> 2 * pad + icon
            is WingPart.Live -> {
                val text = p.item.wingText(now)
                2 * 7 * d + icon + (if (text != null) 5 * d + textWidth(text, d) else 0f)
            }
            is WingPart.LiveText -> 2 * pad + textWidth(p.item.wingText(now) ?: "", d)
        }

        val room = g.screenW / 2f - g.notchW / 2 - 8 * d
        // Fixed places: the cover next to the notch on the left, BentoBar's tiles at the outer ends.
        val (tilesL, tilesR) = if (s.bentoInNotch) {
            val all = BentoRepo.items
            val (l, r) = s.bentoSides
            l.mapNotNull { id -> all.firstOrNull { it.id == id } } to r.mapNotNull { id -> all.firstOrNull { it.id == id } }
        } else emptyList<BentoItem>() to emptyList()
        val left = Side()
        val right = Side()
        if (MediaRepo.isMusicActive) { left.parts += WingPart.Cover; left.width += width(WingPart.Cover) }
        val tileWidthL = tilesL.sumOf { width(WingPart.Tile(it)).toDouble() }.toFloat()
        val tileWidthR = tilesR.sumOf { width(WingPart.Tile(it)).toDouble() }.toFloat()

        // Parts that may go either side, most important first.
        val showsTime = ToolsStore.soonestRunning != null || ToolsStore.state.stopwatch.isRunning
        val flexible = ArrayList<WingPart>()
        if (showsTime) flexible += WingPart.Time
        LiveRepo.items.forEach { flexible += WingPart.Live(it) }
        if (MediaRepo.isMusicActive && flexible.isEmpty()) flexible += WingPart.Bars
        for (p in flexible) {
            val w = width(p)
            val first = if (left.width + tileWidthL < right.width + tileWidthR) left else right
            val second = if (first === left) right else left
            val tilesFirst = if (first === left) tileWidthL else tileWidthR
            val tilesSecond = if (second === left) tileWidthL else tileWidthR
            when {
                first.width + tilesFirst + w <= room -> { first.parts += p; first.width += w }
                second.width + tilesSecond + w <= room -> { second.parts += p; second.width += w }
            }
        }

        // A lone time or live item split across the notch, icon on the shorter side, when that evens them.
        val l0 = left.width + tileWidthL
        val r0 = right.width + tileWidthR
        val (long, short) = if (l0 >= r0) left to right else right to left
        val candidate = long.parts.firstOrNull { it == WingPart.Time || (it is WingPart.Live && it.item.wingText(now) != null) }
        if (candidate != null) {
            val iconPart = if (candidate is WingPart.Live) WingPart.LiveIcon(candidate.item) else WingPart.TimeIcon
            val textPart = if (candidate is WingPart.Live) WingPart.LiveText(candidate.item) else WingPart.Time
            val newLong = long.width - width(candidate) + width(textPart)
            val newShort = short.width + width(iconPart)
            val tl = if (long === left) tileWidthL else tileWidthR
            val ts = if (short === left) tileWidthL else tileWidthR
            if (abs((newLong + tl) - (newShort + ts)) < abs(l0 - r0) && newShort + ts <= room) {
                long.parts[long.parts.indexOf(candidate)] = textPart
                long.width = newLong
                // The icon goes right beside the notch (after the cover, which stays nearest on the left).
                short.parts.add(if (short === left && short.parts.firstOrNull() == WingPart.Cover) 1 else 0, iconPart)
                short.width = newShort
            }
        }

        for ((sideOf, tiles) in listOf(left to tilesL, right to tilesR)) {
            tiles.forEach { val t = WingPart.Tile(it); val w = width(t); if (sideOf.width + w <= room) { sideOf.parts += t; sideOf.width += w } }
        }

        val side = max(left.width, right.width)
        fun place(sideOf: Side, isLeft: Boolean): List<Placed> {
            if (sideOf.parts.isEmpty()) return emptyList()
            // What the shorter side lacks goes to its parts with words (a time, a live item), which take the room as
            // breathing space; else to BentoBar's tiles, whose pills grow a little; with neither, into the gaps
            // between parts and next to the notch, never at the outer edge, so the outermost part always sits as
            // close to the border as on the other side. The music bars keep their size: widened, they'd only leave
            // a hole beside them.
            val lack = side - sideOf.width
            val words = sideOf.parts.any { it !is WingPart.Tile && it != WingPart.Cover && it != WingPart.Bars }
            fun stretches(p: WingPart) =
                if (words) p !is WingPart.Tile && p != WingPart.Cover && p != WingPart.Bars else p is WingPart.Tile
            val stretchy = sideOf.parts.count(::stretches)
            val grow = if (stretchy > 0) lack / stretchy else 0f
            val gap = if (stretchy > 0) 0f else lack / sideOf.parts.size
            var cursor = if (isLeft) g.centerX - g.notchW / 2 else g.centerX + g.notchW / 2
            return sideOf.parts.map { p ->
                cursor = if (isLeft) cursor - gap else cursor + gap
                val w = width(p) + (if (stretches(p)) grow else 0f)
                val x = if (isLeft) cursor - w else cursor
                cursor = if (isLeft) cursor - w else cursor + w
                Placed(p, x, w)
            }
        }
        return WingLayout(place(left, true), place(right, false), side)
    }
}
