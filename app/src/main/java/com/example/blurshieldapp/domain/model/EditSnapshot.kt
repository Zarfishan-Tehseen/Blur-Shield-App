package com.example.blurshieldapp.domain.model

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.example.blurshieldapp.utils.FaceEffect

data class EditSnapshot(
    val boxRects: List<RectF>,
    val selectedFaces: Set<Int>,
    val effect: FaceEffect,
    val intensity: Float,
    val selectedEmoji: String,
    val maskBitmap: Bitmap?,
    // Add the paths here
    val strokePaths: List<List<PointF>>
) {
    fun copySafe(): EditSnapshot = copy(
        boxRects = boxRects.map { RectF(it) },
        selectedFaces = selectedFaces.toSet(),
        maskBitmap = maskBitmap?.copy(maskBitmap.config ?: Bitmap.Config.ARGB_8888, true),
        // Deep copy the list of points safely
        strokePaths = strokePaths.map { stroke -> stroke.map { PointF(it.x, it.y) } }
    )
}