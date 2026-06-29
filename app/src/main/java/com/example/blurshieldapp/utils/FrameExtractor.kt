package com.example.blurshieldapp.utils

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object FrameExtractor {

    data class ExtractionResult(
        val success: Boolean,
        val framePaths: List<String>,
        val errorMessage: String? = null
    )
    suspend fun extract(
        videoPath: String,
        outputDir: String,
        fps: Int = 15,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): ExtractionResult = withContext(Dispatchers.IO) {

        val retriever = MediaMetadataRetriever()

        try {
            // Clean and create output dir
            val dir = File(outputDir).apply {
                deleteRecursively()
                mkdirs()
            }

            retriever.setDataSource(videoPath)

            // Get video duration in microseconds
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs == 0L) {
                return@withContext ExtractionResult(
                    success = false,
                    framePaths = emptyList(),
                    errorMessage = "Could not read video duration"
                )
            }

            // Calculate frame timestamps
            val intervalMs = 1000L / fps
            val timestamps = mutableListOf<Long>()
            var currentMs = 0L
            while (currentMs < durationMs) {
                timestamps.add(currentMs)
                currentMs += intervalMs
            }

            val totalFrames = timestamps.size
            val framePaths = mutableListOf<String>()

            onProgress(0, "Extracting 0 / $totalFrames frames...")

            timestamps.forEachIndexed { index, timeMs ->
                // getFrameAtTime takes microseconds
                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (bitmap != null) {
                    val frameFile = File(dir, "frame_%04d.jpg".format(index))
                    saveBitmap(bitmap, frameFile)
                    bitmap.recycle()
                    framePaths.add(frameFile.absolutePath)
                }

                val progress = ((index + 1) * 50 / totalFrames)
                onProgress(
                    progress,
                    "Extracting ${index + 1} / $totalFrames frames..."
                )
            }

            onProgress(totalFrames, "Extraction complete. ${framePaths.size} frames saved.")

            ExtractionResult(success = true, framePaths = framePaths)

        } catch (e: Exception) {
            android.util.Log.e("FrameExtractor", "Extraction failed: ${e.message}")
            ExtractionResult(
                success = false,
                framePaths = emptyList(),
                errorMessage = e.message ?: "Unknown error"
            )
        } finally {
            retriever.release()
        }
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
    }

    /**
     * Clears all extracted frames to free disk space.
     * Call this when user leaves the editor.
     */
    fun clearFrames(outputDir: String) {
        File(outputDir).deleteRecursively()
    }
}