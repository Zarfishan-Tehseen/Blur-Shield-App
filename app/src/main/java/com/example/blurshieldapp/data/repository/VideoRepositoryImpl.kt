package com.example.blurshieldapp.data.repository

import android.graphics.BitmapFactory
import android.graphics.RectF
import com.example.blurshieldapp.data.model.FaceData
import com.example.blurshieldapp.data.model.FrameData
import com.example.blurshieldapp.utils.FrameExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class VideoRepositoryImpl : VideoRepository {

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    override suspend fun extractFrames(
        videoPath: String,
        outputDir: String,
        fps: Int,
        onProgress: (Int, String) -> Unit
    ): List<String> {
        val result = FrameExtractor.extract(videoPath, outputDir, fps, onProgress)
        return if (result.success) result.framePaths else emptyList()
    }

    override suspend fun detectFaces(
        framePaths: List<String>,
        fps: Int,
        onProgress: (Int, String) -> Unit
    ): List<FrameData> = withContext(Dispatchers.IO) {

//        val options = FaceDetectorOptions.Builder()
//            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
//            .enableTracking()
//            .build()
//
//        val detector = FaceDetection.getClient(options)
        val frameDataList = mutableListOf<FrameData>()

        framePaths.forEachIndexed { index, path ->
            val bitmap = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
            val image = InputImage.fromBitmap(bitmap, 0)

            val faces = suspendCancellableCoroutine<List<Face>> { cont ->
                faceDetector.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(emptyList()) }
            }

            val faceDataList = faces.mapIndexed { faceIndex, face ->
                FaceData(
                    faceId = "f_${faceIndex}",
                    trackingId = face.trackingId ?: -1,
                    boundingBox = RectF(face.boundingBox)
                )
            }

            frameDataList.add(
                FrameData(
                    frameId = index,
                    timestampMs = index * 1000L / fps ,
                    framePath = path,
                    faces = faceDataList.toMutableList()
                )
            )

            val progress = 50 + ((index + 1) * 50 / framePaths.size)
            onProgress(progress, "Detecting faces: frame ${index + 1} / ${framePaths.size}")

            bitmap.recycle()
        }
        frameDataList
    }
    fun close() {
        faceDetector.close()
    }
}