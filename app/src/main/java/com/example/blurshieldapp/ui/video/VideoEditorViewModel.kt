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
import com.example.blurshieldapp.data.model.FaceEffect
import com.example.blurshieldapp.data.model.FrameData
import com.example.blurshieldapp.data.model.VideoEditorState
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

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8  // use 1/8th of available memory
    private val frameCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }

    private var prefetchJob: Job? = null
    private var playbackJob: Job? = null
    private var frameIntervalMs = 33L

    private var framesOutputDir: String? = null

    // ─── Preprocessing ────────────────────────────────────────────────────────

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
        for (i in 0 until windowSize) {
            val targetIndex = startIndex + i
            if (targetIndex >= totalFrames) break  // ← stop at end, don't wrap
            if (frameCache.get(targetIndex) == null) {
                val frameData = framesList.getOrNull(targetIndex) ?: continue
                val originalBmp = BitmapFactory.decodeFile(frameData.framePath) ?: continue
                val processedBmp = applyEffectUseCase.renderFrame(originalBmp, frameData.faces)
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

            // Warm the cache before the loop starts so frame 1 never misses
            prefetchJob?.cancel()
            prefetchJob = launch(Dispatchers.IO) {
                prefetchWindow(
                    startIndex = _currentFrameIndex.value,
                    totalFrames = frameList.size
                )
            }
            prefetchJob?.join()

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

                // Get bitmap from cache or fall back to disk
                val bitmap = frameCache.get(nextIndex)
                    ?: withContext(Dispatchers.IO) {
                        loadBitmapForFrameDirect(nextIndex)
                        frameCache.get(nextIndex)
                    }

                if (bitmap != null) {
                    // *** CRITICAL ORDER ***
                    // Set index FIRST, then bitmap.
                    _currentFrameIndex.value = nextIndex
                    _currentBitmap.value = bitmap
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

                // Time-compensated sleep — absorbs processing jitter
                expectedTimeNs += frameIntervalNs
                val sleepNs = expectedTimeNs - System.nanoTime()
                when {
                    sleepNs > 0 -> delay(sleepNs / 1_000_000L)
                    sleepNs < -(frameIntervalNs * 3) -> {
                        // More than 3 frames behind — reset clock, don't burst-skip
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
        pause()
        _currentFrameIndex.value = index
        viewModelScope.launch(Dispatchers.IO) {
            loadBitmapForFrameDirect(index)
            prefetchWindow(startIndex = index, totalFrames = _frames.value.size)
        }
    }

    // ─── Internal bitmap loading ───────────────────────────────────────────────

    private suspend fun loadBitmapForFrameDirect(index: Int) = withContext(Dispatchers.IO) {
        val frameData = _frames.value.getOrNull(index) ?: return@withContext
        val path = frameData.framePath ?: return@withContext
        if (path.isBlank()) return@withContext
        val originalBmp = BitmapFactory.decodeFile(path) ?: return@withContext
        val processedBmp = applyEffectUseCase.renderFrame(originalBmp, frameData.faces)
        originalBmp.recycle()
        frameCache.put(index, processedBmp)
        _currentBitmap.value = processedBmp
    }

    // ─── Face selection ───────────────────────────────────────────────────────

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
        val frameList = _frames.value.toMutableList()
        val currentFrame = frameList.getOrNull(_currentFrameIndex.value) ?: return

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
        clearSelection()

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
    fun syncFaceSelection(indices: Set<Int>) {
        _selectedFaceIndices.value = indices
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
