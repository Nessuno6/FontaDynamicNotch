package com.fonta.dynamicnotch.notch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fonta.dynamicnotch.data.AppSettings
import com.fonta.dynamicnotch.data.NotchTab
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.ToolsStore
import com.fonta.dynamicnotch.services.BentoItem
import com.fonta.dynamicnotch.services.BentoRepo
import com.fonta.dynamicnotch.services.BrightnessRepo
import com.fonta.dynamicnotch.services.LiveItem
import com.fonta.dynamicnotch.services.LiveRepo
import com.fonta.dynamicnotch.services.MediaRepo
import com.fonta.dynamicnotch.services.NowBarControl
import com.fonta.dynamicnotch.services.ShelfStore
import com.fonta.dynamicnotch.settings.SettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** All sizes for the current screen, in pixels. x values are screen coordinates. */
data class NotchGeometry(
    val density: Float = 1f,
    val screenW: Int = 0,
    val screenH: Int = 0,
    val hasRealNotch: Boolean = false,
    val centerX: Float = 0f,
    val notchW: Float = 0f,
    val notchH: Float = 0f,
    val closedH: Float = 0f,
    val wing: Float = 0f,
    val timeWing: Float = 0f,
    val liveWing: Float = 0f,
    /** Radius of the screen's own rounded corners, 0 when unknown. */
    val cornerPx: Float = 0f,
    /** Phone-sized screens get the stacked layout. */
    val compact: Boolean = false,
    val openLeft: Float = 0f,
    val openW: Float = 0f,
    val openH: Float = 0f,
    val topBarH: Float = 0f,
) {
    val openRight get() = openLeft + openW
    val notchLeft get() = centerX - notchW / 2
    val notchRight get() = centerX + notchW / 2

    companion object {
        const val HOT_PAD_DP = 6f

        fun compute(context: Context, s: AppSettings): NotchGeometry {
            val wm = context.getSystemService(WindowManager::class.java)
            val metrics = wm.maximumWindowMetrics
            val d = context.resources.displayMetrics.density
            val w = metrics.bounds.width()
            val h = metrics.bounds.height()
            val insets = metrics.windowInsets
            val cutTop = insets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
            val statusH = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top

            val corner = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT)
                .mapNotNull { insets.getRoundedCorner(it)?.radius }.maxOrNull()?.toFloat() ?: 0f
            val real = cutTop != null
            val centerX = cutTop?.exactCenterX() ?: (w / 2f)
            val notchH = max(max(statusH, cutTop?.height() ?: 0).toFloat(), 24 * d)
            val margin = 8 * d
            val compact = w < (s.openWidth.toFloat() + 32) * d
            // Phones have little free status-bar room, so the notch is narrower there.
            val wanted = s.notchWidth.toFloat() * d * (if (compact) 0.6f else 1f)
            val notchW = max(wanted, (cutTop?.width() ?: 0) + 24 * d)
            val topBarH = max(notchH, (if (compact) 40 else 36) * d)
            val openW: Float
            val openH: Float
            if (compact) {
                openW = min(w - 2 * margin, 440 * d)
                openH = topBarH + 344 * d
            } else {
                openW = max(s.openWidth.toFloat() * d, notchW + 240 * d)
                openH = max(s.openHeight.toFloat() * d, topBarH + 150 * d)
            }
            val openLeft = (centerX - openW / 2).coerceIn(margin, max(margin, w - openW - margin))
            return NotchGeometry(
                density = d, screenW = w, screenH = h, hasRealNotch = real, centerX = centerX,
                notchW = notchW, notchH = notchH,
                closedH = notchH + s.closedHeightExtra.toFloat() * d,
                wing = s.closedWingWidth.toFloat() * d, timeWing = 68 * d, liveWing = 88 * d, cornerPx = corner,
                compact = compact, openLeft = openLeft, openW = openW, openH = openH, topBarH = topBarH,
            )
        }
    }
}

enum class RightWing { none, bars, time, live }

