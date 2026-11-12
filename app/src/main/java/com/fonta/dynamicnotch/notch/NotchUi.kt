package com.fonta.dynamicnotch.notch

import com.fonta.dynamicnotch.ui.Glyph
import com.fonta.dynamicnotch.ui.Ic
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.Theme
import com.fonta.dynamicnotch.data.TimeFormat
import com.fonta.dynamicnotch.data.ToolsStore
import com.fonta.dynamicnotch.services.BentoRepo
import com.fonta.dynamicnotch.services.LiveRepo
import com.fonta.dynamicnotch.services.MediaRepo
import com.fonta.dynamicnotch.ui.BentoTile
import com.fonta.dynamicnotch.ui.LiveIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import com.fonta.dynamicnotch.ui.MusicBars
import com.fonta.dynamicnotch.ui.MusicWave
import com.fonta.dynamicnotch.ui.OpenContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The notch outline. It hangs from the top; `x0`/`x1` are its left and right edges.
 * Top corners are outward concave fillets that merge into the screen edge; bottom corners are rounded.
 */
fun notchPath(x0: Float, x1: Float, height: Float, topRadius: Float, bottomRadius: Float): Path {
    val width = x1 - x0
    val tr = max(0f, min(topRadius, height / 2))
    val br = max(0f, min(min(bottomRadius, width / 2), height - tr))
    // True circular arcs, so the corners read like the screen's own.
    return Path().apply {
        moveTo(x0 - tr, 0f)
        if (tr > 0f) arcTo(Rect(x0 - 2 * tr, 0f, x0, 2 * tr), 270f, 90f, false)
        lineTo(x0, height - br)
        if (br > 0f) arcTo(Rect(x0, height - 2 * br, x0 + 2 * br, height), 180f, -90f, false) else lineTo(x0, height)
        lineTo(x1 - br, height)
        if (br > 0f) arcTo(Rect(x1 - 2 * br, height - 2 * br, x1, height), 90f, -90f, false) else lineTo(x1, height)
        lineTo(x1, tr)
        if (tr > 0f) arcTo(Rect(x1, 0f, x1 + 2 * tr, 2 * tr), 180f, 90f, false)
        lineTo(x1 + tr, 0f)
        close()
    }
}

