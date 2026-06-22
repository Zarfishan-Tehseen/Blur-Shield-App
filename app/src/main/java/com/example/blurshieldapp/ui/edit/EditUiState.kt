package com.example.blurshieldapp.ui.edit

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.example.blurshieldapp.utils.FaceEffect
import com.example.blurshieldapp.view.BrushTool

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
    val isBrushEnabled: Boolean = false,
    val brushTool: BrushTool = BrushTool.PAINT,
    val brushRadius: Float = 60f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoading: Boolean = false,
    // Add this line to keep track of vector paths for dynamic emojis
    val strokePaths: List<List<PointF>> = emptyList()
)