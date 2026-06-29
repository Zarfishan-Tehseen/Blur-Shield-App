package com.example.blurshieldapp.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView

class VideoCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawMatrix = Matrix()

    var onTransformUpdated: ((Matrix) -> Unit)? = null

    init {
        scaleType = ScaleType.FIT_CENTER
        isClickable = false
        isFocusable = false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    fun drawFrame(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        post {
            computeAndNotifyMatrix(bitmap)
        }
    }
    private fun computeAndNotifyMatrix(bitmap: Bitmap) {
        if (width == 0 || height == 0) return

        val scaleX = width.toFloat() / bitmap.width
        val scaleY = height.toFloat() / bitmap.height
        val scale = minOf(scaleX, scaleY)

        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f

        drawMatrix.apply {
            reset()
            setScale(scale, scale)
            postTranslate(dx, dy)
        }

        onTransformUpdated?.invoke(Matrix(drawMatrix))
    }
    fun getDrawMatrix(): Matrix = Matrix(drawMatrix)
}