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
    private var emojiStampBitmap: Bitmap? = null
    private var emojiStampCanvas: Canvas? = null

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

    var onMaskStrokeFinished: ((Bitmap) -> Unit)? = null
    var onEmojiStrokeFinished: ((Bitmap) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastStampX = 0f
    private var lastStampY = 0f

    fun initLayers(width: Int, height: Int, existingMask: Bitmap?, existingStamp: Bitmap?) {
        bitmapW = width
        bitmapH = height

        maskBitmap = existingMask?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(maskBitmap!!)

        emojiStampBitmap = existingStamp?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        emojiStampCanvas = Canvas(emojiStampBitmap!!)

        isInitialized = true
        invalidate()
    }

    fun restoreLayers(restoredMask: Bitmap?, restoredStamp: Bitmap?) {
        if (!isInitialized) return

        maskBitmap?.let { dest ->
            Canvas(dest).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            restoredMask?.let { src -> Canvas(dest).drawBitmap(src, 0f, 0f, null) }
        }

        emojiStampBitmap?.let { dest ->
            Canvas(dest).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            restoredStamp?.let { src -> Canvas(dest).drawBitmap(src, 0f, 0f, null) }
        }
        invalidate()
    }

    fun getMaskBitmap(): Bitmap? = maskBitmap
    fun getEmojiStampBitmap(): Bitmap? = emojiStampBitmap

    fun clearAll() {
        maskBitmap?.eraseColor(Color.TRANSPARENT)
        emojiStampBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
        maskBitmap?.let { onMaskStrokeFinished?.invoke(it) }
        emojiStampBitmap?.let { onEmojiStrokeFinished?.invoke(it) }
    }

    fun updateTransform(matrix: Matrix) {
        transformMatrix = Matrix(matrix)
        transformMatrix.invert(inverseMatrix)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(transformMatrix)
        if (currentEffect != FaceEffect.EMOJI) {
            maskBitmap?.let { canvas.drawBitmap(it, 0f, 0f, maskPreviewPaint) }
        }
        emojiStampBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.restore()
    }

    fun handlePaintTouch(screenX: Float, screenY: Float, action: Int): Boolean {
        val pt = screenPointToBitmap(screenX, screenY)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                lastX = pt.x; lastY = pt.y

                // ALWAYS draw into the generic mask stencil layer, even for Emojis!
                drawMaskPointOrLine(pt.x, pt.y, isDown = true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return false
                drawMaskPointOrLine(pt.x, pt.y, isDown = false)
                lastX = pt.x; lastY = pt.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    isDrawing = false
                    maskBitmap?.let { bmp -> onMaskStrokeFinished?.invoke(bmp.copy(bmp.config, true)) }
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

    private fun paintEmojiTrail(x: Float, y: Float, isFirstPoint: Boolean) {
        val ec = emojiStampCanvas ?: return

        if (currentTool == BrushTool.ERASE) {
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            maskPaint.style = Paint.Style.FILL
            ec.drawCircle(x, y, brushRadiusBitmapPx, maskPaint)
            return
        }

        if (isFirstPoint) {
            stampEmoji(ec, x, y)
            return
        }

        val spacing = brushRadiusBitmapPx * 0.8f
        val dx = x - lastStampX
        val dy = y - lastStampY
        val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (distance >= spacing) {
            val steps = (distance / spacing).toInt()
            for (i in 1..steps) {
                val t = (spacing * i) / distance
                stampEmoji(ec, lastStampX + dx * t, lastStampY + dy * t)
            }
            lastStampX = x; lastStampY = y
        }
    }

    private fun stampEmoji(canvas: Canvas, x: Float, y: Float) {
        val size = brushRadiusBitmapPx * 2f
        val paint = Paint().apply {
            textSize = size
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val fm = paint.fontMetrics
        canvas.drawText(currentEmoji, x, y - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun screenPointToBitmap(x: Float, y: Float): PointF {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }
}