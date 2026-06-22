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

class PhotoEditCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    lateinit var imageView: ImageView
    lateinit var overlayView: FaceOverlayView
    lateinit var brushView: BrushMaskView

    private val matrix = Matrix()
    private var bitmap: Bitmap? = null

    private var minScale = 1f
    private val maxScale = 6f
    private var currentScale = 1f

    private var isBrushEnabled = false
    private var brushLayersReady = false

    // ── Pan state ──
    private var panPointerId = -1
    private var lastPanX = 0f
    private var lastPanY = 0f

    // ── Scale state ──
    private var isScaling = false

    // ── Tracks whether we've committed this touch sequence to a specific handler ──
    private enum class TouchOwner { NONE, OVERLAY, CANVAS }
    private var touchOwner = TouchOwner.NONE

    private val scaleDetector: ScaleGestureDetector

    init {
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        overlayView = FaceOverlayView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        brushView = BrushMaskView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(imageView)
        addView(overlayView)
        addView(brushView)

        scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    if (isBrushEnabled) {
                        brushView.handlePaintTouch(0f, 0f, MotionEvent.ACTION_CANCEL)
                    }
                    return true
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
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────────

    fun setImageBitmap(bmp: Bitmap) {
        bitmap = bmp
        brushLayersReady = false
        imageView.setImageBitmap(bmp)
        post {
            resetMatrixToFit()
            brushView.initLayers(bmp.width, bmp.height, null, null)
            brushLayersReady = true
        }
    }

    fun setBrushEnabled(enabled: Boolean) {
        isBrushEnabled = enabled
    }

    fun restoreBrushLayers(restoredMask: Bitmap?, restoredStamp: Bitmap?) {
        brushView.restoreLayers(restoredMask, restoredStamp)
    }

    fun getLiveMask(): Bitmap? = brushView.getMaskBitmap()
    fun getLiveEmojiStamp(): Bitmap? = brushView.getEmojiStampBitmap()

    fun updateImagePreservingMatrix(bmp: Bitmap) {
        bitmap = bmp
        imageView.setImageBitmap(bmp)
        imageView.imageMatrix = matrix
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Matrix / layout
    // ────────────────────────────────────────────────────────────────────────────

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
        brushView.updateTransform(matrix)
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
            scaledW <= width             -> (width  - scaledW) / 2f - transX
            transX > 0                   -> -transX
            transX < width  - scaledW   -> (width  - scaledW) - transX
            else -> 0f
        }
        val dy = when {
            scaledH <= height            -> (height - scaledH) / 2f - transY
            transY > 0                   -> -transY
            transY < height - scaledH   -> (height - scaledH) - transY
            else -> 0f
        }
        matrix.postTranslate(dx, dy)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Touch interception — decide ONCE on ACTION_DOWN who owns this gesture
    // ────────────────────────────────────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOwner = TouchOwner.NONE

            if (isBrushEnabled) {
                // Brush mode: canvas owns all single-finger touches immediately
                touchOwner = TouchOwner.CANVAS
                return true  // intercept so onTouchEvent gets the full stream
            }

            // Normal mode: if touch is on a handle or selected box, let overlay own it
            if (overlayView.isTouchOnHandleOrSelectedBox(ev.x, ev.y)) {
                touchOwner = TouchOwner.OVERLAY
                return false  // don't intercept — overlay's onTouchEvent fires directly
            }

            // Otherwise canvas owns it (pan/zoom or tap-to-select via overlay dispatch)
            touchOwner = TouchOwner.CANVAS
            return true
        }

        // For non-DOWN events: intercept if canvas owns this gesture
        return touchOwner == TouchOwner.CANVAS
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (isBrushEnabled) handleBrushTouch(event) else handleNormalTouch(event)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Normal mode touch handling
    // ────────────────────────────────────────────────────────────────────────────

    private fun handleNormalTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panPointerId = event.getPointerId(0)
                lastPanX = event.x
                lastPanY = event.y
                overlayView.dispatchTouchEvent(event)  // ← keep only here
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isScaling = false
                panPointerId = event.getPointerId(0)
                lastPanX = event.getX(0)
                lastPanY = event.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling) return true
                if (event.pointerCount == 1) {
                    val idx = event.findPointerIndex(panPointerId)
                    if (idx != -1) {
                        if (overlayView.dispatchTouchEvent(event)) return true
                        val dx = event.getX(idx) - lastPanX
                        val dy = event.getY(idx) - lastPanY
                        matrix.postTranslate(dx, dy)
                        constrainMatrix()
                        applyMatrix()
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
                overlayView.dispatchTouchEvent(event)  // ← single dispatch on UP
                panPointerId = -1
                isScaling = false
                touchOwner = TouchOwner.NONE
            }
        }
        return true
    }
    // ────────────────────────────────────────────────────────────────────────────
    // Brush mode touch handling
    // ────────────────────────────────────────────────────────────────────────────

    private fun handleBrushTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panPointerId = -1  // -1 means brush is active
                brushView.handlePaintTouch(event.x, event.y, MotionEvent.ACTION_DOWN)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger arrived — cancel brush, switch to pan
                brushView.handlePaintTouch(0f, 0f, MotionEvent.ACTION_CANCEL)
                panPointerId = event.getPointerId(0)
                lastPanX = event.getX(0)
                lastPanY = event.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling) return true
                if (event.pointerCount == 1 && panPointerId == -1) {
                    // Single finger — brush stroke
                    brushView.handlePaintTouch(event.x, event.y, MotionEvent.ACTION_MOVE)
                } else if (event.pointerCount >= 2 && panPointerId != -1) {
                    // Multi finger — pan
                    val idx = event.findPointerIndex(panPointerId)
                    if (idx != -1) {
                        val dx = event.getX(idx) - lastPanX
                        val dy = event.getY(idx) - lastPanY
                        matrix.postTranslate(dx, dy)
                        constrainMatrix()
                        applyMatrix()
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
                if (panPointerId == -1) {
                    // Was a brush stroke — notify finish
                    brushView.handlePaintTouch(event.x, event.y, event.actionMasked)
                }
                panPointerId = -1
                isScaling = false
                touchOwner = TouchOwner.NONE
            }
        }
        return true
    }
}