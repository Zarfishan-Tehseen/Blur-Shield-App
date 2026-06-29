package com.example.blurshieldapp.domain.usecase

import com.example.blurshieldapp.data.repository.VideoRepository

class ExtractFramesUseCase(private val repository: VideoRepository) {
    suspend operator fun invoke(
        videoPath: String,
        outputDir: String,
        fps: Int = 15,
        onProgress: (Int, String) -> Unit
    ) = repository.extractFrames(videoPath, outputDir, fps, onProgress)
}