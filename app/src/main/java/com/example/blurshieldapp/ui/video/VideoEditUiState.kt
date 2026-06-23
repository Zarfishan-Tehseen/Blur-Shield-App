package com.example.blurshieldapp.ui.video

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.blurshieldapp.utils.FaceEffect

data class VideoEditUiState(
    val videoUri: String? = null,
    val durationMs: Long = 0L,
    val frameRate: Float = 30f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,

    val previewFrameBitmap: Bitmap? = null,
    val previewPositionMs: Long = 0L,

    val keyframeBoxes: Map<Long, List<RectF>> = emptyMap(),

    val selectedFaces: Set<Int> = emptySet(),

    val effect: FaceEffect = FaceEffect.BLUR,
    val intensity: Float = 15f,
    val selectedEmoji: String = "😀",

    val keyframeIntervalSec: Float = 1f,

    val isDetecting: Boolean = false,
    val isProcessing: Boolean = false,
    val processingProgress: Float = 0f,
    val outputUri: String? = null,
    val error: String? = null,
    var isPlaying: Boolean = false
)