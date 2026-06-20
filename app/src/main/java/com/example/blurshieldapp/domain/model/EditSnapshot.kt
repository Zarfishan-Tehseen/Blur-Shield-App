package com.example.blurshieldapp.domain.model

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.blurshieldapp.utils.FaceEffect

/**
 * A complete, immutable snapshot of editable state at one point in time.
 * Pushed to the undo stack after every discrete user action.
 */
data class EditSnapshot(
    val boxRects: List<RectF>,
    val selectedFaces: Set<Int>,
    val effect: FaceEffect,
    val intensity: Float,
    val selectedEmoji: String,
    val maskBitmap: Bitmap?   // null until brush mode is used
) {
    /** Deep-ish copy so mutating the live state later never corrupts a stored snapshot */
    fun copySafe(): EditSnapshot = copy(
        boxRects = boxRects.map { RectF(it) },
        selectedFaces = selectedFaces.toSet(),
        maskBitmap = maskBitmap?.copy(maskBitmap.config ?: Bitmap.Config.ARGB_8888, true)
    )
}