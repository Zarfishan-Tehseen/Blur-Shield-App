package com.example.blurshieldapp.ui.video

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.blurshieldapp.utils.VideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class VideoEditViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(VideoEditUiState())
    val uiState: StateFlow<VideoEditUiState> = _uiState.asStateFlow()

    private val videoProcessor = VideoProcessor(application)

    fun setVideoUri(uri: Uri) {
        _uiState.update { it.copy(videoUri = uri, error = null, exportedVideoUri = null, progress = 0) }
        loadVideoMetadata(uri)
    }

    private fun loadVideoMetadata(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(getApplication(), uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 0L

                if (durationMs > 30_000L) {
                    _uiState.update {
                        it.copy(
                            durationMs = durationMs,
                            error = "Video exceeds 30 seconds. Please select a shorter video."
                        )
                    }
                } else {
                    _uiState.update { it.copy(durationMs = durationMs, error = null) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to load video metadata: ${e.localizedMessage}") }
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            }
        }
    }

    fun selectEffect(effect: VideoEffect) {
        _uiState.update { it.copy(selectedEffect = effect) }
    }

    fun selectEmoji(emoji: String) {
        _uiState.update { it.copy(selectedEmoji = emoji, selectedEffect = VideoEffect.EMOJI) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun exportVideo() {
        val currentState = _uiState.value
        val uri = currentState.videoUri ?: return
        if (currentState.error != null) return

        _uiState.update { it.copy(isProcessing = true, progress = 0) }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val outputDir = getApplication<Application>().cacheDir
                val outputFile = File(outputDir, "processed_video_${System.currentTimeMillis()}.mp4")

                videoProcessor.processVideo(
                    sourceUri = uri,
                    outputFile = outputFile,
                    effect = currentState.selectedEffect,
                    emojiChar = currentState.selectedEmoji,
                    onProgress = { progress ->
                        _uiState.update { it.copy(progress = progress) }
                    }
                )

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        exportedVideoUri = Uri.fromFile(outputFile)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        error = "Video processing failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}
//