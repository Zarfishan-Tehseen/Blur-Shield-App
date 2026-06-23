package com.example.blurshieldapp.ui.video

import android.net.Uri

data class VideoEditUiState(
    val videoUri: Uri? = null,
    val durationMs: Long = 0,
    val isProcessing: Boolean = false,
    val progress: Int = 0, // 0 to 100
    val selectedEffect: VideoEffect = VideoEffect.NONE,
    val selectedEmoji: String? = null, // Can be "😊", "😎", "🐱", "❤️"
    val error: String? = null,
    val exportedVideoUri: Uri? = null
)

enum class VideoEffect {
    NONE,
    BLUR,
    PIXELATE,
    BLACKOUT,
    EMOJI
}