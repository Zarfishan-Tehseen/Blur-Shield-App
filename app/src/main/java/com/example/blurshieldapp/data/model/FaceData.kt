package com.example.blurshieldapp.data.model

import android.graphics.RectF

data class FaceData(
    val faceId: String,
    val trackingId: Int,       // ML Kit tracking ID (same face across frames)
    val boundingBox: RectF,    // in bitmap coordinates
    var effect: FaceEffect = FaceEffect.NONE
)

enum class FaceEffect {
    NONE, BLUR, PIXELATE, BLACKOUT, EMOJI
}