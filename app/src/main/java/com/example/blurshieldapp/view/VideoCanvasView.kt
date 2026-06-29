package com.example.blurshieldapp.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView

class VideoEditCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // ── Child views (created in code, same pattern as PhotoEditCanvasView) ─────
    val imageView: ImageView
    val overlayView: FaceOverlayView

    // ── Matrix & scale state ───────────────────────────────────────────────────
    private val matrix = Matrix()
    private var bitmap: Bitmap? = null
    private var minScale = 1f
    private val maxScale = 5f
    private var currentScale = 1f

    // ── Pan state ──────────────────────────────────────────────────────────────
    private var panPointerId = -1
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isScaling = false

    // ── Touch ownership (same pattern as PhotoEditCanvasView) ──────────────────
    private enum class TouchOwner { NONE, OVERLAY, CANVAS }
    private var touchOwner = TouchOwner.NONE

    // ── Zoom enabled gate (off until video is loaded) ──────────────────────────
    private var zoomEnabled = false

    // ── Callback — fragment no longer needs getDrawMatrix() ───────────────────
    var onTransformUpdated: ((Matrix) -> Unit)? = null

    // ── Pinch detector ─────────────────────────────────────────────────────────
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return if (zoomEnabled && touchOwner == TouchOwner.CANVAS) {
                    isScaling = true
                    true
                } else false
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = newScale / currentScale
                currentScale = newScale
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrainMatrix()
                applyMatrix()
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

    // ── Init ───────────────────────────────────────────────────────────────────
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

    // ── Public API ─────────────────────────────────────────────────────────────

    fun setZoomEnabled(enabled: Boolean) {
        zoomEnabled = enabled
    }

    fun resetZoom() {
        bitmap?.let {
            resetMatrixToFit(it)
        }
    }

    /** Called every frame during playback or seek */
    fun drawFrame(bitmap: Bitmap) {
        this.bitmap = bitmap
        imageView.setImageBitmap(bitmap)
        // Only reset matrix on first frame or if bitmap size changed
        if (currentScale == 1f) {
            post { resetMatrixToFit(bitmap) }
        } else {
            // Preserve zoom/pan — just update image and reapply matrix
            imageView.imageMatrix = matrix
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun resetMatrixToFit(bmp: Bitmap) {
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
        bitmap?.let { if (currentScale == 1f) resetMatrixToFit(it) }
    }

    private fun applyMatrix() {
        imageView.imageMatrix = matrix
        overlayView.updateTransform(matrix)
        // Notify fragment — overlay already updated above, this is for any
        // other consumers (e.g. future minimap, export bounds)
        onTransformUpdated?.invoke(Matrix(matrix))
    }

    private fun constrainMatrix() {
        val bmp = bitmap ?: return
        val values = FloatArray(9)
        matrix.getValues(values)
        val scaledW = bmp.width  * values[Matrix.MSCALE_X]
        val scaledH = bmp.height * values[Matrix.MSCALE_Y]
        val transX  = values[Matrix.MTRANS_X]
        val transY  = values[Matrix.MTRANS_Y]
        val dx = when {
            scaledW <= width          -> (width  - scaledW) / 2f - transX
            transX > 0               -> -transX
            transX < width - scaledW -> (width  - scaledW) - transX
            else -> 0f
        }
        val dy = when {
            scaledH <= height          -> (height - scaledH) / 2f - transY
            transY > 0                -> -transY
            transY < height - scaledH -> (height - scaledH) - transY
            else -> 0f
        }
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    // ── Touch handling (mirrors PhotoEditCanvasView.handleNormalTouch) ─────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOwner = TouchOwner.NONE

            // If overlay wants this touch (handle or selected box) — don't intercept
            if (overlayView.isTouchOnHandleOrSelectedBox(ev.x, ev.y)
                || overlayView.isPointInsideAnyBox(ev.x, ev.y)) {
                touchOwner = TouchOwner.OVERLAY
                return false
            }

            // Canvas owns everything else (pan, zoom, tap on empty space)
            touchOwner = TouchOwner.CANVAS
            return true
        }
        return touchOwner == TouchOwner.CANVAS
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Prevent NestedScrollView from stealing 2-finger or active pan gestures
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || isScaling) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panPointerId = event.getPointerId(0)
                lastPanX = event.x
                lastPanY = event.y
                // Forward tap-down to overlay for selection
                overlayView.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isScaling = false
                panPointerId = event.getPointerId(0)
                lastPanX = event.getX(0)
                lastPanY = event.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling || !zoomEnabled) return true
                if (event.pointerCount == 1) {
                    val idx = event.findPointerIndex(panPointerId)
                    if (idx != -1) {
                        // Let overlay try first (box drag on selected box)
                        if (overlayView.dispatchTouchEvent(event)) return true
                        // Otherwise pan
                        if (currentScale > minScale) {
                            val dx = event.getX(idx) - lastPanX
                            val dy = event.getY(idx) - lastPanY
                            matrix.postTranslate(dx, dy)
                            constrainMatrix()
                            applyMatrix()
                        }
                        lastPanX = event.getX(idx)
                        lastPanY = event.getY(idx)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                val liftedId = event.getPointerId(liftedIndex)
                if (liftedId == panPointerId) {
                    val newIndex = if (liftedIndex == 0) 1 else 0
                    panPointerId = event.getPointerId(newIndex)
                    lastPanX = event.getX(newIndex)
                    lastPanY = event.getY(newIndex)
                }
                if (event.pointerCount <= 2) isScaling = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                overlayView.dispatchTouchEvent(event)
                panPointerId = -1
                isScaling = false
                touchOwner = TouchOwner.NONE
            }
        }
        return true
    }
}