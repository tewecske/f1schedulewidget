package dev.f1schedule

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** Saturation/brightness square with a hue strip below it. Reports opaque RGB colours to [onPick]. */
class ColorPicker(context: Context) : View(context) {
    var onPick: (Int) -> Unit = {}

    private val hsv = floatArrayOf(0f, 0f, 0f)
    private val density = resources.displayMetrics.density
    private val gap = 12 * density
    private val hueHeight = 24 * density
    private val radius = 8 * density
    private val inset = 10 * density // room for the markers at the edges
    private val square = RectF()
    private val hue = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private var dragging: RectF? = null

    /** Shows [rgb] without reporting it. Greys keep the current hue so the square doesn't jump. */
    fun setColor(rgb: Int) {
        val h = hsv[0]
        Color.colorToHSV(rgb, hsv)
        if (hsv[1] == 0f || hsv[2] == 0f) hsv[0] = h
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (inset + 160 * density + gap + hueHeight).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        square.set(inset, inset, w - inset, h - gap - hueHeight)
        hue.set(inset, h - hueHeight, w - inset, h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        fill.shader = LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(square, radius, radius, fill)
        fill.shader = LinearGradient(0f, square.top, 0f, square.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(square, radius, radius, fill)
        fill.shader = LinearGradient(hue.left, 0f, hue.right, 0f, IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hue, hueHeight / 2, hueHeight / 2, fill)
        fill.shader = null

        // Markers: white ring with a dark edge so they show on any colour.
        marker(canvas, square.left + hsv[1] * square.width(), square.top + (1 - hsv[2]) * square.height())
        marker(canvas, hue.left + hsv[0] / 360f * hue.width(), hue.centerY())
    }

    private fun marker(canvas: Canvas, x: Float, y: Float) {
        val r = 9 * density
        ring.color = 0x80000000.toInt(); canvas.drawCircle(x, y, r + density, ring)
        ring.color = Color.WHITE; canvas.drawCircle(x, y, r, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (e.y <= square.bottom + gap / 2) square else hue
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                return true
            }
        }
        val x = ((e.x - square.left) / square.width()).coerceIn(0f, 1f)
        when {
            dragging === square -> {
                hsv[1] = x
                hsv[2] = 1 - ((e.y - square.top) / square.height()).coerceIn(0f, 1f)
            }
            dragging === hue -> hsv[0] = (x * 360f).coerceAtMost(359.9f)
            else -> return false
        }
        invalidate()
        onPick(Color.HSVToColor(hsv) and 0xFFFFFF)
        return true
    }
}
