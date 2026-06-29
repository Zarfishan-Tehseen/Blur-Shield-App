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

enum class FaceEffect { BLUR, PIXELATE, BLACKOUT, EMOJI }

@Suppress("DEPRECATION")
object ImageProcessor {
    private fun Bitmap.isHardwareBitmap(): Boolean =
        Build.VERSION.SDK_INT >= 26 && config == Bitmap.Config.HARDWARE

    private fun Bitmap.toSoftware(): Bitmap =
        if (isHardwareBitmap()) copy(Bitmap.Config.ARGB_8888, false) else this

    private fun Bitmap.toSoftwareMutable(): Bitmap =
        copy(Bitmap.Config.ARGB_8888, true)
    //facebox effect
    fun applyEffect(
        context: Context,
        original: Bitmap,
        boxes: List<RectF>,
        selectedIndices: Set<Int>,
        effect: FaceEffect,
        intensity: Float,
        emoji: String = "😀"
    ): Bitmap {
        // Always work on a software mutable copy — never touch the original
        val result = original.toSoftwareMutable()
        val canvas = Canvas(result)

        selectedIndices.forEach { i ->
            val box = boxes.getOrNull(i) ?: return@forEach
            val left   = box.left.toInt().coerceIn(0, result.width)
            val top    = box.top.toInt().coerceIn(0, result.height)
            val right  = box.right.toInt().coerceIn(0, result.width)
            val bottom = box.bottom.toInt().coerceIn(0, result.height)
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@forEach

            when (effect) {
                FaceEffect.BLUR -> {
                    // Crop from result (already software), blur, draw back
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
                    canvas.drawRect(
                        left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
                        Paint().apply { color = Color.BLACK }
                    )
                }
                FaceEffect.EMOJI -> {
                    drawEmoji(canvas, emoji, left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                }
            }
        }
        return result
    }

    private fun drawEmoji(
        canvas: Canvas,
        emoji: String,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        val boxW = right - left
        val boxH = bottom - top
        val paint = Paint().apply {
            textSize = minOf(boxW, boxH) * 0.9f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val fm = paint.fontMetrics
        canvas.drawText(
            emoji,
            left + boxW / 2f,
            top + boxH / 2f - (fm.ascent + fm.descent) / 2f,
            paint
        )
    }
    fun applyBrushMaskEffect(
        context: Context,
        base: Bitmap,
        mask: Bitmap?,
        effect: FaceEffect,
        intensity: Float,
        emoji: String = "😀",
        brushRadius: Float = 60f,
        strokePaths: List<List<PointF>> = emptyList() // Added vector paths here
    ): Bitmap {
        if (mask == null || isMaskEmpty(mask)) return base
        val softBase = base.toSoftware()

        // ── CASE 1: Dynamic Vector Line Path Trail for Emojis ──
        if (effect == FaceEffect.EMOJI) {
            val result = softBase.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)

            val size = brushRadius * 2f
            val paint = Paint().apply {
                textSize = size
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val fm = paint.fontMetrics
            val yOffset = -(fm.ascent + fm.descent) / 2f

            // Define spacing intervals between emojis along the stroke path line
            val spacing = brushRadius * 0.8f

            // Loop through each separate brush stroke path drawn by the user
            for (pathPoints in strokePaths) {
                if (pathPoints.isEmpty()) continue

                // Draw the very first starting point of the stroke trail
                var lastRecordedX = pathPoints[0].x
                var lastRecordedY = pathPoints[0].y
                canvas.drawText(emoji, lastRecordedX, lastRecordedY + yOffset, paint)

                // Interpolate points along the remaining coordinates of the stroke
                for (i in 1 until pathPoints.size) {
                    val targetX = pathPoints[i].x
                    val targetY = pathPoints[i].y

                    val dx = targetX - lastRecordedX
                    val dy = targetY - lastRecordedY
                    val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    if (distance >= spacing) {
                        val steps = (distance / spacing).toInt()
                        for (j in 1..steps) {
                            val t = (spacing * j) / distance
                            val interpX = lastRecordedX + dx * t
                            val interpY = lastRecordedY + dy * t
                            canvas.drawText(emoji, interpX, interpY + yOffset, paint)
                        }
                        lastRecordedX = targetX
                        lastRecordedY = targetY
                    }
                }
            }
            return result
        }

        // ── CASE 2: Normal Filters (Blur, Pixelate, Blackout) ──
        // Your working logic stays entirely untouched here!
        val fullyProcessed: Bitmap = when (effect) {
            FaceEffect.BLUR -> blurBitmap(context, softBase.copy(Bitmap.Config.ARGB_8888, true), intensity.coerceIn(1f, 25f))
            FaceEffect.PIXELATE -> pixelateBitmap(softBase.copy(Bitmap.Config.ARGB_8888, true), intensity.toInt().coerceAtLeast(2))
            FaceEffect.BLACKOUT -> Bitmap.createBitmap(softBase.width, softBase.height, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.BLACK) }
            else -> softBase
        }

        val intermediateLayer = Bitmap.createBitmap(softBase.width, softBase.height, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(intermediateLayer)
        layerCanvas.drawBitmap(mask, 0f, 0f, null)

        val paintXfer = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        layerCanvas.drawBitmap(fullyProcessed, 0f, 0f, paintXfer)

        val result = softBase.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(intermediateLayer, 0f, 0f, null)

        intermediateLayer.recycle()
        if (fullyProcessed != softBase) fullyProcessed.recycle()

        return result
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Brush emoji stamp  (pre-rendered emoji pixels, just composite on top)
    // ────────────────────────────────────────────────────────────────────────────

    fun applyBrushEmojiStamp(base: Bitmap, stampLayer: Bitmap?): Bitmap {
        if (stampLayer == null) return base
        if (stampLayer.isRecycled) return base
        val result = base.toSoftware().copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(stampLayer, 0f, 0f, null)
        return result
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ────────────────────────────────────────────────────────────────────────────

    private fun blurBitmap(context: Context, bitmap: Bitmap, radius: Float): Bitmap {
        // RenderScript requires ARGB_8888 software bitmap — guaranteed by callers,
        // but double-check here defensively.
        val safe = if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, true) else bitmap

        val rs     = RenderScript.create(context)
        val input  = Allocation.createFromBitmap(rs, safe)
        val output = Allocation.createTyped(rs, input.type)
        ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)).apply {
            setRadius(radius)
            setInput(input)
            forEach(output)
        }
        output.copyTo(safe)
        rs.destroy()
        return safe
    }

    private fun pixelateBitmap(bitmap: Bitmap, blockSize: Int): Bitmap {
        val safe = if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, true) else bitmap

        val small = Bitmap.createScaledBitmap(
            safe,
            (safe.width  / blockSize).coerceAtLeast(1),
            (safe.height / blockSize).coerceAtLeast(1),
            false   // no filtering — keeps the blocky look
        )
        return Bitmap.createScaledBitmap(small, safe.width, safe.height, false)
    }

    /** Returns true when every pixel in the mask is fully transparent. */
    private fun isMaskEmpty(mask: Bitmap): Boolean {
        // Sample a grid of pixels rather than reading every pixel (cheap heuristic)
        val stepX = (mask.width  / 16).coerceAtLeast(1)
        val stepY = (mask.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < mask.height) {
            var x = 0
            while (x < mask.width) {
                if (Color.alpha(mask.getPixel(x, y)) > 0) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Gallery save
    // ────────────────────────────────────────────────────────────────────────────

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
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
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