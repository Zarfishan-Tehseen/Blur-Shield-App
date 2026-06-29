package com.example.blurshieldapp.utils

import android.graphics.*
import com.example.blurshieldapp.data.model.FaceEffect
import kotlin.math.roundToInt

object EffectProcessor {
    private val emojiPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    fun applyEffects(
        source: Bitmap,
        faces: List<Pair<RectF, FaceEffect>>,
        intensity: Float = 15f,
        emoji: String = "😶"
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        faces.forEach { (rect, effect) ->
            when (effect) {
                FaceEffect.NONE -> { }
                FaceEffect.BLUR -> applyBlur(canvas, result, rect, intensity)
                FaceEffect.PIXELATE -> applyPixelate(canvas, result, rect, intensity)
                FaceEffect.BLACKOUT -> applyBlackout(canvas, rect, intensity)
                FaceEffect.EMOJI -> applyEmoji(canvas, rect, emoji)
            }
        }

        return result
    }

    // -------------------------------------------------------------------------
    // BLUR
    // Crops the face region, applies a stack blur, draws it back
    // -------------------------------------------------------------------------
    private fun applyBlur(canvas: Canvas, bitmap: Bitmap, rect: RectF, intensity: Float) {
        val safeRect = clampRect(rect, bitmap.width, bitmap.height) ?: return

        val cropW = safeRect.width().toInt()
        val cropH = safeRect.height().toInt()
        if (cropW < 10 || cropH < 10) {
            applyBlackout(canvas, safeRect, intensity)
            return
        }
        val faceCrop = Bitmap.createBitmap(
            bitmap,
            safeRect.left.toInt(),
            safeRect.top.toInt(),
            safeRect.width().toInt(),
            safeRect.height().toInt()
        )

        val safeRadius = minOf(intensity.toInt(), cropW / 2, cropH / 2).coerceAtLeast(1)
        val blurred = stackBlur(faceCrop, radius = safeRadius)
        canvas.drawBitmap(blurred, null, safeRect, null)

        faceCrop.recycle()
        blurred.recycle()
    }

    // -------------------------------------------------------------------------
    // PIXELATE
    // Downscale to tiny size then upscale back = pixelated look
    // -------------------------------------------------------------------------
    private fun applyPixelate(canvas: Canvas, bitmap: Bitmap, rect: RectF, intensity: Float) {
        val safeRect = clampRect(rect, bitmap.width, bitmap.height) ?: return
        val blockSize = intensity.toInt().coerceAtLeast(2)

        val w = safeRect.width().toInt()
        val h = safeRect.height().toInt()

        val faceCrop = Bitmap.createBitmap(bitmap, safeRect.left.toInt(), safeRect.top.toInt(), w, h)

        // Downscale
        val smallW = (w / blockSize).coerceAtLeast(1)
        val smallH = (h / blockSize).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(faceCrop, smallW, smallH, false)

        // Upscale back (no filter = hard pixel edges)
        val pixelated = Bitmap.createScaledBitmap(small, w, h, false)

        canvas.drawBitmap(pixelated, null, safeRect, null)

        faceCrop.recycle()
        small.recycle()
        pixelated.recycle()
    }
    private fun applyBlackout(canvas: Canvas, rect: RectF, intensity: Float) {
        val alpha = ((intensity / 25f) * 255f).toInt().coerceIn(10, 255)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            this.alpha = alpha
        }
        canvas.drawRect(rect, paint)
    }

    private fun applyEmoji(canvas: Canvas, rect: RectF, emoji: String) {
        emojiPaint.textSize = minOf(rect.width(), rect.height()) * 0.85f
        val x = rect.centerX()
        val y = rect.centerY() - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
        canvas.drawText(emoji, x, y, emojiPaint)
    }
    private fun stackBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 100)
        val w = bitmap.width
        val h = bitmap.height

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Helper to get clamped pixel
        fun pixel(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, w - 1)
            val cy = y.coerceIn(0, h - 1)
            return pixels[cy * w + cx]
        }

        val temp = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0
                val count = 2 * r + 1
                for (k in -r..r) {
                    val p = pixel(x + k, y)
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }
                temp[y * w + x] = (0xFF shl 24) or
                        ((rSum / count) shl 16) or
                        ((gSum / count) shl 8) or
                        (bSum / count)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var rSum = 0; var gSum = 0; var bSum = 0
                val count = 2 * r + 1
                for (k in -r..r) {
                    val cy = (y + k).coerceIn(0, h - 1)
                    val p = temp[cy * w + x]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }
                pixels[y * w + x] = (0xFF shl 24) or
                        ((rSum / count) shl 16) or
                        ((gSum / count) shl 8) or
                        (bSum / count)
            }
        }

        val result = Bitmap.createBitmap(w, h, bitmap.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun clampRect(rect: RectF, bitmapW: Int, bitmapH: Int): RectF? {
        val clamped = RectF(
            rect.left.coerceIn(0f, bitmapW.toFloat()),
            rect.top.coerceIn(0f, bitmapH.toFloat()),
            rect.right.coerceIn(0f, bitmapW.toFloat()),
            rect.bottom.coerceIn(0f, bitmapH.toFloat())
        )
        return if (clamped.width() > 0 && clamped.height() > 0) clamped else null
    }
}