/** Shared notch state: the service owns the window, the Compose UI reads and drives this. */
object NotchState {
    var geometry by mutableStateOf(NotchGeometry())
    var isOpen by mutableStateOf(false)
        private set
    var tab by mutableStateOf(NotchTab.home)
    /** A full-screen app is showing: the notch disappears. */
    var hiddenByFullscreen by mutableStateOf(false)
    var openedByHover = false
        private set
    /** True when the last open was caused by an alarm (uses the alarm spring). */
    var openedByAlarm by mutableStateOf(false)
        private set
    var serviceRunning by mutableStateOf(false)
    /** The notch's shape is on the move (opening, closing, wings resizing): its windows ask for the top refresh rate. */
    var animating by mutableStateOf(false)
    /** The blur window behind the open notch, when the device can blur. */
    internal var glass: GlassWindow? = null
    var glassAvailable by mutableStateOf(false)

    /** Ticks once a second while something beside the closed notch shows a running time (NotchService). */
    var clock by mutableLongStateOf(System.currentTimeMillis())

    /**
     * Everything beside the closed notch, measured and placed. Recomputed whenever what it holds
     * changes (a live activity comes or goes, a tile is added, music starts); readers are only told
     * when the result differs, so a ticking clock does not redraw the notch.
     */
    val layout: WingLayout by derivedStateOf { WingLayouts.compute() }

    /** The notch reaches this far on both sides, so it stays centred whatever its wings hold. */
    val side: Float get() = layout.side
    val wingsVisible: Boolean get() = layout.visible

    /** Where the open panel's album cover sits (window pixels), while that panel shows one. */
    var openCoverRect by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)

    /** The BentoBar tile at a point of the closed notch, with its place on screen. */
    fun bentoTileAt(x: Float, y: Float): Pair<BentoItem, android.graphics.Rect>? = layout.tileAt(x, y, geometry.closedH)

    fun open(byHover: Boolean = false, byAlarm: Boolean = false, tab: NotchTab? = null) {
        if (hiddenByFullscreen && !byAlarm) return
        if (tab != null) this.tab = tab
        if (!isOpen) {
            openedByHover = byHover
            openedByAlarm = byAlarm
            BrightnessRepo.refresh()
            // A BentoBar popup a wing opened (pointed at, then the pointer moved on to the notch) goes as the notch opens.
            if (SettingsStore.settings.bentoInNotch) com.fonta.dynamicnotch.services.BentoRepo.closeMenu()
        }
        isOpen = true
    }

    fun close(force: Boolean = false) {
        if (!force && ToolsStore.isRinging) return
        isOpen = false
    }
}

/**
 * Hosts the notch as an accessibility overlay: the only window type that can sit on top of the
 * status bar and the camera cutout, and take touches there. The service reads nothing from the screen.
 */
