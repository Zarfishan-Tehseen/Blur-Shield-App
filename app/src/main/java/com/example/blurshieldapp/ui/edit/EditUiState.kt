package com.example.blurshieldapp.ui.edit

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.blurshieldapp.utils.FaceEffect

data class EditUiState(
    val originalBitmap: Bitmap? = null,
    val detectedFaceCount: Int = 0,
    val boxRects: List<RectF> = emptyList(),
    val selectedFaces: Set<Int> = emptySet(),
    val effect: FaceEffect = FaceEffect.BLUR,
    val intensity: Float = 15f,
    val selectedEmoji: String = "😀",
    val maskBitmap: Bitmap? = null,
    val isPreviewMode: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoading: Boolean = false
)