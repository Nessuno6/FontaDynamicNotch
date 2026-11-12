package com.fonta.dynamicnotch.notch

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.fonta.dynamicnotch.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The live blur behind the open notch, done the way BentoBar does it: only a `Window` can blur what is
 * behind it, so the glass is a dialog window of type TYPE_ACCESSIBILITY_OVERLAY that draws nothing and
 * takes no touches. The platform blurs exactly its root view's rectangle (with the corners of its
 * background's outline), so before each frame the root view is framed to the notch as far as it shows.
 *
 * It must be added before the notch's own window (later windows of one type are higher), otherwise it
 * would blur the notch too. It starts above the top of the screen, so the rectangle's rounded top
 * corners are off-screen and only the bottom ones show.
 *
 * Where Android's window blur is off for apps (Samsung tablets), Samsung's own blur is used instead.
 */
internal class GlassWindow(service: Context) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val outline = GlassOutline()
    private val dialog = Dialog(service, R.style.Theme_Notch_Glass)
    private val lift = (LIFT_DP * density).roundToInt()
    private val blurPx = (BLUR_DP * density).roundToInt()

    private var shown = false
    private var active = false
    private var lastBlur = -1
    private var lastCorner = -1f
    private var left = 0
    private var right = 0
    private var bottom = 0

    val supported: Boolean get() = shown && (wm.isCrossWindowBlurEnabled || SemBlur.available)
    private val samsung: Boolean get() = !wm.isCrossWindowBlurEnabled && SemBlur.available

    /** A layout pass gives the root view the whole window back, so the frame is re-set before each draw. */
    private val keepFrame = ViewTreeObserver.OnPreDrawListener {
        applyFrame()
        true
    }

    fun show(heightPx: Int): Boolean {
        val window = dialog.window ?: return false
        dialog.setCancelable(false)
        dialog.setContentView(View(dialog.context))
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        window.setBackgroundDrawable(outline)
        window.setWindowAnimations(0)
        window.setGravity(Gravity.TOP or Gravity.LEFT)
        window.setDecorFitsSystemWindows(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        window.attributes = window.attributes.apply {
            x = 0
            y = -lift
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = heightPx + lift
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FontaDynamicNotchGlass"
        }
        return try {
            dialog.show()
            window.decorView.visibility = View.INVISIBLE
            window.decorView.viewTreeObserver.addOnPreDrawListener(keepFrame)
            shown = true
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "can't add the glass window: ${e.javaClass.simpleName}")
            false
        }
    }

    /** The open notch's size changed (rotation, settings). [heightPx] leaves room for the spring's overshoot. */
    fun resize(heightPx: Int) {
        val window = dialog.window ?: return
        if (!shown) return
        val a = window.attributes
        if (a.height == heightPx + lift) return
        a.height = heightPx + lift
        runCatching { window.attributes = a }
    }

    /**
     * Called for every frame the notch draws: the glass from [leftPx] to [rightPx] (screen x), down to
     * [bottomPx], with [corner] px bottom corners, as present as [strength] (0 = closed, no blur at all).
     */
    fun frame(leftPx: Float, rightPx: Float, bottomPx: Float, corner: Float, strength: Float) {
        val decor = dialog.window?.decorView ?: return
        if (!shown) return
        if (strength <= 0.01f) {
            if (active) {
                active = false
                setBlur(0)
                decor.visibility = View.INVISIBLE
            }
            return
        }
        left = leftPx.roundToInt()
        right = rightPx.roundToInt()
        bottom = bottomPx.roundToInt() + lift
        outline.corner = corner
        if (!active) {
            active = true
            decor.visibility = View.VISIBLE
        }
        applyFrame()
        // Never 0 while showing: the platform lets the blur layer go, and Samsung's leaves a black frame.
        val r = if (samsung) (SemBlur.RADIUS_PX * strength).roundToInt() else (blurPx * strength).roundToInt()
        setBlur(r.coerceAtLeast(1))
    }

    /** The refresh rate the glass asks for while the notch moves (Android 15+, see NotchService). */
    @androidx.annotation.RequiresApi(35)
    fun requestFrameRate(rate: Float) {
        dialog.window?.decorView?.requestedFrameRate = rate
    }

    private fun applyFrame() {
        if (!active) return
        val root = dialog.window?.decorView as? ViewGroup ?: return
        // Set only when it differs: each change asks for another frame.
        val bounds = root.left != left || root.top != 0 || root.right != right || root.bottom != bottom
        if (bounds) root.setLeftTopRightBottom(left, 0, right, bottom)
        if (bounds || outline.corner != lastCorner) {
            lastCorner = outline.corner
            root.invalidateOutline()
            root.invalidate()
        }
    }

    private fun setBlur(radius: Int) {
        if (radius == lastBlur) return
        lastBlur = radius
        val window = dialog.window ?: return
        if (samsung) SemBlur.apply(window.decorView, radius, outline.corner)
        else runCatching { window.setBackgroundBlurRadius(radius) }
    }

    fun destroy() {
        dialog.window?.decorView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(keepFrame) }
        runCatching { dialog.dismiss() }
        shown = false
    }

    companion object {
        const val TAG = "FontaNotch"
        const val BLUR_DP = 28f
        /** How far above the screen the window starts; must be at least the largest bottom radius. */
        const val LIFT_DP = 64f
    }
}

