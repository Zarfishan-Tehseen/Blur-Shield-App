package com.example.blurshieldapp.ui.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.example.blurshieldapp.R
import com.example.blurshieldapp.databinding.FragmentVideoEditBinding
import com.example.blurshieldapp.utils.FaceEffect
import com.example.blurshieldapp.utils.VideoProcessor
import kotlinx.coroutines.launch

class VideoEditFragment : Fragment() {

    private var _binding: FragmentVideoEditBinding? = null
    private val binding get() = _binding!!

    private val videoUri: String by lazy {
        arguments?.getString("videoUri") ?: ""
    }

    private val viewModel: VideoEditViewModel by viewModels()

    // Track last preview bitmap to avoid redundant canvas updates
    private var lastPreviewBitmap: android.graphics.Bitmap? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        viewModel.loadVideoIfNeeded(requireContext(), videoUri)

        setupListeners()
        observeState()
    }

    private fun setupListeners() {

        binding.btnPlayPause.setOnClickListener {
            viewModel.togglePlayback(requireContext())
        }
        binding.sliderTimeline.addOnSliderTouchListener(
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    // Pause the video immediately when user grabs the handle
                    viewModel.pauseVideo()
                }
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    val state = viewModel.uiState.value
                    val positionMs = (slider.value / 100f * state.durationMs).toLong()
                    viewModel.seekPreviewTo(requireContext(), positionMs)
                }
            }
        )

        binding.btnDetectFaces.setOnClickListener {
            viewModel.detectFacesOnKeyframes(requireContext())
        }

        binding.toggleEffect.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val effect = when (checkedId) {
                R.id.btn_blur      -> FaceEffect.BLUR
                R.id.btn_pixelate  -> FaceEffect.PIXELATE
                R.id.btn_blackout  -> FaceEffect.BLACKOUT
                R.id.btn_emoji     -> FaceEffect.EMOJI
                else               -> FaceEffect.BLUR
            }
            viewModel.onEffectChanged(effect)
        }

        listOf(binding.emoji1, binding.emoji2, binding.emoji3, binding.emoji4)
            .forEach { v ->
                v.setOnClickListener {
                    viewModel.onEmojiSelected((v as TextView).text.toString())
                }
            }

        binding.sliderIntensity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.onIntensityChanged(value)
        }

        binding.sliderQuality.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.onKeyframeIntervalChanged(value)
        }

        binding.btnProcess.setOnClickListener {
            val state = viewModel.uiState.value
            viewModel.onProcessingStarted()

            viewLifecycleOwner.lifecycleScope.launch {
                val outputUri = VideoProcessor.processVideo(
                    context = requireContext(),
                    state = state,
                    getInterpolatedBoxes = { timestampMs ->
                        viewModel.getInterpolatedBoxes(timestampMs)
                    },
                    onProgress = { progress ->
                        viewModel.updateProcessingProgress(progress)
                    }
                )

                if (outputUri != null) {
                    viewModel.onProcessingCompleted(outputUri)
                    com.google.android.material.snackbar.Snackbar
                        .make(binding.root, "Video saved to gallery!",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .show()
                } else {
                    viewModel.onProcessingFailed("Processing failed — please try again")
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: VideoEditUiState) {

        val previewBitmap = state.previewFrameBitmap
        if (previewBitmap != null && previewBitmap !== lastPreviewBitmap) {
            lastPreviewBitmap = previewBitmap
            binding.videoPreviewCanvas.setImageBitmap(previewBitmap)
        }

        val previewBoxes = viewModel.getInterpolatedBoxes(state.previewPositionMs)
        if (previewBitmap != null) {
            binding.videoPreviewCanvas.overlayView.setFacesFromRects(
                previewBoxes,
                previewBitmap.width,
                previewBitmap.height
            )
            binding.videoPreviewCanvas.overlayView.syncSelection(state.selectedFaces)
        }
        if (state.isPlaying) {
            binding.btnPlayPause.setIconResource(R.drawable.ic_pause)
        } else {
            binding.btnPlayPause.setIconResource(R.drawable.ic_play)
        }

        if (state.durationMs > 0) {
            val progress = (state.previewPositionMs.toFloat() / state.durationMs * 100f)
                .coerceIn(0f, 100f)
            if (binding.sliderTimeline.value != progress) {
                binding.sliderTimeline.value = progress
            }
            binding.tvCurrentTime.text = formatTime(state.previewPositionMs)
            binding.tvTotalTime.text = formatTime(state.durationMs)
        }

        binding.progressDetection.isVisible = state.isDetecting
        binding.tvDetectionStatus.isVisible = state.isDetecting
        if (state.isDetecting) {
            binding.tvDetectionStatus.text = "Detecting faces..."
        }
        binding.btnDetectFaces.isEnabled = !state.isDetecting && !state.isProcessing

        // ── Chips — rebuild only if face count changes
        val faceCount = state.keyframeBoxes[0L]?.size ?: 0
        if (binding.chipGroupFaces.childCount != faceCount) {
            buildChips(faceCount)
        }
        syncChips(state.selectedFaces)

        // ── Effect controls
        binding.emojiPresetRow.isVisible = (state.effect == FaceEffect.EMOJI)
        binding.sliderIntensity.isVisible = (state.effect != FaceEffect.EMOJI)
        if (binding.sliderIntensity.value != state.intensity) {
            binding.sliderIntensity.value = state.intensity
        }

        // ── Quality slider
        if (binding.sliderQuality.value != state.keyframeIntervalSec) {
            binding.sliderQuality.value = state.keyframeIntervalSec
        }

        // ── Process button — only enable after detection has run
        binding.btnProcess.isEnabled =
            state.keyframeBoxes.isNotEmpty() && !state.isProcessing && !state.isDetecting

        // ── Processing progress
        binding.processingProgressContainer.isVisible = state.isProcessing
        if (state.isProcessing) {
            val percent = (state.processingProgress * 100).toInt()
            binding.progressProcessing.progress = percent
            binding.tvProcessingStatus.text = "Processing... $percent%"
        }

        // ── Error
        state.error?.let { error ->
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, error, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Dismiss") { viewModel.clearError() }
                .show()
            viewModel.clearError()
        }
    }

    private fun buildChips(count: Int) {
        binding.chipGroupFaces.removeAllViews()
        repeat(count) { i ->
            val chip = Chip(requireContext()).apply {
                text = "Face ${i + 1}"
                isCheckable = true
                isChecked = i in viewModel.uiState.value.selectedFaces
                setOnCheckedChangeListener { _, checked ->
                    viewModel.onFaceSelected(i, checked)
                }
            }
            binding.chipGroupFaces.addView(chip)
        }
    }

    private fun syncChips(selected: Set<Int>) {
        for (i in 0 until binding.chipGroupFaces.childCount) {
            val chip = binding.chipGroupFaces.getChildAt(i) as? Chip
            val shouldBeChecked = i in selected
            if (chip?.isChecked != shouldBeChecked) chip?.isChecked = shouldBeChecked
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}