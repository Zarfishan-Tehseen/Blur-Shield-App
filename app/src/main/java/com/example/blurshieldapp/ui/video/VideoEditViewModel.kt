package com.example.blurshieldapp.ui.video


import android.content.Context
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blurshieldapp.ui.video.VideoEditUiState
import com.example.blurshieldapp.utils.FaceEffect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay

class VideoEditViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(VideoEditUiState())
    val uiState: StateFlow<VideoEditUiState> = _uiState.asStateFlow()
    private var currentVideoUri: String? = null
    private var playbackJob: Job? = null

    fun loadVideoIfNeeded(context: Context, uri: String) {
        if (uri == currentVideoUri) return
        currentVideoUri = uri
        reset()
        _uiState.update { it.copy(videoUri = uri) }
        extractVideoMetadata(context, uri)
    }

    private fun reset() {
        _uiState.value = VideoEditUiState()
    }
    private fun extractVideoMetadata(context: Context, uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(uri))

                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLong() ?: 0L

                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toInt() ?: 0

                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toInt() ?: 0

                val firstFrame = retriever.getFrameAtTime(0)
                retriever.release()

                _uiState.update {
                    it.copy(
                        durationMs = durationMs,
                        videoWidth = width,
                        videoHeight = height,
                        previewFrameBitmap = firstFrame,
                        previewPositionMs = 0L
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to load video: ${e.message}") }
            }
        }
    }

    // ── Preview frame scrubbing

    fun seekPreviewTo(context: Context, positionMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = currentVideoUri ?: return@launch
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                val frame = retriever.getFrameAtTime(
                    positionMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                _uiState.update {
                    it.copy(
                        previewFrameBitmap = frame,
                        previewPositionMs = positionMs
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to seek: ${e.message}") }
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        }
    }
    fun togglePlayback(context: android.content.Context) {
        val currentState = _uiState.value
        if (currentState.isPlaying) {
            pauseVideo()
        } else {
            playVideo(context)
        }
    }

    private fun playVideo(context: android.content.Context) {
        _uiState.update { it.copy(isPlaying = true) }

        // Run the entire loop on a background IO thread so it never blocks the UI
        playbackJob = viewModelScope.launch(Dispatchers.IO) {
            val frameDurationMs = 33L // ~30 frames per second
            val uri = currentVideoUri ?: return@launch

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))

                while (true) {
                    val currentState = _uiState.value
                    if (!currentState.isPlaying) break

                    var nextPosition = currentState.previewPositionMs + frameDurationMs

                    // Loop back to the beginning if the video ends
                    if (nextPosition >= currentState.durationMs) {
                        nextPosition = 0L
                    }

                    // Synchronously grab the frame on this background thread
                    val frame = retriever.getFrameAtTime(
                        nextPosition * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )

                    // Push the result smoothly to the UI state
                    _uiState.update {
                        it.copy(
                            previewFrameBitmap = frame,
                            previewPositionMs = nextPosition
                        )
                    }

                    // Pause before processing the next frame
                    delay(frameDurationMs)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Playback error: ${e.message}") }
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        }
    }
    fun pauseVideo() {
        playbackJob?.cancel()
        _uiState.update { it.copy(isPlaying = false) }
    }
    fun detectFacesOnKeyframes(context: Context) {
        val uri = currentVideoUri ?: return
        val duration = _uiState.value.durationMs
        val intervalMs = (_uiState.value.keyframeIntervalSec * 1000).toLong()

        _uiState.update { it.copy(isDetecting = true, keyframeBoxes = emptyMap()) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(uri))

                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setMinFaceSize(0.1f)
                    .build()
                val detector = FaceDetection.getClient(options)

                val keyframeResults = mutableMapOf<Long, List<RectF>>()
                var timestampMs = 0L

                while (timestampMs <= duration) {
                    val frame = retriever.getFrameAtTime(
                        timestampMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (frame != null) {
                        val image = InputImage.fromBitmap(frame, 0)
                        val faces = detector.process(image).await()
                        keyframeResults[timestampMs] = faces.map { RectF(it.boundingBox) }
                    }
                    timestampMs += intervalMs
                }

                retriever.release()

                val firstFrameFaces = keyframeResults[0L] ?: emptyList()

                _uiState.update {
                    it.copy(
                        isDetecting = false,
                        keyframeBoxes = keyframeResults,
                        selectedFaces = firstFrameFaces.indices.toSet()
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isDetecting = false, error = "Detection failed: ${e.message}")
                }
            }
        }
    }

    // ── Effect controls

    fun onFaceSelected(index: Int, selected: Boolean) {
        _uiState.update { state ->
            val newSelection = state.selectedFaces.toMutableSet().apply {
                if (selected) add(index) else remove(index)
            }
            state.copy(selectedFaces = newSelection)
        }
    }

    fun onEffectChanged(effect: FaceEffect) {
        _uiState.update { it.copy(effect = effect) }
    }

    fun onIntensityChanged(intensity: Float) {
        _uiState.update { it.copy(intensity = intensity) }
    }

    fun onEmojiSelected(emoji: String) {
        _uiState.update { it.copy(selectedEmoji = emoji) }
    }

    fun onKeyframeIntervalChanged(seconds: Float) {
        _uiState.update { it.copy(keyframeIntervalSec = seconds) }
    }
    fun getInterpolatedBoxes(frameTimestampMs: Long): List<RectF> {
        val keyframes = _uiState.value.keyframeBoxes
        if (keyframes.isEmpty()) return emptyList()

        val sortedKeys = keyframes.keys.sorted()

        if (keyframes.containsKey(frameTimestampMs)) {
            return keyframes[frameTimestampMs] ?: emptyList()
        }

        val prevKey = sortedKeys.lastOrNull { it <= frameTimestampMs }
        val nextKey = sortedKeys.firstOrNull { it > frameTimestampMs }

        if (prevKey == null) return keyframes[nextKey] ?: emptyList()
        if (nextKey == null) return keyframes[prevKey] ?: emptyList()

        val prevBoxes = keyframes[prevKey] ?: emptyList()
        val nextBoxes = keyframes[nextKey] ?: emptyList()

        if (prevBoxes.size != nextBoxes.size) return prevBoxes

        val t = (frameTimestampMs - prevKey).toFloat() / (nextKey - prevKey).toFloat()
        return prevBoxes.zip(nextBoxes).map { (a, b) -> interpolateBox(a, b, t) }
    }

    private fun interpolateBox(a: RectF, b: RectF, t: Float) = RectF(
        a.left + (b.left - a.left) * t,
        a.top + (b.top - a.top) * t,
        a.right + (b.right - a.right) * t,
        a.bottom + (b.bottom - a.bottom) * t
    )

    fun onProcessingStarted() {
        _uiState.update {
            it.copy(isProcessing = true, processingProgress = 0f, outputUri = null)
        }
    }

    fun onProcessingCompleted(outputUri: String) {
        _uiState.update {
            it.copy(isProcessing = false, processingProgress = 1f, outputUri = outputUri)
        }
    }

    fun onProcessingFailed(error: String) {
        _uiState.update { it.copy(isProcessing = false, error = error) }
    }

    fun updateProcessingProgress(progress: Float) {
        _uiState.update { it.copy(processingProgress = progress) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}