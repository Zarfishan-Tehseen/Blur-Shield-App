package com.example.blurshieldapp.domain.model

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.blurshieldapp.utils.FaceEffect

data class EditSnapshot(
    val boxRects: List<RectF>,
    val selectedFaces: Set<Int>,
    val effect: FaceEffect,
    val intensity: Float,
    val selectedEmoji: String,
    val maskBitmap: Bitmap?,
    val emojiStampBitmap: Bitmap?
) {
    fun copySafe(): EditSnapshot = copy(
        boxRects = boxRects.map { RectF(it) },
        selectedFaces = selectedFaces.toSet(),
        maskBitmap = maskBitmap?.copy(maskBitmap.config ?: Bitmap.Config.ARGB_8888, true),
        emojiStampBitmap = emojiStampBitmap?.copy(emojiStampBitmap.config ?: Bitmap.Config.ARGB_8888, true)
    )
}