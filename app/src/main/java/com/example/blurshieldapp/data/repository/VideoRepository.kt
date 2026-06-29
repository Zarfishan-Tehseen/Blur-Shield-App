package com.example.blurshieldapp.data.repository

import com.example.blurshieldapp.data.model.FrameData
interface VideoRepository {
    suspend fun extractFrames(
        videoPath: String,
        outputDir: String,
        fps: Int,
        onProgress: (Int, String) -> Unit
    ): List<String> // returns list of frame image paths

    suspend fun detectFaces(
        framePaths: List<String>,
        fps: Int,
        onProgress: (Int, String) -> Unit
    ): List<FrameData>
}