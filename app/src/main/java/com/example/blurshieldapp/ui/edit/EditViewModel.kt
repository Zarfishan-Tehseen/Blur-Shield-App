package com.example.blurshieldapp.ui.edit

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import androidx.lifecycle.ViewModel
import com.example.blurshieldapp.domain.model.EditSnapshot
import com.example.blurshieldapp.utils.FaceEffect
import com.example.blurshieldapp.view.BrushTool
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
    private var currentImageUri: String? = null
    private val _exportBitmap = MutableStateFlow<Bitmap?>(null)
    val exportBitmap: StateFlow<Bitmap?> = _exportBitmap.asStateFlow()

    // ---------- Initialization ----------
    fun loadImageIfNeeded(uri: String, loadBitmap: () -> Bitmap): Boolean {
        if (uri == currentImageUri && _uiState.value.originalBitmap != null) {
            return false
        }
        reset()
        currentImageUri = uri
        var bitmap = loadBitmap()

        if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        _uiState.update { it.copy(originalBitmap = bitmap, isLoading = true) }
        return true
    }

    private fun reset() {
        _uiState.value = EditUiState()
        undoStack.clear()
        redoStack.clear()
        detectedFaces = emptyList()
    }

    fun setDetectedFaces(faces: List<Face>) {
        detectedFaces = faces
        val rects = faces.map { RectF(it.boundingBox) }
        _uiState.update {
            it.copy(detectedFaceCount = faces.size, boxRects = rects, isLoading = false)
        }
        pushSnapshot(recordHistory = false)
    }

    // ---------- Face-box actions ----------

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
            val updated = state.boxRects.toMutableList().apply { this[index] = RectF(newRect) }
            state.copy(boxRects = updated)
        }
    }

    fun onBoxAdjustFinished() {
        pushSnapshot()
    }

    // ---------- Brush actions ----------

    fun toggleBrushEnabled() {
        _uiState.update { it.copy(isBrushEnabled = !it.isBrushEnabled) }
    }

    fun onBrushToolChanged(tool: BrushTool) {
        _uiState.update { it.copy(brushTool = tool) }
    }

    fun onBrushRadiusChanged(radius: Float) {
        _uiState.update { it.copy(brushRadius = radius) }
    }

    fun onMaskStrokeFinished(mask: Bitmap) {
        // Keep an isolated copy inside UI State to decouple it from view mutations
        val secureCopy = mask.copy(mask.config ?: Bitmap.Config.ARGB_8888, true)
        _uiState.update { it.copy(maskBitmap = secureCopy) }
        pushSnapshot()
    }

    fun onEmojiStrokeFinished(stamp: Bitmap) {
        val secureCopy = stamp.copy(stamp.config ?: Bitmap.Config.ARGB_8888, true)
        _uiState.update { it.copy(emojiStampBitmap = secureCopy) }
        pushSnapshot()
    }

    fun clearBrushLayers() {
        _uiState.update { it.copy(maskBitmap = null, emojiStampBitmap = null) }
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
            maskBitmap = state.maskBitmap,
            emojiStampBitmap = state.emojiStampBitmap
        ).copySafe()

        if (recordHistory) {
            undoStack.addLast(snapshot)
            if (undoStack.size > maxStackSize) undoStack.removeFirst()
            redoStack.clear()
        } else {
            undoStack.addLast(snapshot)
        }
        updateUndoRedoFlags()
    }

    fun undo() {
        if (undoStack.size <= 1) return
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
                maskBitmap = snapshot.maskBitmap,
                emojiStampBitmap = snapshot.emojiStampBitmap
            )
        }
    }
    private fun updateUndoRedoFlags() {
        _uiState.update {
            it.copy(canUndo = undoStack.size > 1, canRedo = redoStack.isNotEmpty())
        }
    }
    fun prepareExport(compositor: (Bitmap, EditUiState) -> Bitmap) {
        val state = _uiState.value
        val base = state.originalBitmap ?: return
        _exportBitmap.value = compositor(base, state)
    }

    fun clearExport() {
        _exportBitmap.value = null
    }
}