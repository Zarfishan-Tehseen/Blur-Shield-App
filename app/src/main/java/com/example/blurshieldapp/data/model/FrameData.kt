package com.example.blurshieldapp.data.model

data class FrameData(
    val frameId: Int,
    val timestampMs: Long,
    val framePath: String?,     // path to frame image on disk
    val faces: MutableList<FaceData> = mutableListOf()
)