package com.example.blurshieldapp.data.model

import android.graphics.RectF

data class VideoSnapshot(
    val framesFaceState: List<FrameFaceState>,  // one entry per frame
    val intensity: Float,
    val selectedEmoji: String
)
data class FrameFaceState(
    val frameId: Int,
    val faces: List<FaceFaceState>
)

data class FaceFaceState(
    val faceId: String,
    val trackingId: Int,
    val boundingBox: RectF,
    val effect: FaceEffect
)