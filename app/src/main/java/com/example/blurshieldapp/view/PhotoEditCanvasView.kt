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
import com.example.blurshieldapp.ui.edit.EditViewModel

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

    // Pan state
    private var panPointerId = -1
    private var lastPanX = 0f
    private var lastPanY = 0f

    // Scale state
    private var isScaling = false

    // Tracks whether we've committed this touch sequence to a specific handler
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
                    // CRITICAL FIX: Only allow scaling if there are actually 2 or more fingers on the screen
                    // This stops single-finger brush strokes from accidentally triggering a zoom cancel!
                    return if (touchOwner == TouchOwner.CANVAS && !isBrushEnabled) {
                        isScaling = true
                        true
                    } else {
                        false
                    }
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

    //for home fragment
    fun setImageBitmap(bmp: Bitmap) {
        bitmap = bmp
        brushLayersReady = false
        imageView.setImageBitmap(bmp)
        post {
            resetMatrixToFit()
            // Initialize with default empty layer setups
            brushView.initLayers(bmp.width, bmp.height, null, null)
            brushLayersReady = true
        }
    }
    //for edit fragment
    fun setImageBitmap(bmp: Bitmap, viewModel: EditViewModel) {
        bitmap = bmp
        brushLayersReady = false
        imageView.setImageBitmap(bmp)
        post {
            resetMatrixToFit()

            // Initialize layers
            brushView.initLayers(bmp.width, bmp.height, null, null)

            // Link up the callback lambda with the correct types
            brushView.onMaskStrokeFinished = { mask: Bitmap, pathPoints: List<android.graphics.PointF> ->
                viewModel.onMaskStrokeFinished(mask, pathPoints)
            }

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
    fun updateImagePreservingMatrix(bmp: Bitmap) {
        bitmap = bmp
        imageView.setImageBitmap(bmp)
        imageView.imageMatrix = matrix
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
        if (isBrushEnabled) {
            // CRITICAL FIX: Tell the Parent ScrollView/Layout not to steal touches while drawing!
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return handleBrushTouch(event)
        } else {
            // Also prevent scrolling while actively zooming/panning the image canvas
            if (event.pointerCount > 1 || isScaling) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return handleNormalTouch(event)
        }
    }

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
                overlayView.dispatchTouchEvent(event)
                panPointerId = -1
                isScaling = false
                touchOwner = TouchOwner.NONE
            }
        }
        return true
    }
    private fun handleBrushTouch(event: MotionEvent): Boolean {
        // Only pass to scale detector if there is more than 1 finger down
        if (event.pointerCount > 1) {
            scaleDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panPointerId = -1  // -1 explicitly marks an active brush stroke
                brushView.handlePaintTouch(event.x, event.y, MotionEvent.ACTION_DOWN)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger arrived: cleanly cancel the brush stroke and transition to pan/zoom
                brushView.handlePaintTouch(0f, 0f, MotionEvent.ACTION_CANCEL)
                panPointerId = event.getPointerId(event.actionIndex)
                lastPanX = event.getX(event.actionIndex)
                lastPanY = event.getY(event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling) return true

                if (event.pointerCount == 1 && panPointerId == -1) {
                    // Single finger tracking: draw smoothly
                    brushView.handlePaintTouch(event.x, event.y, MotionEvent.ACTION_MOVE)
                } else if (event.pointerCount >= 2 && panPointerId != -1) {
                    // Multi-finger tracking: pan the canvas around safely
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
                    brushView.handlePaintTouch(event.x, event.y, event.actionMasked)
                }
                panPointerId = -1
                isScaling = false
                touchOwner = TouchOwner.NONE
            }
        }
        return true
    }}