package com.mattintech.papercase.locker.ui.pages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** Image that fits the screen and can be pinched, double-tapped and dragged to read small print. */
class ZoomableImageView(context: Context) : AppCompatImageView(context) {

    private val drawMatrix = Matrix()
    private val bounds = RectF()

    /** Zoom relative to fitting the whole image on screen. */
    private var zoom = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            drawMatrix.postTranslate(-dx, -dy)
            settle()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) fit() else zoomBy(DOUBLE_TAP_ZOOM / zoom, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val s = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate((width - d.intrinsicWidth * s) / 2f, (height - d.intrinsicHeight * s) / 2f)
        zoom = 1f
        imageMatrix = drawMatrix
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val target = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val f = target / zoom
        zoom = target
        drawMatrix.postScale(f, f, focusX, focusY)
        settle()
    }

    /** Keeps the image on screen: centred when smaller than the view, edges pinned when larger. */
    private fun settle() {
        val d = drawable ?: return
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> (width - bounds.width()) / 2f - bounds.left
            bounds.left > 0 -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> (height - bounds.height()) / 2f - bounds.top
            bounds.top > 0 -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // While zoomed (or pinching), drags pan the page instead of swiping to the next one
        parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f || event.pointerCount > 1)
        return true
    }

    private companion object {
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
