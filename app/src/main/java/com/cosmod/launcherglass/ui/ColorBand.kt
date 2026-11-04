package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/** Gradient and thumb are drawn directly; launcher settings are committed on release. */
class ColorBand(context: Context, val component: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var shader: Shader? = null
    private val grayFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    var monochrome = false
        set(value) { field = value; invalidate() }
    private var hsv = floatArrayOf(30f, .25f, .3f)
    var onChange: ((Int, Boolean) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var canceled = false
    private var lastTick = 0L
    private val maximum get() = if (component == 0) 360 else 100
    private val value get() = (hsv[component] * if (component == 0) 1f else 100f).roundToInt()

    init { isFocusable = true; isClickable = true; minimumHeight = (56 * density).roundToInt() }
    fun setColor(hue: Int, saturation: Int, brightness: Int) {
        hsv = floatArrayOf(hue.coerceIn(0,360).toFloat(), saturation.coerceIn(0,100) / 100f, brightness.coerceIn(0,100) / 100f)
        updateShader(); invalidate()
    }
    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((240 * density).roundToInt(), w), resolveSize(minimumHeight, h))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = 20 * density
        track.set(inset, height / 2f - 16 * density, width - inset, height / 2f + 16 * density)
        updateShader()
    }
    private fun updateShader() {
        if (track.width() <= 0) return
        val colors = when (component) {
            0 -> IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            1 -> intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
            else -> intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        }
        shader = LinearGradient(track.left, 0f, track.right, 0f, colors, null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        paint.colorFilter = if(monochrome) grayFilter else null
        paint.shader = shader; paint.style = Paint.Style.FILL
        canvas.drawRoundRect(track, 16 * density, 16 * density, paint)
        paint.shader = null
        val x = track.left + track.width() * value / maximum
        val y = height / 2f
        paint.color = Color.HSVToColor(hsv)
        canvas.drawCircle(x, y, 16 * density, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * density; paint.color = 0x55000000
        canvas.drawCircle(x, y, 16 * density, paint)
        paint.strokeWidth = 2.5f * density; paint.color = Color.WHITE
        canvas.drawCircle(x, y, 16 * density, paint)
        if (isFocused) { paint.strokeWidth = density; canvas.drawCircle(x, y, 20 * density, paint) }
    }
    private fun changeTo(next: Int, commit: Boolean) {
        val bounded = next.coerceIn(0, maximum)
        val changed = bounded != value
        hsv[component] = bounded / if (component == 0) 1f else 100f
        if (changed && SystemClock.uptimeMillis() - lastTick > 35) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); lastTick = SystemClock.uptimeMillis()
        }
        if (changed || commit) onChange?.invoke(bounded, commit)
        invalidate()
    }
    private fun fromX(x: Float) = ((x - track.left) / track.width().coerceAtLeast(1f) * maximum).roundToInt()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragging = false; canceled = false; isPressed = true }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX); val dy = abs(event.y - downY)
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (!dragging && dy > slop && dy > dx) { canceled = true; isPressed = false; return false }
                if (!canceled && (dragging || dx > slop)) {
                    dragging = true; parent.requestDisallowInterceptTouchEvent(true); changeTo(fromX(event.x), false)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!canceled) { changeTo(fromX(event.x), true); performClick() }
                isPressed = false; dragging = false; parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; dragging = false; parent.requestDisallowInterceptTouchEvent(false); onChange?.invoke(value, true) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            changeTo(value + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1, true); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, maximum.toFloat(), value.toFloat())
        info.stateDescription = "$value${if(component == 0) "°" else "%"}"
        if (isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled) when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                if (arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true) return false
                changeTo(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE).roundToInt(), true); return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { changeTo(value + 1, true); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { changeTo(value - 1, true); return true }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
