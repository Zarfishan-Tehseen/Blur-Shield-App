package com.example.blurshieldapp.ui.video

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.blurshieldapp.data.model.FaceEffect
import com.example.blurshieldapp.data.model.VideoEditorState
import com.example.blurshieldapp.databinding.FragmentVideoEditBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class VideoEditFragment : Fragment() {

    private var _binding: FragmentVideoEditBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoEditorViewModel by viewModels()
    private var isSeeking = false
    private var currentEffect: FaceEffect = FaceEffect.BLUR

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val path = getRealPath(uri) ?: return@registerForActivityResult
        viewModel.preprocessVideo(path, requireContext().cacheDir)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCanvas()
        setupFaceOverlay()
        setupControls()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Canvas setup ───────────────────────────────────────────────────────────
    // Touch routing, zoom, pan, and overlay sync all happen inside
    // VideoEditCanvasView — nothing to coordinate here in the fragment.

    private fun setupCanvas() {
        // onTransformUpdated is available for future use (export, minimap, etc.)
        // Overlay is already updated directly inside VideoEditCanvasView.applyMatrix()
        // so no action needed here right now.
        binding.videoCanvas.onTransformUpdated = { _ -> }
    }

    // ── Face overlay setup ─────────────────────────────────────────────────────

    private fun setupFaceOverlay() {
        binding.videoCanvas.overlayView.onFaceSelectionChanged = { selected ->
            viewModel.syncFaceSelection(selected)
            if (selected.isNotEmpty()) {
                binding.effectsLayout.visibility = View.VISIBLE
                updateEffectSubRows(currentEffect)  // restore correct sub-row state
            } else {
                binding.effectsLayout.visibility = View.GONE
            }
        }
        binding.videoCanvas.overlayView.onBoxAdjusted = { faceIndex, newRect ->
            viewModel.updateFaceBox(viewModel.currentFrameIndex.value, faceIndex, newRect)
        }
    }

    // ── Controls setup ─────────────────────────────────────────────────────────

    // Helper to update which sub-rows are visible based on active effect
    private fun updateEffectSubRows(effect: FaceEffect) {
        currentEffect = effect
        val isEmoji = effect == FaceEffect.EMOJI
        binding.emojiPickerRow.visibility = if (isEmoji) View.VISIBLE else View.GONE
        binding.intensityRow.visibility   = if (isEmoji) View.GONE else View.VISIBLE
    }

    private fun setupControls() {
        binding.btnPickVideo.setOnClickListener { videoPicker.launch("video/*") }

        binding.btnPlayPause.setOnClickListener {
            if (viewModel.isPlaying.value) viewModel.pause() else viewModel.play()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.seekToFrame(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) { isSeeking = false }
        })

        // Effect buttons — apply effect AND update sub-row visibility
        binding.btnBlur.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.BLUR)
            updateEffectSubRows(FaceEffect.BLUR)
            binding.effectsLayout.visibility = View.VISIBLE  // keep panel open
        }
        binding.btnPixelate.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.PIXELATE)
            updateEffectSubRows(FaceEffect.PIXELATE)
            binding.effectsLayout.visibility = View.VISIBLE
        }
        binding.btnBlackout.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.BLACKOUT)
            updateEffectSubRows(FaceEffect.BLACKOUT)
            binding.effectsLayout.visibility = View.VISIBLE
        }
        binding.btnEmoji.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.EMOJI)
            updateEffectSubRows(FaceEffect.EMOJI)
            binding.effectsLayout.visibility = View.VISIBLE
        }

        // Intensity seekbar
        binding.seekBarIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.onIntensityChanged(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Emoji picker
        listOf(
            binding.emojiOpt1,
            binding.emojiOpt2,
            binding.emojiOpt3,
            binding.emojiOpt4,
            binding.emojiOpt5
        ).forEach { tv ->
            tv.setOnClickListener {
                viewModel.onEmojiSelected((tv as android.widget.TextView).text.toString())
            }
        }

        binding.btnDoneEdit.setOnClickListener { viewModel.togglePreviewMode() }
        binding.btnResetZoom.setOnClickListener { binding.videoCanvas.resetZoom() }
    }

    // ── ViewModel observers ────────────────────────────────────────────────────

    private fun observeViewModel() {

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.editorState.collectLatest { state ->
                when (state) {
                    is VideoEditorState.Idle ->
                        binding.preprocessingLayout.visibility = View.GONE

                    is VideoEditorState.Preprocessing -> {
                        binding.preprocessingLayout.visibility = View.VISIBLE
                        binding.tvPreprocessingMessage.text = state.message
                        binding.progressBar.progress = state.progress
                        binding.tvProgressPercent.text = "${state.progress}%"
                    }

                    is VideoEditorState.Ready -> {
                        binding.preprocessingLayout.visibility = View.GONE
                        binding.seekBar.max = state.totalFrames - 1
                        binding.videoCanvas.setZoomEnabled(true)
                    }

                    is VideoEditorState.Error ->
                        binding.preprocessingLayout.visibility = View.GONE
                }
            }
        }

        // Bitmap collector — draw frame + update overlay face rects
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentBitmap.collectLatest { bitmap ->
                bitmap ?: return@collectLatest
                val index = viewModel.currentFrameIndex.value
                val frame = viewModel.frames.value.getOrNull(index) ?: return@collectLatest

                binding.videoCanvas.drawFrame(bitmap)

                binding.videoCanvas.overlayView.setFacesFromRects(
                    rects = frame.faces.map { it.boundingBox },
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height
                )
            }
        }

        // Frame index collector — seekbar + counter only
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentFrameIndex.collectLatest { index ->
                val frames = viewModel.frames.value
                if (!isSeeking) binding.seekBar.progress = index
                binding.tvFrameCounter.text = "Frame: $index / ${frames.size}"
            }
        }

        // Playback state collector
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isPlaying.collectLatest { playing ->
                binding.btnPlayPause.text = if (playing) "Pause" else "Play"
                binding.videoCanvas.overlayView.setDrawingEnabled(!playing)
                if (playing) binding.effectsLayout.visibility = View.GONE
            }
        }

        // Face selection collector
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedFaceIndices.collectLatest { selected ->
                binding.videoCanvas.overlayView.syncSelection(selected)
            }
        }

        // Preview / Edit mode collector
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isPreviewMode.collectLatest { isPreview ->
                binding.btnDoneEdit.text = if (isPreview) "Edit" else "Done"
                binding.videoCanvas.overlayView.visibility =
                    if (isPreview) View.GONE else View.VISIBLE
                if (isPreview) binding.effectsLayout.visibility = View.GONE
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun getRealPath(uri: Uri): String? {
        val tempFile = File(requireContext().cacheDir, "input_video_temp.mp4")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { input.copyTo(it) }
        }
        return tempFile.absolutePath
    }
}