/** Ticks the wall clock while `active`, for countdowns. */
@Composable
fun rememberNow(periodMs: Long, active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs, active) {
        now = System.currentTimeMillis()
        while (active) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun NotchRoot() {
    val g = NotchState.geometry
    val settings = SettingsStore.settings
    val theme = SettingsStore.theme
    val open = NotchState.isOpen
    val d = g.density
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Full-screen window: screen coordinates. Small closed window: centred on the notch.
        // The window covers the screen and never moves, so window x is screen x.
        val full = true
        val cx = g.centerX
        // "Settled" = closed and done closing: from then on, wing changes use their own spring.
        var settled by remember { mutableStateOf(!open) }
        LaunchedEffect(open) {
            if (open) settled = false else { delay(600); settled = true }
        }

        val leftWing = NotchState.side
        val rightWing = NotchState.side
        val idleVisible = settings.showNotchWhenIdle || NotchState.wingsVisible
        val anim = settings.animations
        val spring = when {
            open && NotchState.openedByAlarm -> anim.alarmOpen
            open -> anim.open
            else -> anim.close
        }
        // Wing changes while closed use their own spring.
        val shapeSpring = if (!open && settled) anim.wings else spring

        // Closed, both sides are as wide as the wider wing: the notch stays centred on the camera.
        val x0Target = if (open) g.openLeft - g.centerX else -(g.notchW / 2 + NotchState.side)
        val x1Target = if (open) g.openRight - g.centerX else g.notchW / 2 + NotchState.side
        val hTarget = if (open) g.openH else g.closedH
        val x0State = animateFloatAsState(x0Target, shapeSpring.spec(), label = "x0")
        val x1State = animateFloatAsState(x1Target, shapeSpring.spec(), label = "x1")
        val hState = animateFloatAsState(hTarget, spring.spec(), label = "h")
        val x0 by x0State
        val x1 by x1State
        val height by hState
        // Still on its way somewhere (from the moment a new target is set, before its first frame): the service asks
        // for the screen's top refresh rate meanwhile. Watched outside composition, so frames don't recompose.
        val targets by rememberUpdatedState(floatArrayOf(x0Target, x1Target, hTarget))
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow {
                val t = targets
                kotlin.math.abs(x0State.value - t[0]) > 0.5f || kotlin.math.abs(x1State.value - t[1]) > 0.5f ||
                    kotlin.math.abs(hState.value - t[2]) > 0.5f
            }.collect { NotchState.animating = it }
        }
        val topR by animateFloatAsState(if (open) theme.topFilletRadius.toFloat() * d else 0f, spring.spec(), label = "tr")
        // Corners follow the screen's own radius when asked (and known); the closed notch is capped so
        // its sides stay straight.
        val deviceR = g.cornerPx.takeIf { settings.matchDeviceCorners && it > 0f }
        val bottomTarget = if (open) deviceR ?: (theme.openBottomRadius.toFloat() * d)
        else (deviceR ?: (theme.closedBottomRadius.toFloat() * d)).coerceAtMost(g.closedH * 0.5f)
        val bottomR by animateFloatAsState(bottomTarget, spring.spec(), label = "br")
        val shapeAlpha by animateFloatAsState(if (open || idleVisible) 1f else 0f, tween(200), label = "alpha")
        val shadowAlpha by animateFloatAsState(if (open && theme.shadowEnabled) theme.shadowOpacity.toFloat() else 0f, tween(250), label = "shadow")

        val artBg = MediaRepo.artworkColor?.takeIf { theme.useArtworkColor && MediaRepo.isMusicActive }
            ?.scaled(theme.artworkIntensity)?.darkened(theme.maxBackgroundLuminance)?.color
        val bg by animateColorAsState(
            if (open) artBg ?: theme.fallbackBackgroundColor.color else Color.Black, tween(300), label = "bg",
        )

        // Read through updated state: the gesture blocks below outlive geometry and window changes.
        val insideShape by rememberUpdatedState { p: Offset ->
            val pad = NotchGeometry.HOT_PAD_DP * d
            p.x >= cx + x0 - pad && p.x <= cx + x1 + pad && p.y <= height + pad
        }

        Box(
            Modifier
                .fillMaxSize()
                // Mouse / stylus hover: open after a short pause, close when the pointer leaves.
                .pointerInput(Unit) {
                    var job: Job? = null
                    // A BentoBar wing pointed at opens its own BentoBar popup, as in BentoBar's bar, not the notch.
                    var tileJob: Job? = null
                    var tileId: String? = null
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull() ?: continue
                            if (c.type != PointerType.Mouse && c.type != PointerType.Stylus) continue
                            if (c.pressed) continue
                            val inside = e.type != PointerEventType.Exit && insideShape(c.position)
                            val tile = if (inside && !NotchState.isOpen) NotchState.bentoTileAt(c.position.x, c.position.y) else null
                            if (tile?.first?.id != tileId) {
                                tileJob?.cancel(); tileJob = null
                                tileId = tile?.first?.id
                                if (tile != null) tileJob = scope.launch {
                                    delay((SettingsStore.settings.hoverOpenDelay * 1000).toLong())
                                    BentoRepo.open(tile.first, tile.second, hover = true)
                                }
                            }
                            if (tile != null) {
                                job?.cancel(); job = null
                            } else if (!NotchState.isOpen && inside && job == null) {
                                job = scope.launch {
                                    delay((SettingsStore.settings.hoverOpenDelay * 1000).toLong())
                                    NotchState.open(byHover = true)
                                    job = null
                                }
                            } else if (!NotchState.isOpen && !inside) {
                                job?.cancel(); job = null
                            } else if (NotchState.isOpen && NotchState.openedByHover && !inside && job == null) {
                                job = scope.launch {
                                    delay((SettingsStore.settings.closeDelay * 1000).toLong())
                                    NotchState.close()
                                    job = null
                                }
                            } else if (NotchState.isOpen && inside) {
                                job?.cancel(); job = null
                            }
                        }
                    }
                }
                // Touch: tap the closed notch to open it; tap anywhere outside the open notch to close it.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!NotchState.isOpen) {
                            down.consume()
                            // The window stays full-screen for a moment while closing: only the notch itself reopens.
                            val onNotch = insideShape(down.position)
                            // A BentoBar tile opens that item's own BentoBar popup under it, not the notch.
                            val tile = NotchState.bentoTileAt(down.position.x, down.position.y)
                            if (waitForUpOrCancellation(PointerEventPass.Initial) != null) {
                                if (tile != null) BentoRepo.open(tile.first, tile.second) else if (onNotch) NotchState.open()
                            }
                        } else if (!insideShape(down.position)) {
                            down.consume()
                            NotchState.close()
                        }
                    }
                }
                .drawWithContent {
                    // 0 closed … 1 open: how far the glass has taken over from the solid black notch.
                    val openness = ((height - g.closedH) / (g.openH - g.closedH).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val glass = NotchState.glass?.takeIf { settings.liveBlur && full && it.supported }
                    NotchState.glass?.frame(
                        g.centerX + x0, g.centerX + x1, height, bottomR,
                        when {
                            glass == null || shapeAlpha <= 0.01f -> 0f
                            settings.blurWhenClosed -> shapeAlpha   // glass all the time
                            else -> openness                        // glass only as it opens
                        },
                    )
                    if (shapeAlpha <= 0.01f) return@drawWithContent
                    val path = notchPath(cx + x0, cx + x1, height, topR, bottomR)
                    if (shadowAlpha > 0.01f) {
                        drawIntoCanvas { canvas ->
                            val paint = Paint()
                            paint.asFrameworkPaint().apply {
                                color = Color.Black.toArgb()
                                setShadowLayer(
                                    theme.shadowRadius.toFloat() * d, 0f, theme.shadowYOffset.toFloat() * d,
                                    theme.shadowTint.color.copy(alpha = shadowAlpha).toArgb(),
                                )
                            }
                            // Only the shadow outside the shape: the glass is see-through.
                            canvas.save()
                            canvas.clipPath(path, ClipOp.Difference)
                            canvas.drawPath(path, paint)
                            canvas.restore()
                        }
                    }
                    if (glass == null) {
                        drawPath(path, Color.Black, alpha = shapeAlpha)
                        clipPath(path) {
                            drawRect(bg, alpha = shapeAlpha)
                            this@drawWithContent.drawContent()
                        }
                    } else {
                        // A tint over the blur. Without "blur when closed" the closed notch is solid black
                        // and thins to the tint as it opens.
                        val tint = settings.glassTint.toFloat().coerceIn(0f, 1f)
                        val glassiness = if (settings.blurWhenClosed) 1f else openness
                        drawPath(path, bg, alpha = shapeAlpha * (1f + (tint - 1f) * glassiness))
                        clipPath(path) { this@drawWithContent.drawContent() }
                    }
                    // The border. The part along the screen's top edge is cut away.
                    val bw = settings.borderWidth.toFloat() * d
                    if (bw > 0.05f * d) {
                        clipRect(top = bw) {
                            drawPath(path, settings.borderColor.color, alpha = settings.borderColor.color.alpha * shapeAlpha, style = Stroke(bw))
                        }
                    }
                    if (settings.showDebugOverlay) {
                        val s = Stroke(1.5f)
                        drawRect(Color.Yellow, Offset(cx - g.notchW / 2, 0f), androidx.compose.ui.geometry.Size(g.notchW, g.notchH), style = s)
                        drawRect(Color.Green, Offset(cx - g.notchW / 2 - leftWing, 0f), androidx.compose.ui.geometry.Size(g.notchW + leftWing + rightWing, g.closedH), style = s)
                        if (full) drawRect(Color.Cyan, Offset(g.openLeft, 0f), androidx.compose.ui.geometry.Size(g.openW, g.openH), style = s)
                    }
                },
        ) {
            val density = LocalDensity.current
            AnimatedVisibility(open && full, enter = fadeIn(tween(220, delayMillis = 60)), exit = fadeOut(tween(110))) {
                Box(
                    with(density) {
                        Modifier
                            .offset { IntOffset(g.openLeft.roundToInt(), 0) }
                            .size(g.openW.toDp(), g.openH.toDp())
                            .pointerInput(Unit) {
                                // Swallow taps on the panel background; long-press opens Settings, except on the
                                // BentoBar tab, where holding an item pins it beside the notch.
                                detectTapGestures(onLongPress = {
                                    if (NotchState.tab != com.fonta.dynamicnotch.data.NotchTab.bento) NotchService.openSettings(context)
                                })
                            }
                    },
                ) {
                    OpenContent(g, theme, settings)
                }
            }
            AnimatedVisibility(!open && NotchState.wingsVisible, enter = fadeIn(tween(250)), exit = fadeOut(tween(100))) {
                ClosedWings(g, theme, cx)
            }
            // The album cover is one picture that travels: from beside the closed notch to its place in the
            // open panel and back, growing on the way, instead of one cover vanishing and another appearing.
            val slot = NotchState.openCoverRect
            var lastSlot by remember { mutableStateOf<Rect?>(null) }
            if (slot != null) SideEffect { lastSlot = slot }
            val travel by animateFloatAsState(if (open) 1f else 0f, spring.spec(), label = "cover")
            val coverAlpha by animateFloatAsState(
                when {
                    open -> if (slot != null) 1f else 0f
                    else -> if (NotchState.layout.cover != null) 1f else 0f
                },
                tween(if (open && slot == null) 260 else 140), label = "coverAlpha",
            )
            if (coverAlpha > 0.01f) {
                val small = max(12 * d, g.closedH - 12 * d)
                val inset = (g.closedH - small) / 2
                val coverAt = NotchState.layout.cover
                val from = Rect(
                    Offset((coverAt?.let { it.x + (it.w - small) / 2 } ?: (cx - g.notchW / 2 - g.closedH + inset)), inset),
                    androidx.compose.ui.geometry.Size(small, small),
                )
                val to = slot ?: lastSlot ?: from
                // The spring may overshoot; the cover itself never grows past its place.
                val t = travel.coerceIn(0f, 1f)
                fun mix(a: Float, b: Float) = a + (b - a) * t
                val w = mix(from.width, to.width).coerceAtLeast(1f)
                val hh = mix(from.height, to.height).coerceAtLeast(1f)
                with(density) {
                    CoverImage(
                        mix(theme.coverRadiusClosed.toFloat(), theme.coverRadiusOpen.toFloat()).coerceAtLeast(0f).dp,
                        Modifier
                            .offset { IntOffset(mix(from.left, to.left).roundToInt(), mix(from.top, to.top).roundToInt()) }
                            .size(w.toDp(), hh.toDp())
                            .alpha(coverAlpha),
                    )
                }
            }
        }
    }
}

