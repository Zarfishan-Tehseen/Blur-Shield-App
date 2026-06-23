package com.example.blurshieldapp.utils

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.blurshieldapp.ui.video.VideoEditUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "VideoProcessor"
private const val TIMEOUT_US = 10_000L
private const val OUTPUT_MIME = MediaFormat.MIMETYPE_VIDEO_AVC  // H.264
private const val OUTPUT_BIT_RATE = 8_000_000                  // 8 Mbps
private const val OUTPUT_I_FRAME_INTERVAL = 1                   // keyframe every 1 sec

object VideoProcessor {
    suspend fun processVideo(
        context: Context,
        state: VideoEditUiState,
        getInterpolatedBoxes: (frameTimestampMs: Long) -> List<RectF>,
        onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.IO) {

        val inputUri = Uri.parse(state.videoUri ?: return@withContext null)

        // ── Step 1: gather video/audio metadata ────────────────────────────────────
        val retriever = MediaMetadataRetriever()
        val audioExtractor = MediaExtractor()
        var hasAudio = false
        var inputAudioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        try {
            retriever.setDataSource(context, inputUri)

            // Set up extractor to look for an audio track
            audioExtractor.setDataSource(context, inputUri, null)
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    inputAudioTrackIndex = i
                    audioFormat = format
                    hasAudio = true
                    audioExtractor.selectTrack(i)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open video or extract audio: ${e.message}")
            audioExtractor.release()
            return@withContext null
        }

        val durationUs = (state.durationMs * 1000L)
        val videoWidth = state.videoWidth.takeIf { it > 0 }
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toInt() ?: 1280
        val videoHeight = state.videoHeight.takeIf { it > 0 }
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toInt() ?: 720
        val frameRateInt = state.frameRate.toInt().takeIf { it > 0 } ?: 30

        // ── Step 2: prepare output file ──────────────────────────────────────
        val outputFile = createOutputFile(context) ?: run {
            Log.e(TAG, "Failed to create output file")
            retriever.release()
            audioExtractor.release()
            return@withContext null
        }

        // ── Step 3: set up encoder + muxer ───────────────────────────────────
        val encoderFormat = MediaFormat.createVideoFormat(
            OUTPUT_MIME, videoWidth, videoHeight
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRateInt)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_I_FRAME_INTERVAL)
        }

        val encoder = MediaCodec.createEncoderByType(OUTPUT_MIME)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerAudioTrackIndex = -1
        var muxerStarted = false

        if (hasAudio && audioFormat != null) {
            muxerAudioTrackIndex = muxer.addTrack(audioFormat)
        }

        // ── Step 4: frame-by-frame processing loop ───────────────────────────
        val frameIntervalUs = 1_000_000L / frameRateInt
        var currentTimeUs = 0L
        var frameIndex = 0
        val totalFrames = (durationUs / frameIntervalUs).toInt().coerceAtLeast(1)

        try {
            while (currentTimeUs <= durationUs && isActive) {

                // Extract frame at this timestamp
                val rawFrame = retriever.getFrameAtTime(
                    currentTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (rawFrame != null) {
                    // Apply effects to this frame
                    val processedFrame = applyEffectsToFrame(
                        context = context,
                        frame = rawFrame,
                        timestampMs = currentTimeUs / 1000,
                        state = state,
                        getInterpolatedBoxes = getInterpolatedBoxes
                    )

                    val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        inputSurface.lockHardwareCanvas()
                    } else {
                        inputSurface.lockCanvas(null)
                    }

                    canvas.drawBitmap(processedFrame, 0f, 0f, null)
                    inputSurface.unlockCanvasAndPost(canvas)

                    rawFrame.recycle()
                    if (processedFrame !== rawFrame) processedFrame.recycle()
                }

                // Drain encoder output into muxer (Fixed parameters to match the definition)
                drainEncoder(
                    encoder = encoder,
                    muxer = muxer,
                    presentationTimeUs = currentTimeUs,
                    muxerIndex = { muxerTrackIndex },
                    setMuxerIndex = { index -> muxerTrackIndex = index },
                    muxerStarted = { muxerStarted },
                    setMuxerStarted = { muxerStarted = true },
                    endOfStream = false
                )

                currentTimeUs += frameIntervalUs
                frameIndex++
                onProgress((frameIndex.toFloat() / totalFrames) * 0.85f)            }

            // Signal end of stream and drain remaining encoded frames
            drainEncoder(
                encoder = encoder,
                muxer = muxer,
                presentationTimeUs = currentTimeUs,
                muxerIndex = { muxerTrackIndex },
                setMuxerIndex = { index -> muxerTrackIndex = index },
                muxerStarted = { muxerStarted },
                setMuxerStarted = { muxerStarted = true },
                endOfStream = true
            )
            if (hasAudio && muxerStarted) {
                copyAudioTrack(
                    extractor = audioExtractor,
                    muxer = muxer,
                    muxerAudioTrackIndex = muxerAudioTrackIndex,
                    durationUs = durationUs
                )
            }

            onProgress(1.0f)

        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}")
            encoder.release()
            inputSurface.release()
            muxer.release()
            retriever.release()
            audioExtractor.release()
            outputFile.delete()
            return@withContext null
        }

        // ── Step 5: finalize ─────────────────────────────────────────────────
        encoder.stop()
        encoder.release()
        inputSurface.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        retriever.release()
        audioExtractor.release()

        // ── Step 6: save to gallery ──────────────────────────────────────────
        return@withContext saveVideoToGallery(context, outputFile)
    }

    // ── Effect application ────────────────────────────────────────────────────

    private fun applyEffectsToFrame(
        context: Context,
        frame: Bitmap,
        timestampMs: Long,
        state: VideoEditUiState,
        getInterpolatedBoxes: (Long) -> List<RectF>
    ): Bitmap {
        if (state.selectedFaces.isEmpty()) return frame

        val boxes = getInterpolatedBoxes(timestampMs)
        if (boxes.isEmpty()) return frame

        return ImageProcessor.applyEffect(
            context = context,
            original = frame,
            boxes = boxes,
            selectedIndices = state.selectedFaces,
            effect = state.effect,
            intensity = state.intensity,
            emoji = state.selectedEmoji
        )
    }

    // ── Encoder drain helper ──────────────────────────────────────────────────

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        presentationTimeUs: Long,
        muxerIndex: () -> Int,
        setMuxerIndex: (Int) -> Unit,
        muxerStarted: () -> Boolean,
        setMuxerStarted: () -> Unit,
        endOfStream: Boolean
    ) {
        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder.outputFormat
                    val trackIndex = muxer.addTrack(newFormat)
                    setMuxerIndex(trackIndex) // Fixed: changed from setMuxerTrackIndex
                    muxer.start()
                    setMuxerStarted()
                }
                outputBufferIndex >= 0 -> {
                    val encodedData: ByteBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        ?: continue

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0 && muxerStarted()) {
                        bufferInfo.presentationTimeUs = presentationTimeUs
                        muxer.writeSampleData(muxerIndex(), encodedData, bufferInfo) // Fixed: changed from muxerTrackIndex()
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        }
    }

    // ── Output file creation ──────────────────────────────────────────────────

    @SuppressLint("WrongConstant")
    private fun copyAudioTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerAudioTrackIndex: Int,
        durationUs: Long
    ) {
        val maxBufferSize = 256 * 1024
        val buffer = ByteBuffer.allocateDirect(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)

            if (bufferInfo.size < 0) {
                break // No more audio packets left
            }

            bufferInfo.presentationTimeUs = extractor.sampleTime
            if (bufferInfo.presentationTimeUs > durationUs) {
                break // Stop copying if we go past the edited video duration limits
            }

            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerAudioTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
    private fun createOutputFile(context: Context): File? {
        return try {
            val outputDir = context.cacheDir
            File(outputDir, "processed_video_${System.currentTimeMillis()}.mp4")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create output file: ${e.message}")
            null
        }
    }

    // ── Save to gallery ───────────────────────────────────────────────────────

    private fun saveVideoToGallery(context: Context, file: File): String? {
        return try {
            val filename = "BlurShield_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null

            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            file.delete()
            uri.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to gallery: ${e.message}")
            null
        }
    }
}