/**
 * The glass window's background: it paints nothing, but its outline is the rounded rectangle the
 * platform takes the blur's corners from.
 */
private class GlassOutline : Drawable() {
    var corner = 0f
    override fun draw(canvas: Canvas) {}
    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, min(corner, min(bounds.width(), bounds.height()) / 2f))
        outline.alpha = 1f
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Samsung's window blur (One UI): `View.semSetBlurInfo` with a `SemBlurInfo` in its window mode, which
 * blurs what is behind the view live, as the notification panel's glass does. Not in Android's SDK, so
 * it is found by name; where it isn't there, nothing changes.
 */
internal object SemBlur {
    private val info = runCatching { Class.forName("android.view.SemBlurInfo") }.getOrNull()
    private val builder = runCatching { Class.forName("android.view.SemBlurInfo\$Builder") }.getOrNull()
    private val windowMode = runCatching { info!!.getField("BLUR_MODE_WINDOW").getInt(null) }.getOrNull()
    private val set = runCatching { View::class.java.getMethod("semSetBlurInfo", info) }.getOrNull()
    val available get() = info != null && builder != null && windowMode != null && set != null

    /** Quick Settings' glass: a 300 px blur, and what shows through held between 0.18 and 0.85 brightness. */
    const val RADIUS_PX = 300
    private val BGCA = floatArrayOf(0.10f, 0.18f, 0.85f)

    private fun call(target: Any, name: String, vararg values: Number) {
        val m = target.javaClass.methods.first { it.name == name && it.parameterTypes.size == values.size }
        val args = m.parameterTypes.mapIndexed { i, t ->
            when (t) {
                Float::class.javaPrimitiveType -> values[i].toFloat()
                Int::class.javaPrimitiveType -> values[i].toInt()
                else -> values[i]
            }
        }.toTypedArray()
        m.invoke(target, *args)
    }

    /** Blurs what is behind [view] by [radius] px with [corner] px corners; 0 takes the blur away. */
    fun apply(view: View, radius: Int, corner: Float): Boolean = runCatching {
        if (!available) return false
        if (radius <= 0) {
            set!!.invoke(view, null)
            return true
        }
        val b = builder!!.getConstructor(Int::class.javaPrimitiveType).newInstance(windowMode)
        call(b, "setRadius", radius)
        call(b, "setBackgroundCornerRadius", corner)
        runCatching { call(b, "setBGColorAdjustment", BGCA[0], BGCA[1], BGCA[2]) }
        set!!.invoke(view, builder.getMethod("build").invoke(b))
        true
    }.onFailure { Log.w(GlassWindow.TAG, "Samsung blur failed: ${it.javaClass.simpleName} ${it.cause?.javaClass?.simpleName}") }
        .getOrDefault(false)
}
