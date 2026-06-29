package com.example.blurshieldapp.domain.usecase

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.blurshieldapp.data.model.FaceEffect
import com.example.blurshieldapp.data.model.FrameData
import com.example.blurshieldapp.utils.EffectProcessor

class ApplyEffectUseCase {

    // 1. Keeps your tracking persistence across multiple frames
    operator fun invoke(
        frames: List<FrameData>,
        trackingId: Int,
        effect: FaceEffect
    ) {
        frames.forEach { frame ->
            frame.faces
                .filter { it.trackingId == trackingId }
                .forEach { it.effect = effect }
        }
    }

    // 2. Refactored to delegate directly to our optimized EffectProcessor
    fun renderFrame(
        originalBitmap: Bitmap,
        faces: List<com.example.blurshieldapp.data.model.FaceData>,
        intensity: Float = 15f,    // ← add
        emoji: String = "😶"
    ): Bitmap {
        // Map our custom FaceData data class into the Pair<RectF, FaceEffect> required by EffectProcessor
        val effectPairs = faces.map { face ->
            Pair(face.boundingBox, face.effect)
        }

        // Returns the beautiful modified mutable copy, leaving the original intact
        return EffectProcessor.applyEffects(originalBitmap, effectPairs, intensity, emoji)
    }
}