/**
 * What sits beside the closed notch, as [WingLayouts] placed it. Each part slides to its new place
 * when the layout changes (something comes or goes), with the wings' spring.
 */
@Composable
private fun ClosedWings(g: NotchGeometry, theme: Theme, cx: Float) {
    val density = LocalDensity.current
    val settings = SettingsStore.settings
    val h = g.closedH
    val layout = NotchState.layout
    val now = rememberNow(500, layout.ticks)
    val numbers = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
    @Composable
    fun words(text: String) = Text(
        text, color = theme.primaryText, fontSize = WingLayouts.textSp(text).sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, style = numbers,
    )
    with(density) {
        Box(Modifier.fillMaxSize()) {
            for (p in layout.all) key(p.part.key) {
                val x by animateFloatAsState(p.x + cx - g.centerX, settings.animations.wings.spec(), label = "wing")
                val w by animateFloatAsState(p.w, settings.animations.wings.spec(), label = "wingW")
                Box(
                    Modifier.offset { IntOffset(x.roundToInt(), 0) }.size(w.toDp(), h.toDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val part = p.part) {
                        // Drawn by NotchRoot: the same picture grows into the open panel.
                        WingPart.Cover -> {}
                        WingPart.Bars -> {
                            val color = MediaRepo.artworkColor?.takeIf { settings.musicBarsUseArtworkColor }?.lightenedForBars?.color ?: theme.accent
                            val count = settings.musicBarCount.coerceIn(3, 8)
                            val size = Modifier.size((if (settings.oneUiWaves) WingLayouts.WAVE_DP else count * 5 - 2).dp, max(8.dp.toPx(), h - 18.dp.toPx()).toDp())
                            if (settings.oneUiWaves) MusicWave(settings.musicBarSpeed.toFloat(), color, MediaRepo.isPlaying, size)
                            else MusicBars(count, settings.musicBarSpeed.toFloat(), color, MediaRepo.isPlaying, size)
                        }
                        WingPart.Time -> WingLayouts.timeText(now)?.let { words(it) }
                        WingPart.TimeIcon -> Glyph(Ic.timer, null, Modifier.size(18.dp), theme.primaryText)
                        is WingPart.Live -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            LiveIcon(part.item, 18.dp)
                            part.item.wingText(now)?.let { words(it) }
                        }
                        is WingPart.LiveIcon -> LiveIcon(part.item, 18.dp)
                        is WingPart.LiveText -> part.item.wingText(now)?.let { words(it) }
                        is WingPart.Tile -> BentoTile(part.item, theme, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/**
 * The album cover's place in the open panel. It draws nothing: NotchRoot's travelling cover comes to
 * rest here, and this says where "here" is.
 */
@Composable
fun CoverSlot(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) { onDispose { NotchState.openCoverRect = null } }
    Box(
        modifier.onGloballyPositioned { c ->
            val p = c.positionInWindow()
            val r = Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
            if (r != NotchState.openCoverRect) NotchState.openCoverRect = r
        },
    )
}

/** Album cover with a placeholder when there is no artwork. */
@Composable
fun CoverImage(radius: Dp, modifier: Modifier = Modifier) {
    val art = MediaRepo.artwork
    Box(modifier.clip(RoundedCornerShape(radius)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (art != null) {
            Image(remember(art) { art.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Glyph(Ic.music, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}
