package com.mattintech.papercase.locker.ui.crop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shows a photo with four draggable corners. [crop] straightens the area inside the corners into a
 * flat rectangle (perspective correction), so a document photographed at an angle comes out square.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null

    /** Corners in bitmap coordinates: top-left, top-right, bottom-right, bottom-left. */
    private val corners = Array(4) { PointF() }

    private val imageRect = RectF()
    private var scale = 1f
    private var dragging = -1

    private val density = resources.displayMetrics.density
    private val handleRadius = 12 * density
    private val touchSlop = 40 * density

    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B57D0")
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setBitmap(b: Bitmap) {
        bitmap = b
        resetCorners()
        layoutImage()
        invalidate()
    }

    /** Corners at the photo's edges, so Done without adjusting keeps the whole photo. */
    fun resetCorners() {
        val b = bitmap ?: return
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        corners[0].set(0f, 0f)
        corners[1].set(w, 0f)
        corners[2].set(w, h)
        corners[3].set(0f, h)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutImage()

    private fun layoutImage() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = handleRadius * 2
        scale = minOf((width - pad * 2) / b.width, (height - pad * 2) / b.height)
        val w = b.width * scale
        val h = b.height * scale
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun toView(p: PointF) = PointF(imageRect.left + p.x * scale, imageRect.top + p.y * scale)

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, imageRect, bitmapPaint)

        val pts = corners.map(::toView)
        val shape = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) lineTo(pts[i].x, pts[i].y)
            close()
        }
        // Dim everything outside the selected area
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(imageRect, Path.Direction.CW)
            addPath(shape)
        }
        canvas.drawPath(outside, dimPaint)
        canvas.drawPath(shape, linePaint)
        pts.forEach {
            canvas.drawCircle(it.x, it.y, handleRadius, handlePaint)
            canvas.drawCircle(it.x, it.y, handleRadius, handleRing)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = corners.indices.minByOrNull { i ->
                    val p = toView(corners[i]); hypot(p.x - event.x, p.y - event.y)
                }?.takeIf { i ->
                    val p = toView(corners[i]); hypot(p.x - event.x, p.y - event.y) <= touchSlop
                } ?: -1
                if (dragging >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                return dragging >= 0
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                val x = ((event.x - imageRect.left) / scale).coerceIn(0f, b.width.toFloat())
                val y = ((event.y - imageRect.top) / scale).coerceIn(0f, b.height.toFloat())
                corners[dragging].set(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return super.onTouchEvent(event)
    }

    /** The area inside the corners, straightened into a rectangle. */
    fun crop(): Bitmap? {
        val b = bitmap ?: return null
        val (tl, tr, br, bl) = corners
        var w = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y))
        var h = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y))
        if (w < 10 || h < 10) return null
        // Keep the output a sensible size
        val limit = MAX_OUTPUT / max(w, h)
        if (limit < 1f) { w *= limit; h *= limit }

        val src = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val dst = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null

        val out = Bitmap.createBitmap(w.roundToInt(), h.roundToInt(), Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(b, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    private companion object {
        const val MAX_OUTPUT = 3000f
    }
}
