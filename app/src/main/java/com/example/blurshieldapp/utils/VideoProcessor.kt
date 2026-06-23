package com.example.blurshieldapp.utils

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import com.example.blurshieldapp.ui.video.VideoEffect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class VideoProcessor(private val context: Context) {

    // Preset emojis list (4 built-in emojis)
    val availableEmojis = listOf("😊", "😎", "🐱", "❤️")
    suspend fun processVideo(
        sourceUri: Uri,
        outputFile: File,
        effect: VideoEffect,
        emojiChar: String? = null,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, sourceUri)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to read source video metadata: ${e.localizedMessage}")
        }

        // 1. Verify Video Duration (Limit to 30 seconds)
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 0L
        if (durationMs > 30_000L) {
            retriever.release()
            throw IllegalArgumentException("Video exceeds the 30-second prototype limit (${durationMs / 1000}s).")
        }

        val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

        val srcWidth = widthStr?.toIntOrNull() ?: 640
        val srcHeight = heightStr?.toIntOrNull() ?: 480
        val rotation = rotationStr?.toIntOrNull() ?: 0

        // Determine output dimensions, swapping width/height if rotated 90 or 270 degrees
        val isRotated = rotation == 90 || rotation == 270
        val originalWidth = if (isRotated) srcHeight else srcWidth
        val originalHeight = if (isRotated) srcWidth else srcHeight

        // Scale down video to max 640p for processing performance
        val maxDimension = 640
        val scale = Math.min(1.0f, maxDimension.toFloat() / Math.max(originalWidth, originalHeight))
        val targetWidth = ((originalWidth * scale).toInt() / 2) * 2 // Must be even for encoder
        val targetHeight = ((originalHeight * scale).toInt() / 2) * 2

        // Setup ML Kit Face Detector
        val detectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        val faceDetector = FaceDetection.getClient(detectorOptions)

        // Setup MediaCodec Encoder (H.264 / AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000) // 1.5 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15) // Fixed 15 FPS for processing speed
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s sync keyframes

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // Setup MediaMuxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var isMuxerStarted = false

        // Setup MediaExtractor for Audio tracking
        val audioExtractor = MediaExtractor()
        var hasAudio = false
        try {
            audioExtractor.setDataSource(context, sourceUri, null)
            for (i in 0 until audioExtractor.trackCount) {
                val trackFormat = audioExtractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = muxer.addTrack(trackFormat)
                    hasAudio = true
                    break
                }
            }
        } catch (e: Exception) {
            // No audio or error reading audio - proceed video only
        }

        // Estimation of frames
        val fps = 15
        val frameDurationUs = 1_000_000L / fps
        val totalFrames = ((durationMs / 1000f) * fps).toInt().coerceAtLeast(1)

        val bufferInfo = MediaCodec.BufferInfo()
        var frameIndex = 0
        var isEncoderEOS = false

        try {
            while (frameIndex < totalFrames || !isEncoderEOS) {
                // Feed encoder input buffers while we have frames left
                if (frameIndex < totalFrames) {
                    val timeUs = frameIndex * frameDurationUs
                    // Extract frame bitmap
                    val sourceBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (sourceBitmap != null) {
                        // 1. Resize and Rotate Frame to Target Dimensions
                        val processedBitmap = resizeAndOrientBitmap(sourceBitmap, targetWidth, targetHeight, rotation)
                        val canvas = Canvas(processedBitmap)

                        // 2. Perform Face Detection if effect is active
                        if (effect != VideoEffect.NONE) {
                            val inputImage = InputImage.fromBitmap(processedBitmap, 0)
                            try {
                                val faces = Tasks.await(faceDetector.process(inputImage))
                                for (face in faces) {
                                    applyEffectToFace(canvas, face.boundingBox, processedBitmap, effect, emojiChar)
                                }
                            } catch (e: Exception) {
                                // Fallback/Skip face detection errors for this frame
                            }
                        }

                        // 3. Convert Bitmap to NV12 (YUV420SP)
                        val yuvData = convertBitmapToNV12(processedBitmap)

                        // Feed to Encoder
                        val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(yuvData)
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                yuvData.size,
                                timeUs,
                                if (frameIndex == totalFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            frameIndex++
                            val progressPercent = ((frameIndex.toFloat() / totalFrames) * 90).toInt() // 90% is video encoding
                            onProgress(progressPercent)
                        }
                    } else {
                        // End of stream if retriever fails to get more frames
                        val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                frameIndex * frameDurationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            frameIndex = totalFrames // Exit loop condition
                        }
                    }
                }

                // Dequeue Encoder Output Buffers and Write to Muxer
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                    if (encodedData != null) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // Codec config data (SPS/PPS), not video content
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0) {
                            if (!isMuxerStarted) {
                                val newFormat = encoder.outputFormat
                                videoTrackIndex = muxer.addTrack(newFormat)
                                muxer.start()
                                isMuxerStarted = true
                            }
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isEncoderEOS = true
                        }
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!isMuxerStarted) {
                        val newFormat = encoder.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        isMuxerStarted = true
                    }
                }
            }

            // 4. Mux Audio Track (Synchronously copying audio packet-by-packet)
            if (hasAudio && isMuxerStarted) {
                val audioBuffer = ByteBuffer.allocate(1024 * 256)
                val audioBufferInfo = MediaCodec.BufferInfo()
                audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                while (true) {
                    audioBufferInfo.offset = 0
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        break
                    }
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    audioBufferInfo.flags = audioExtractor.sampleFlags

                    // Clamp to original 30-sec limit to stay clean
                    if (audioBufferInfo.presentationTimeUs > durationMs * 1000) {
                        break
                    }

                    muxer.writeSampleData(audioTrackIndex, audioBuffer, audioBufferInfo)
                    audioExtractor.advance()
                }
            }

            onProgress(100)

        } finally {
            // Clean up resources
            try {
                faceDetector.close()
                encoder.stop()
                encoder.release()
                if (isMuxerStarted) {
                    muxer.stop()
                }
                muxer.release()
                audioExtractor.release()
                retriever.release()
            } catch (e: Exception) {
                // Ignore final release cleanup crashes
            }
        }
    }

    private fun resizeAndOrientBitmap(src: Bitmap, targetWidth: Int, targetHeight: Int, rotation: Int): Bitmap {
        val dest = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val matrix = Matrix()

        // Apply scaling
        val scaleX = targetWidth.toFloat() / src.width
        val scaleY = targetHeight.toFloat() / src.height
        matrix.postScale(scaleX, scaleY)

        // Apply rotation around pivot
        if (rotation != 0) {
            matrix.postRotate(rotation.toFloat(), targetWidth / 2f, targetHeight / 2f)
        }

        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return dest
    }

    private fun applyEffectToFace(
        canvas: Canvas,
        rect: Rect,
        bitmap: Bitmap,
        effect: VideoEffect,
        emojiChar: String?
    ) {
        val left = rect.left.coerceIn(0, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height)
        val right = rect.right.coerceIn(0, bitmap.width)
        val bottom = rect.bottom.coerceIn(0, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return

        when (effect) {
            VideoEffect.BLUR -> {
                try {
                    // Extract face bitmap region
                    val face = Bitmap.createBitmap(bitmap, left, top, w, h)
                    // Scale down to blur
                    val scaleFactor = 8
                    val small = Bitmap.createScaledBitmap(face, Math.max(1, w / scaleFactor), Math.max(1, h / scaleFactor), true)
                    // Scale up back
                    val blurred = Bitmap.createScaledBitmap(small, w, h, true)
                    canvas.drawBitmap(blurred, left.toFloat(), top.toFloat(), null)
                    face.recycle()
                    small.recycle()
                    blurred.recycle()
                } catch (e: Exception) {
                    // Fallback to solid blackout on unexpected OOM
                    applyBlackout(canvas, left, top, right, bottom)
                }
            }
            VideoEffect.PIXELATE -> {
                try {
                    val face = Bitmap.createBitmap(bitmap, left, top, w, h)
                    // Scale down deeply (e.g. 16px dimension)
                    val pixelSize = 16
                    val small = Bitmap.createScaledBitmap(face, Math.max(1, w / pixelSize), Math.max(1, h / pixelSize), false)
                    // Scale back up with filtering disabled
                    val paint = Paint().apply { isFilterBitmap = false }
                    canvas.drawBitmap(small, null, RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), paint)
                    face.recycle()
                    small.recycle()
                } catch (e: Exception) {
                    applyBlackout(canvas, left, top, right, bottom)
                }
            }
            VideoEffect.BLACKOUT -> {
                applyBlackout(canvas, left, top, right, bottom)
            }
            VideoEffect.EMOJI -> {
                val selectedEmoji = emojiChar ?: "😊"
                val paint = Paint().apply {
                    textSize = h * 0.85f // Size proportional to face height
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val x = left + w / 2f
                val y = (top + h / 2f) - ((paint.descent() + paint.ascent()) / 2f)
                canvas.drawText(selectedEmoji, x, y, paint)
            }
            VideoEffect.NONE -> { /* No-op */ }
        }
    }

    private fun applyBlackout(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int) {
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
    }

    /**
     * Converts a standard ARGB Bitmap into YUV420 Semi-Planar (NV12 format) byte array.
     */
    private fun convertBitmapToNV12(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val size = w * h
        val nv12 = ByteArray(size + size / 2)
        val argb = IntArray(size)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        var yIndex = 0
        var uvIndex = size

        for (j in 0 until h) {
            for (i in 0 until w) {
                val pixel = argb[j * w + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Fast RGB to YUV NV12 color matrix coefficients
                var yVal = (0.257f * r + 0.504f * g + 0.098f * b + 16).toInt()
                var uVal = (-0.148f * r - 0.291f * g + 0.439f * b + 128).toInt()
                var vVal = (0.439f * r - 0.368f * g - 0.071f * b + 128).toInt()

                yVal = yVal.coerceIn(0, 255)
                uVal = uVal.coerceIn(0, 255)
                vVal = vVal.coerceIn(0, 255)

                nv12[yIndex++] = yVal.toByte()

                // NV12 format: Y plane followed by interleaved U and V bytes for 2x2 blocks
                if (j % 2 == 0 && i % 2 == 0) {
                    nv12[uvIndex++] = uVal.toByte()
                    nv12[uvIndex++] = vVal.toByte()
                }
            }
        }
        return nv12
    }
}