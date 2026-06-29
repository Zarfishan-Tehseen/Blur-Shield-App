package com.example.blurshieldapp.domain.usecase

import com.example.blurshieldapp.data.repository.VideoRepository
class DetectFacesUseCase(private val repository: VideoRepository) {
    suspend operator fun invoke(
        framePaths: List<String>,
        fps: Int,
        onProgress: (Int, String) -> Unit
    ) = repository.detectFaces(framePaths,fps, onProgress)
}