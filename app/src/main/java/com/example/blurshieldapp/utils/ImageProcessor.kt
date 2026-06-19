package com.example.blurshieldapp.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import com.google.mlkit.vision.face.Face


enum class FaceEffect { BLUR, PIXELATE, BLACKOUT, EMOJI}

object ImageProcessor {
    fun applyEffect(
        context: Context,
        original: Bitmap,
        boxes: List<RectF>,
        selectedIndices: Set<Int>,
        effect: FaceEffect,
        intensity: Float,
        emoji: String = "😀"
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        selectedIndices.forEach { i ->
                val box = boxes[i]
                val left = box.left.toInt().coerceIn(0, result.width)
                val top = box.top.toInt().coerceIn(0, result.height)
                val right = box.right.toInt().coerceIn(0, result.width)
                val bottom = box.bottom.toInt().coerceIn(0, result.height)
                val w = right - left
                val h = bottom - top
                if (w <= 0 || h <= 0) return@forEach

                when (effect) {
                    FaceEffect.BLUR -> {
                        val crop = Bitmap.createBitmap(result, left, top, w, h)
                        val blurred = blurBitmap(context, crop, intensity.coerceIn(1f, 25f))
                        canvas.drawBitmap(blurred, left.toFloat(), top.toFloat(), null)
                    }

                    FaceEffect.PIXELATE -> {
                        val crop = Bitmap.createBitmap(result, left, top, w, h)
                        val pixelated = pixelateBitmap(crop, intensity.toInt().coerceAtLeast(2))
                        canvas.drawBitmap(pixelated, left.toFloat(), top.toFloat(), null)
                    }

                    FaceEffect.BLACKOUT -> {
                        val paint = Paint().apply { color = Color.BLACK }
                        canvas.drawRect(
                            left.toFloat(),
                            top.toFloat(),
                            right.toFloat(),
                            bottom.toFloat(),
                            paint
                        )
                    }
                    FaceEffect.EMOJI -> {
                        drawEmoji(canvas, emoji, left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                    }
                }
            }
            return result
        }

        private fun blurBitmap(context: Context, bitmap: Bitmap, radius: Float): Bitmap {
            val rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createTyped(rs, input.type)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            script.setRadius(radius)
            script.setInput(input)
            script.forEach(output)
            output.copyTo(bitmap)
            rs.destroy()
            return bitmap
        }

        private fun pixelateBitmap(bitmap: Bitmap, blockSize: Int): Bitmap {
            val small = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width / blockSize).coerceAtLeast(1),
                (bitmap.height / blockSize).coerceAtLeast(1),
                false
            )
            return Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, false)
        }
    private fun drawEmoji(canvas: Canvas, emoji: String, left: Float, top: Float, right: Float, bottom: Float) {
        val boxW = right - left
        val boxH = bottom - top
        val textSize = minOf(boxW, boxH) * 0.9f

        val paint = Paint().apply {
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val centerX = left + boxW / 2f
        val fm = paint.fontMetrics
        val centerY = top + boxH / 2f - (fm.ascent + fm.descent) / 2f

        canvas.drawText(emoji, centerX, centerY, paint)
    }

        fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
            return try {
                val filename = "FaceDetect_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    true
                } ?: false
            } catch (e: Exception) {
                false
            }
        }
    }