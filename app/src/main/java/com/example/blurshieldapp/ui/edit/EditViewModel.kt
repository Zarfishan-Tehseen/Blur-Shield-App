package com.example.blurshieldapp.ui.edit

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blurshieldapp.domain.model.EditSnapshot
import com.example.blurshieldapp.utils.FaceEffect
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EditViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private val maxStackSize = 20

    private var detectedFaces: List<Face> = emptyList()

    // ---------- Initialization ----------

    fun setOriginalImage(bitmap: Bitmap) {
        _uiState.update { it.copy(originalBitmap = bitmap, isLoading = true) }
    }

    fun setDetectedFaces(faces: List<Face>) {
        detectedFaces = faces
        val rects = faces.map { RectF(it.boundingBox) }
        _uiState.update {
            it.copy(
                detectedFaceCount = faces.size,
                boxRects = rects,
                isLoading = false
            )
        }
        // Establish the initial state as the base of the undo stack (not undo-able past this)
        pushSnapshot(recordHistory = false)
    }

    // ---------- User actions (each pushes a snapshot AFTER applying) ----------

    fun onFaceSelected(index: Int, selected: Boolean) {
        _uiState.update { state ->
            val newSelection = state.selectedFaces.toMutableSet().apply {
                if (selected) add(index) else remove(index)
            }
            state.copy(selectedFaces = newSelection)
        }
        pushSnapshot()
    }

    fun onEffectChanged(effect: FaceEffect) {
        _uiState.update { it.copy(effect = effect) }
        pushSnapshot()
    }

    fun onIntensityChanged(intensity: Float) {
        _uiState.update { it.copy(intensity = intensity) }
        // Slider drags fire many events; only snapshot on settle (see onIntensityChangeFinished)
    }

    fun onIntensityChangeFinished() {
        pushSnapshot()
    }

    fun onEmojiSelected(emoji: String) {
        _uiState.update { it.copy(selectedEmoji = emoji) }
        pushSnapshot()
    }

    fun onBoxAdjusted(index: Int, newRect: RectF) {
        _uiState.update { state ->
            val updated = state.boxRects.toMutableList().apply {
                this[index] = RectF(newRect)
            }
            state.copy(boxRects = updated)
        }
        // Don't snapshot every drag-move event — only when drag ends
    }

    fun onBoxAdjustFinished() {
        pushSnapshot()
    }

    fun onBrushMaskUpdated(mask: Bitmap) {
        _uiState.update { it.copy(maskBitmap = mask) }
        // Snapshot on stroke-end only (call onBrushStrokeFinished from the view)
    }

    fun onBrushStrokeFinished() {
        pushSnapshot()
    }

    fun togglePreviewMode() {
        _uiState.update { it.copy(isPreviewMode = !it.isPreviewMode) }
    }

    // ---------- Undo / Redo ----------

    private fun pushSnapshot(recordHistory: Boolean = true) {
        val state = _uiState.value
        val snapshot = EditSnapshot(
            boxRects = state.boxRects,
            selectedFaces = state.selectedFaces,
            effect = state.effect,
            intensity = state.intensity,
            selectedEmoji = state.selectedEmoji,
            maskBitmap = state.maskBitmap
        ).copySafe()

        if (recordHistory) {
            undoStack.addLast(snapshot)
            if (undoStack.size > maxStackSize) undoStack.removeFirst()
            redoStack.clear() // new action invalidates redo history
        } else {
            // Initial baseline state, still needed so first undo has somewhere to go
            undoStack.addLast(snapshot)
        }
        updateUndoRedoFlags()
    }

    fun undo() {
        if (undoStack.size <= 1) return // keep at least the baseline state
        val current = undoStack.removeLast()
        redoStack.addLast(current)
        val previous = undoStack.last()
        restoreSnapshot(previous)
        updateUndoRedoFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        restoreSnapshot(next)
        updateUndoRedoFlags()
    }

    private fun restoreSnapshot(snapshot: EditSnapshot) {
        _uiState.update { state ->
            state.copy(
                boxRects = snapshot.boxRects.map { RectF(it) },
                selectedFaces = snapshot.selectedFaces.toSet(),
                effect = snapshot.effect,
                intensity = snapshot.intensity,
                selectedEmoji = snapshot.selectedEmoji,
                maskBitmap = snapshot.maskBitmap
            )
        }
    }

    private fun updateUndoRedoFlags() {
        _uiState.update {
            it.copy(
                canUndo = undoStack.size > 1,
                canRedo = redoStack.isNotEmpty()
            )
        }
    }
}