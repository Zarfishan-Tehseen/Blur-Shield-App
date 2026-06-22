package com.example.blurshieldapp.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.blurshieldapp.utils.FaceEffect

enum class BrushTool { PAINT, ERASE }

class BrushMaskView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null

    private var bitmapW = 0
    private var bitmapH = 0
    private var isInitialized = false

    private var transformMatrix = Matrix()
    private val inverseMatrix = Matrix()

    var brushRadiusBitmapPx = 60f
    var currentTool = BrushTool.PAINT
    var currentEffect = FaceEffect.BLUR
    var currentEmoji = "😀"

    // Read-only externally, tracked internally for performance throttling
    var isDrawing = false
        private set

    // Unified stroke paths
    private val maskPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val maskPreviewPaint = Paint().apply {
        colorFilter = PorterDuffColorFilter(Color.argb(90, 0, 0, 0), PorterDuff.Mode.SRC_IN)
    }

    // Single unified callback passing BOTH the stencil mask and the path coordinates
    var onMaskStrokeFinished: ((Bitmap, List<PointF>) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f

    private val currentStrokePoints = mutableListOf<PointF>()

    fun initLayers(width: Int, height: Int, existingMask: Bitmap?, existingStamp: Bitmap?) {
        bitmapW = width
        bitmapH = height

        maskBitmap = existingMask?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(maskBitmap!!)

        isInitialized = true
        invalidate()
    }

    fun restoreLayers(restoredMask: Bitmap?, restoredStamp: Bitmap?) {
        if (!isInitialized) return

        maskBitmap?.let { dest ->
            Canvas(dest).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            restoredMask?.let { src -> Canvas(dest).drawBitmap(src, 0f, 0f, null) }
        }
        invalidate()
    }

    fun getMaskBitmap(): Bitmap? = maskBitmap

    fun updateTransform(matrix: Matrix) {
        transformMatrix = Matrix(matrix)
        transformMatrix.invert(inverseMatrix)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(transformMatrix)

        // Show the mask preview stencil on screen unless we are using the emoji tool
        if (currentEffect != FaceEffect.EMOJI) {
            maskBitmap?.let { canvas.drawBitmap(it, 0f, 0f, maskPreviewPaint) }
        }

        canvas.restore()
    }

    fun handlePaintTouch(screenX: Float, screenY: Float, action: Int): Boolean {
        val pt = screenPointToBitmap(screenX, screenY)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                lastX = pt.x; lastY = pt.y

                currentStrokePoints.clear()
                currentStrokePoints.add(PointF(pt.x, pt.y))

                drawMaskPointOrLine(pt.x, pt.y, isDown = true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return false

                currentStrokePoints.add(PointF(pt.x, pt.y))

                drawMaskPointOrLine(pt.x, pt.y, isDown = false)
                lastX = pt.x; lastY = pt.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    isDrawing = false
                    maskBitmap?.let { bmp ->
                        // Safely pass data copies upwards to the handlers
                        onMaskStrokeFinished?.invoke(
                            bmp.copy(bmp.config, true),
                            currentStrokePoints.toList()
                        )
                    }
                }
                return true
            }
        }
        return false
    }

    private fun drawMaskPointOrLine(x: Float, y: Float, isDown: Boolean) {
        val mc = maskCanvas ?: return
        maskPaint.strokeWidth = brushRadiusBitmapPx * 2f
        maskPaint.xfermode = PorterDuffXfermode(
            if (currentTool == BrushTool.ERASE) PorterDuff.Mode.CLEAR else PorterDuff.Mode.SRC
        )

        if (isDown) {
            maskPaint.style = Paint.Style.FILL
            mc.drawCircle(x, y, brushRadiusBitmapPx, maskPaint)
            maskPaint.style = Paint.Style.STROKE
        } else {
            mc.drawLine(lastX, lastY, x, y, maskPaint)
        }
    }

    private fun screenPointToBitmap(x: Float, y: Float): PointF {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }
}