class NotchService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Which part of the (always full-screen) window takes touches. */
    private data class TouchSpec(val open: Boolean, val left: Int, val right: Int, val bottom: Int, val shown: Boolean)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = recomputeGeometry()
    }

    /** An open notch should not still be open when the screen comes back on. */
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = NotchState.close()
    }

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        MediaRepo.attach(this)
        recomputeGeometry()
        // The glass first: it must lie under the notch's own window, or it would blur the notch too.
        val g = GlassWindow(this)
        if (g.show(glassHeight())) NotchState.glass = g
        NotchState.glassAvailable = g.supported
        addOverlay()
        // Keep the system's switches in step with the app's option (they can be reset by updates).
        // Samsung's Now Bar and status-bar chip are hidden only while the notch is on to show their items instead:
        // switching the notch off (the Quick Settings tile) brings them back. Also kept in step after updates.
        lifecycleScope.launch {
            var first = true
            snapshotFlow { SettingsStore.settings.let { it.hideNowBar && it.notchEnabled } }.distinctUntilChanged().collectLatest { hide ->
                // At start, only a "hide" needs saying: Samsung's own setting is "shown" unless the notch hid it.
                if (hide || !first || SettingsStore.settings.hideNowBar) NowBarControl.apply(this@NotchService, hide) {}
                first = false
            }
        }
        registry.currentState = Lifecycle.State.RESUMED
        NotchState.serviceRunning = true
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))

        lifecycleScope.launch {
            snapshotFlow { SettingsStore.settings }.distinctUntilChanged().collectLatest { recomputeGeometry(); checkStatusBar() }
        }
        lifecycleScope.launch {
            snapshotFlow { targetSpec() }.distinctUntilChanged().collectLatest { apply(it) }
        }
        // Updates from GitHub: looked for once a day while the notch runs, when wanted (services/Updates).
        lifecycleScope.launch {
            com.fonta.dynamicnotch.services.Updates.load(this@NotchService)
            while (true) {
                com.fonta.dynamicnotch.services.Updates.checkIfDue(this@NotchService)
                kotlinx.coroutines.delay(60 * 60 * 1000L)
            }
        }
        // Android 15+ gives a window's ordinary redraws the "normal" rate (60 Hz on many screens) unless the window
        // asks for more: while the notch moves, both its windows ask for the screen's top rate, so the spring runs
        // at 120 Hz from its first frame, touch or no touch (hover, closing by itself, wings resizing).
        lifecycleScope.launch {
            snapshotFlow { NotchState.animating }.distinctUntilChanged().collectLatest { moving ->
                if (Build.VERSION.SDK_INT < 35) return@collectLatest
                val rate = if (moving) topRefreshRate() else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
                view?.requestedFrameRate = rate
                NotchState.glass?.requestFrameRate(rate)
            }
        }
        lifecycleScope.launch {
            snapshotFlow { SettingsStore.settings.bentoInNotch }.distinctUntilChanged().collectLatest { BentoRepo.set(this@NotchService, it) }
        }
        // BentoBar's items pinned beside the notch leave BentoBar's own bar; while the BentoBar tab is open, every
        // item is listed. Sent on every change and renewed once a minute (BentoBar's lease is two minutes).
        lifecycleScope.launch {
            snapshotFlow {
                val s = SettingsStore.settings
                val (l, r) = s.bentoSides
                // Notch off (the Quick Settings tile): BentoBar gets its whole bar back at once.
                val on = s.bentoInNotch && s.notchEnabled && NotchState.serviceRunning
                Triple(
                    if (on) l + r else emptyList(),
                    on && NotchState.isOpen && NotchState.tab == NotchTab.bento,
                    on && s.hideBentoBar,
                )
            }.distinctUntilChanged().collectLatest { (hide, watch, whole) ->
                while (true) {
                    BentoRepo.claim(hide, watch, whole)
                    if (hide.isEmpty() && !watch && !whole) break
                    kotlinx.coroutines.delay(60_000)
                }
            }
        }
        // The wings' clock: once a second, on the second, only while something there counts.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000 - System.currentTimeMillis() % 1000 + 5)
                if (WingLayouts.needsClock()) NotchState.clock = System.currentTimeMillis()
            }
        }
        lifecycleScope.launch {
            snapshotFlow { ToolsStore.isRinging }.distinctUntilChanged().collectLatest { if (it) NotchState.open(byAlarm = true, tab = NotchTab.home) }
        }
        lifecycleScope.launch {
            var seen = ShelfStore.arrivals
            snapshotFlow { ShelfStore.arrivals }.collectLatest { if (it != seen) { seen = it; NotchState.open(tab = NotchTab.files) } }
        }
    }

    /** The highest refresh rate the screen offers (120 Hz on this kind of tablet). */
    private fun topRefreshRate(): Float {
        val d = getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return 120f
        return d.supportedModes.maxOfOrNull { it.refreshRate } ?: d.refreshRate
    }

    private fun recomputeGeometry() {
        NotchState.geometry = NotchGeometry.compute(this, SettingsStore.settings)
        NotchState.glass?.resize(glassHeight())
    }

    /** Room for the open notch plus the spring's overshoot. */
    private fun glassHeight() = (NotchState.geometry.openH * 1.3f).roundToInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recomputeGeometry()
    }

    private fun targetSpec(): TouchSpec {
        val g = NotchState.geometry
        val shown = SettingsStore.settings.notchEnabled && (!NotchState.hiddenByFullscreen || NotchState.isOpen)
        val pad = NotchGeometry.HOT_PAD_DP * g.density
        return TouchSpec(
            NotchState.isOpen,
            (g.centerX - g.notchW / 2 - NotchState.side - pad).roundToInt(),
            (g.centerX + g.notchW / 2 + NotchState.side + pad).roundToInt(),
            (g.closedH + pad).roundToInt(),
            shown,
        )
    }

    private fun addOverlay() {
        val v = ComposeView(this)
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setContent { NotchRoot() }
        // The status bar disappearing means a full-screen app: hide the notch with it.
        // Files dragged from another app: hovering the notch opens it on the Files tab, dropping parks them.
        var openedByDrag = false
        var dropped = false
        v.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    openedByDrag = false
                    dropped = false
                    !ShelfStore.draggingOut
                }
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    if (!NotchState.isOpen) {
                        openedByDrag = true
                        NotchState.open(tab = NotchTab.files)
                    }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val g = NotchState.geometry
                    val inside = e.x >= g.openLeft && e.x <= g.openRight && e.y <= g.openH
                    if (inside && ShelfStore.addFromDrop(e) > 0) {
                        dropped = true
                        NotchState.tab = NotchTab.files
                    }
                    dropped
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    ShelfStore.draggingOut = false
                    if (openedByDrag && !dropped) NotchState.close()
                    true
                }
                else -> true
            }
        }
        // The window never changes size or place (resizing it made the notch jump for a frame). It covers
        // the screen, and only its touchable region changes: the closed notch, or everything while open.
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.LEFT
        p.x = 0
        p.y = 0
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.windowAnimations = 0
        p.title = "FontaDynamicNotch"
        p.setFitInsetsTypes(0)
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.post { apply(targetSpec()) } }
            override fun onViewDetachedFromWindow(view: View) {}
        })
        wm.addView(v, p)
        view = v
        params = p
    }

    private fun apply(spec: TouchSpec) {
        val v = view ?: return
        v.visibility = if (spec.shown) View.VISIBLE else View.INVISIBLE
        // The glass is a window of its own: it goes with the notch (it keeps its last frame otherwise).
        if (!spec.shown) NotchState.glass?.frame(0f, 0f, 0f, 0f, 0f)
        val region = when {
            !spec.shown -> Region()
            spec.open -> null   // the whole window: a tap outside the open notch closes it
            else -> Region(spec.left, 0, spec.right, spec.bottom)
        }
        runCatching { v.rootSurfaceControl?.setTouchableRegion(region) }
    }

    /**
     * Windows came, went or moved: whether SystemUI's status bar is still on screen is looked at
     * again a moment later (several such events come together).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            main.removeCallbacks(statusCheck)
            main.postDelayed(statusCheck, 60)
        }
    }

    private val statusCheck = Runnable { checkStatusBar() }

    /**
     * Hides the notch while the status bar is gone (a full-screen video, a game, an app that hides
     * it). Only which windows exist and where they are is read: the status bar is SystemUI's system
     * window along the top edge, no taller than the bar can be. Nothing inside any window is read.
     */
    private fun checkStatusBar() {
        val hide = SettingsStore.settings.hideWhenStatusBarHidden && !statusBarShown()
        if (hide != NotchState.hiddenByFullscreen) NotchState.hiddenByFullscreen = hide
        if (hide && NotchState.isOpen && !ToolsStore.isRinging) NotchState.close()
    }

    private fun statusBarShown(): Boolean {
        val g = NotchState.geometry
        if (g.screenW == 0) return true
        val list = runCatching { windows }.getOrNull() ?: return true
        if (list.isEmpty()) return true
        val maxH = max(g.notchH * 2.5f, 64 * g.density)
        val r = android.graphics.Rect()
        return list.any { w ->
            w.getBoundsInScreen(r)
            // A see-through panel past every edge (Samsung's edge panel) hides the bar from the list, not the screen.
            val overhangs = r.left < 0 && r.top < 0 && r.right > g.screenW && r.bottom > g.screenH
            overhangs || (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                r.top == 0 && r.height() in 1..maxH.toInt() && r.width() >= g.screenW / 2)
        }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun teardown() {
        NotchState.serviceRunning = false
        NotchState.close(force = true)
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        runCatching { unregisterReceiver(screenOff) }
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        BentoRepo.claim(emptyList(), false)
        // The notch is gone (its service turned off): Samsung's Now Bar and chip come back.
        if (SettingsStore.settings.hideNowBar) NowBarControl.apply(this, hide = false) {}
        BentoRepo.set(this, false)
        NotchState.glass?.destroy()
        NotchState.glass = null
        NotchState.glassAvailable = false
    }

    companion object {
        fun openSettings(context: Context) {
            NotchState.close()
            context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
