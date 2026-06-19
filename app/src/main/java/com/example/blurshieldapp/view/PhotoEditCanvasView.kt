package com.example.blurshieldapp.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.annotation.SuppressLint

class PhotoEditCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val imageView: ImageView
    val overlayView: FaceOverlayView

    private val matrix = Matrix()
    private var bitmap: Bitmap? = null

    private var minScale = 1f
    private val maxScale = 6f
    private var currentScale = 1f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false
    private var activePointerId = -1

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = newScale / currentScale
            currentScale = newScale

            matrix.postScale(factor, factor, detector.focusX, detector.focusY)
            constrainMatrix()
            applyMatrix()
            return true
        }
    })

    init {
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        overlayView = FaceOverlayView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(imageView)
        addView(overlayView)
    }

    fun setImageBitmap(bmp: Bitmap) {
        bitmap = bmp
        imageView.setImageBitmap(bmp)
        post { resetMatrixToFit() }
    }

    private fun resetMatrixToFit() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return

        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        minScale = scale
        currentScale = scale

        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) resetMatrixToFit()
    }

    private fun applyMatrix() {
        imageView.imageMatrix = matrix
        overlayView.updateTransform(matrix)
    }

    private fun constrainMatrix() {
        val bmp = bitmap ?: return
        val values = FloatArray(9)
        matrix.getValues(values)

        var dx = 0f
        var dy = 0f

        val scaledW = bmp.width * values[Matrix.MSCALE_X]
        val scaledH = bmp.height * values[Matrix.MSCALE_Y]

        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]

        dx = when {
            scaledW <= width -> (width - scaledW) / 2f - transX
            transX > 0 -> -transX
            transX < width - scaledW -> (width - scaledW) - transX
            else -> 0f
        }
        dy = when {
            scaledH <= height -> (height - scaledH) / 2f - transY
            transY > 0 -> -transY
            transY < height - scaledH -> (height - scaledH) - transY
            else -> 0f
        }
        matrix.postTranslate(dx, dy)
    }
    fun updateImagePreservingMatrix(bmp: Bitmap) {
        bitmap = bmp
        imageView.setImageBitmap(bmp)
        imageView.imageMatrix = matrix  // re-apply current matrix, don't reset
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // If the touch starts on a handle or a selected box body, let the overlay handle it exclusively
        if (ev.action == MotionEvent.ACTION_DOWN &&
            overlayView.isTouchOnHandleOrSelectedBox(ev.x, ev.y)) {
            return false // don't intercept; overlay's onTouchEvent will fire
        }
        return false // we manage gesture in onTouchEvent of this ViewGroup itself
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // First give the overlay a chance (handles / box drag / tap-select)
        if (overlayView.isTouchOnHandleOrSelectedBox(event.x, event.y) ||
            event.pointerCount > 1) {
            // multi-touch always goes to scale detector below
        } else if (overlayView.dispatchTouchEvent(event)) {
            return true
        }

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                isPanning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val dx = event.getX(pointerIndex) - lastTouchX
                        val dy = event.getY(pointerIndex) - lastTouchY
                        matrix.postTranslate(dx, dy)
                        constrainMatrix()
                        applyMatrix()
                        lastTouchX = event.getX(pointerIndex)
                        lastTouchY = event.getY(pointerIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                activePointerId = -1
            }
        }
        return true
    }
}