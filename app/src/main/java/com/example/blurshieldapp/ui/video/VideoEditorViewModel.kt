package com.example.blurshieldapp.ui.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.LruCache
import com.example.blurshieldapp.data.model.FaceData
import com.example.blurshieldapp.data.model.FaceEffect
import com.example.blurshieldapp.data.model.FaceFaceState
import com.example.blurshieldapp.data.model.FrameData
import com.example.blurshieldapp.data.model.FrameFaceState
import com.example.blurshieldapp.data.model.VideoEditorState
import com.example.blurshieldapp.data.model.VideoSnapshot
import com.example.blurshieldapp.data.repository.VideoRepositoryImpl
import com.example.blurshieldapp.domain.usecase.ApplyEffectUseCase
import com.example.blurshieldapp.utils.FrameExtractor
import com.example.blurshieldapp.domain.usecase.DetectFacesUseCase
import com.example.blurshieldapp.domain.usecase.ExtractFramesUseCase
import kotlin.math.roundToInt

class VideoEditorViewModel : ViewModel() {

    private val repository = VideoRepositoryImpl()
    private val extractFramesUseCase = ExtractFramesUseCase(repository)
    private val detectFacesUseCase = DetectFacesUseCase(repository)
    private val applyEffectUseCase = ApplyEffectUseCase()

    private val _editorState = MutableStateFlow<VideoEditorState>(VideoEditorState.Idle)
    val editorState: StateFlow<VideoEditorState> = _editorState

    private val _frames = MutableStateFlow<List<FrameData>>(emptyList())
    val frames: StateFlow<List<FrameData>> = _frames

    private val _currentFrameIndex = MutableStateFlow(0)
    val currentFrameIndex: StateFlow<Int> = _currentFrameIndex

    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _selectedFaceIndices = MutableStateFlow<Set<Int>>(emptySet())
    val selectedFaceIndices: StateFlow<Set<Int>> = _selectedFaceIndices
    private val _intensity = MutableStateFlow(15f)
    val intensity: StateFlow<Float> = _intensity

    private val _selectedEmoji = MutableStateFlow("😶")
    val selectedEmoji: StateFlow<String> = _selectedEmoji

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8  // use 1/8th of available memory
    private val frameCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }

    private var prefetchJob: Job? = null
    private var playbackJob: Job? = null
    private var frameIntervalMs = 33L

    private var framesOutputDir: String? = null

    private val _isPreviewMode = MutableStateFlow(false)
    val isPreviewMode: StateFlow<Boolean> = _isPreviewMode

    private val undoStack = ArrayDeque<VideoSnapshot>()
    private val redoStack = ArrayDeque<VideoSnapshot>()
    private val maxStackSize = 20

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private var seekJob: Job? = null
    private val _activeEffect = MutableStateFlow(FaceEffect.BLUR)
    val activeEffect: StateFlow<FaceEffect> = _activeEffect

    fun preprocessVideo(videoPath: String, cacheDir: File) {
        viewModelScope.launch {
            framesOutputDir?.let { FrameExtractor.clearFrames(it) }
            frameCache.evictAll()

            _editorState.value = VideoEditorState.Preprocessing(0, "Starting...")

            try {
                val retriever = MediaMetadataRetriever().apply {
                    setDataSource(videoPath)
                }

                var nativeFps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toFloatOrNull()?.toInt()
                } else null

                if (nativeFps == null || nativeFps <= 0) {
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 1000L
                    val totalFramesCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                            ?.toIntOrNull() ?: 30
                    } else 30
                    val durationSeconds = durationMs / 1000f
                    nativeFps = (totalFramesCount / durationSeconds).roundToInt().coerceIn(15, 60)
                }

                retriever.release()

                frameIntervalMs = 1000L / nativeFps
                Log.d("VideoEditor", "Detected FPS: $nativeFps | interval: ${frameIntervalMs}ms")

                val outputDir = File(cacheDir, "frames").apply { mkdirs() }
                framesOutputDir = outputDir.absolutePath
                val framePaths = extractFramesUseCase(
                    videoPath = videoPath,
                    outputDir = outputDir.absolutePath,
                    fps = nativeFps
                ) { progress, message ->
                    _editorState.value = VideoEditorState.Preprocessing(progress, message)
                }

                if (framePaths.isEmpty()) {
                    _editorState.value = VideoEditorState.Error("Frame extraction failed")
                    return@launch
                }

                val frameDataList = detectFacesUseCase(framePaths, nativeFps) { progress, message ->
                    _editorState.value = VideoEditorState.Preprocessing(progress, message)
                }

                _frames.value = frameDataList
                _editorState.value = VideoEditorState.Ready(frameDataList.size)

                pushSnapshot()

                // Load first frame and warm up the cache
                withContext(Dispatchers.IO) {
                    loadBitmapForFrameDirect(0)
                    prefetchWindow(startIndex = 0, totalFrames = frameDataList.size)
                }

            } catch (e: Exception) {
                _editorState.value = VideoEditorState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ─── Cache / prefetch ─────────────────────────────────────────────────────

    private suspend fun prefetchWindow(
        startIndex: Int,
        totalFrames: Int,
        windowSize: Int = 20
    ) = withContext(Dispatchers.IO) {
        val framesList = _frames.value
        val intensity = _intensity.value
        val emoji = _selectedEmoji.value
        for (i in 0 until windowSize) {
            val targetIndex = startIndex + i
            if (targetIndex >= totalFrames) break  // ← stop at end, don't wrap
            if (frameCache.get(targetIndex) == null) {
                val frameData = framesList.getOrNull(targetIndex) ?: continue
                val originalBmp = BitmapFactory.decodeFile(frameData.framePath) ?: continue
                val processedBmp = applyEffectUseCase.renderFrame(originalBmp, frameData.faces, intensity, emoji)
                originalBmp.recycle()
                frameCache.put(targetIndex, processedBmp)
            }
        }
    }

    // ─── Playback ─────────────────────────────────────────────────────────────

    fun play() {
        if (_isPlaying.value) return

        viewModelScope.launch {
            val frameList = _frames.value
            if (frameList.isEmpty()) return@launch

            _isPlaying.value = false

            // For blur-heavy content, pre-warm more frames before starting
            // Check if any face has blur effect to decide window size
            val currentFrame = frameList.getOrNull(_currentFrameIndex.value)
            val hasBlur = currentFrame?.faces?.any {
                it.effect == FaceEffect.BLUR
            } == true

            val warmupWindow = if (hasBlur) 40 else 20  // ← bigger window for blur

            prefetchJob?.cancel()
            prefetchJob = launch(Dispatchers.IO) {
                prefetchWindow(
                    startIndex = _currentFrameIndex.value,
                    totalFrames = frameList.size,
                    windowSize = warmupWindow
                )
            }
            prefetchJob?.join()  // wait for warmup to complete before playing

            _isPlaying.value = true
            startPlaybackLoop()
        }
    }
    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(Dispatchers.Main) {
            val frameList = _frames.value
            if (frameList.isEmpty()) return@launch

            val frameIntervalNs = frameIntervalMs * 1_000_000L
            var expectedTimeNs = System.nanoTime()

            while (_isPlaying.value) {
                val nextIndex = (_currentFrameIndex.value + 1) % frameList.size

                val bitmap = frameCache.get(nextIndex)

                if (bitmap != null) {
                    _currentFrameIndex.value = nextIndex
                    _currentBitmap.value = bitmap
                } else {
                    // Cache miss during playback — skip this frame visually
                    // but still advance index so we don't get stuck on one frame
                    // Trigger background load so future loops hit the cache
                    _currentFrameIndex.value = nextIndex
                    viewModelScope.launch(Dispatchers.IO) {
                        loadBitmapForFrameDirect(nextIndex)
                    }
                }

                // Slide prefetch window forward every 10 frames
                if (nextIndex % 10 == 0) {
                    prefetchJob?.cancel()
                    prefetchJob = viewModelScope.launch(Dispatchers.IO) {
                        prefetchWindow(
                            startIndex = nextIndex,
                            totalFrames = frameList.size,
                            windowSize = 20
                        )
                    }
                }

                expectedTimeNs += frameIntervalNs
                val sleepNs = expectedTimeNs - System.nanoTime()
                when {
                    sleepNs > 0 -> delay(sleepNs / 1_000_000L)
                    sleepNs < -(frameIntervalNs * 3) -> {
                        expectedTimeNs = System.nanoTime()
                    }
                }
            }
        }
    }
    fun pause() {
        _isPlaying.value = false
        playbackJob?.cancel()
        prefetchJob?.cancel()
    }

    fun seekToFrame(index: Int) {
        // Cancel any in-flight seek — only the latest seek matters
        seekJob?.cancel()

        _currentFrameIndex.value = index

        seekJob = viewModelScope.launch {
            // Try cache first — instant, no disk IO needed
            val cached = frameCache.get(index)
            if (cached != null) {
                _currentBitmap.value = cached
                // Kick off prefetch in background without blocking UI
                launch(Dispatchers.IO) {
                    prefetchWindow(startIndex = index, totalFrames = _frames.value.size)
                }
                return@launch
            }

            // Cache miss — load from disk on IO thread
            withContext(Dispatchers.IO) {
                loadBitmapForFrameDirect(index)
            }

            // Prefetch around the new position after seek settles
            launch(Dispatchers.IO) {
                prefetchWindow(startIndex = index, totalFrames = _frames.value.size)
            }
        }
    }


    private suspend fun loadBitmapForFrameDirect(index: Int) = withContext(Dispatchers.IO) {
        val frameData = _frames.value.getOrNull(index) ?: return@withContext
        val path = frameData.framePath ?: return@withContext
        if (path.isBlank()) return@withContext
        val originalBmp = BitmapFactory.decodeFile(path) ?: return@withContext
        val processedBmp = applyEffectUseCase.renderFrame(
            originalBmp,
            frameData.faces,
            _intensity.value,
            _selectedEmoji.value)
        originalBmp.recycle()
        frameCache.put(index, processedBmp)
        _currentBitmap.value = processedBmp
    }


    fun toggleFaceSelection(faceIndex: Int) {
        val current = _selectedFaceIndices.value.toMutableSet()
        if (faceIndex in current) current.remove(faceIndex) else current.add(faceIndex)
        _selectedFaceIndices.value = current
    }

    fun clearSelection() {
        _selectedFaceIndices.value = emptySet()
    }

    // ─── Effects ──────────────────────────────────────────────────────────────

    fun applyEffectToSelected(effect: FaceEffect) {
        pause()

        _activeEffect.value = effect
        val frameList = _frames.value.toMutableList()
        val currentFrame = frameList.getOrNull(_currentFrameIndex.value) ?: return

        pushSnapshot()
        val selectedTrackingIds = _selectedFaceIndices.value
            .mapNotNull { currentFrame.faces.getOrNull(it)?.trackingId }
            .filter { it != -1 }

        selectedTrackingIds.forEach { trackingId ->
            applyEffectUseCase(frameList, trackingId, effect)
        }

        _selectedFaceIndices.value.forEach { faceIdx ->
            val face = currentFrame.faces.getOrNull(faceIdx)
            if (face?.trackingId == -1) face.effect = effect
        }

        _frames.value = frameList
        frameCache.evictAll()

        viewModelScope.launch(Dispatchers.IO) {
            loadBitmapForFrameDirect(_currentFrameIndex.value)
            prefetchWindow(
                startIndex = _currentFrameIndex.value,
                totalFrames = frameList.size
            )
        }
    }

    // ─── Box adjustment ───────────────────────────────────────────────────────

    fun updateFaceBox(frameIndex: Int, faceIndex: Int, newRect: RectF) {
        pushSnapshot()
        val frameList = _frames.value.toMutableList()
        frameList.getOrNull(frameIndex)?.faces?.getOrNull(faceIndex)?.let {
            val updated = it.copy(boundingBox = newRect)
            frameList[frameIndex].faces[faceIndex] = updated
        }
        _frames.value = frameList
        frameCache.evictAll()
        viewModelScope.launch(Dispatchers.IO) {
            loadBitmapForFrameDirect(_currentFrameIndex.value)
        }
    }
    fun togglePreviewMode() {
        _isPreviewMode.value = !_isPreviewMode.value
        // Clear selection when entering preview so no stale highlights remain
        if (_isPreviewMode.value) clearSelection()
    }
    fun onIntensityChanged(value: Float) {
        _intensity.value = value
        // Invalidate cache so next frame load uses new intensity
        frameCache.evictAll()
        viewModelScope.launch(Dispatchers.IO) {
            loadBitmapForFrameDirect(_currentFrameIndex.value)
        }
    }
    fun onIntensityChangeFinished() {
        pushSnapshot()
    }

    fun onEmojiSelected(emoji: String) {
        pushSnapshot()
        _selectedEmoji.value = emoji
        frameCache.evictAll()
        viewModelScope.launch(Dispatchers.IO) {
            loadBitmapForFrameDirect(_currentFrameIndex.value)
        }
    }
    // ─── Snapshot helpers ──────────────────────────────────────────────────────

    private fun captureSnapshot(): VideoSnapshot {
        return VideoSnapshot(
            framesFaceState = _frames.value.map { frame ->
                FrameFaceState(
                    frameId = frame.frameId,
                    faces = frame.faces.map { face ->
                        FaceFaceState(
                            faceId = face.faceId,
                            trackingId = face.trackingId,
                            boundingBox = RectF(face.boundingBox),  // deep copy
                            effect = face.effect
                        )
                    }
                )
            },
            intensity = _intensity.value,
            selectedEmoji = _selectedEmoji.value
        )
    }

    private fun pushSnapshot() {
        undoStack.addLast(captureSnapshot())
        if (undoStack.size > maxStackSize) undoStack.removeFirst()
        redoStack.clear()
        updateUndoRedoFlags()
    }

    private fun restoreSnapshot(snapshot: VideoSnapshot) {
        val currentFrames = _frames.value.toMutableList()

        // Restore face effects and box positions frame by frame
        snapshot.framesFaceState.forEach { savedFrame ->
            val frameIndex = currentFrames.indexOfFirst { it.frameId == savedFrame.frameId }
            if (frameIndex == -1) return@forEach

            val frame = currentFrames[frameIndex]
            val restoredFaces = savedFrame.faces.map { savedFace ->
                FaceData(
                    faceId = savedFace.faceId,
                    trackingId = savedFace.trackingId,
                    boundingBox = RectF(savedFace.boundingBox),
                    effect = savedFace.effect
                )
            }
            currentFrames[frameIndex] = frame.copy(faces = restoredFaces.toMutableList())
        }

        _frames.value = currentFrames
        _intensity.value = snapshot.intensity
        _selectedEmoji.value = snapshot.selectedEmoji

        // Evict cache and re-render current frame with restored state
        frameCache.evictAll()
        viewModelScope.launch(Dispatchers.IO) {
            loadBitmapForFrameDirect(_currentFrameIndex.value)
            prefetchWindow(
                startIndex = _currentFrameIndex.value,
                totalFrames = currentFrames.size
            )
        }
    }

    private fun updateUndoRedoFlags() {
        _canUndo.value = undoStack.size > 1
        _canRedo.value = redoStack.isNotEmpty()
    }
    fun undo() {
        if (undoStack.size <= 1) return
        val current = undoStack.removeLast()
        redoStack.addLast(current)
        restoreSnapshot(undoStack.last())
        updateUndoRedoFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        restoreSnapshot(next)
        updateUndoRedoFlags()
    }
    fun syncFaceSelection(indices: Set<Int>) {
        val previousSelection = _selectedFaceIndices.value
        val newlyAdded = indices - previousSelection   // faces just selected this call

        _selectedFaceIndices.value = indices

        if (newlyAdded.isNotEmpty()) {
            applyActiveEffectToFaces(newlyAdded)
        }
    }
    private fun applyActiveEffectToFaces(faceIndices: Set<Int>) {
        val frameList = _frames.value.toMutableList()
        val currentFrame = frameList.getOrNull(_currentFrameIndex.value) ?: return
        val effect = _activeEffect.value

        val trackingIds = faceIndices
            .mapNotNull { currentFrame.faces.getOrNull(it)?.trackingId }
            .filter { it != -1 }

        if (trackingIds.isEmpty()) return

        pushSnapshot()

        trackingIds.forEach { trackingId ->
            applyEffectUseCase(frameList, trackingId, effect)
        }

        _frames.value = frameList
        frameCache.evictAll()

        viewModelScope.launch(Dispatchers.IO) {
            loadBitmapForFrameDirect(_currentFrameIndex.value)
            prefetchWindow(startIndex = _currentFrameIndex.value, totalFrames = frameList.size)
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        prefetchJob?.cancel()
        frameCache.evictAll()
        repository.close()
        framesOutputDir?.let { FrameExtractor.clearFrames(it) }

    }
}
