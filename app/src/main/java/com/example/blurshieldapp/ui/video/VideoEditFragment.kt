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
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.io.File

class VideoEditFragment : Fragment() {

    private var _binding: FragmentVideoEditBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoEditorViewModel by viewModels()
    private var isSeeking = false
    private var currentEffect: FaceEffect = FaceEffect.BLUR

    private var lastChipCount = -1

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
    private fun setupCanvas() {
        binding.videoCanvas.onTransformUpdated = { _ -> }
    }
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
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
                viewModel.pause()   // ← pause once when drag starts, not on every tick
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeeking = false
                // Optionally resume playback after seek if it was playing before
                // viewModel.play()
            }
        })

        // Effect buttons — apply effect AND update sub-row visibility
        binding.btnBlur.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.BLUR)
            updateEffectSubRows(FaceEffect.BLUR)
            highlightActiveEffectButton(FaceEffect.BLUR)
            binding.effectsLayout.visibility = View.VISIBLE  // keep panel open
        }
        binding.btnPixelate.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.PIXELATE)
            updateEffectSubRows(FaceEffect.PIXELATE)
            highlightActiveEffectButton(FaceEffect.PIXELATE)
            binding.effectsLayout.visibility = View.VISIBLE
        }
        binding.btnBlackout.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.BLACKOUT)
            updateEffectSubRows(FaceEffect.BLACKOUT)
            highlightActiveEffectButton(FaceEffect.BLACKOUT)
            binding.effectsLayout.visibility = View.VISIBLE
        }
        binding.btnEmoji.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.EMOJI)
            updateEffectSubRows(FaceEffect.EMOJI)
            highlightActiveEffectButton(FaceEffect.EMOJI)
            binding.effectsLayout.visibility = View.VISIBLE
        }

        // Intensity seekbar
        binding.seekBarIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.onIntensityChanged(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                viewModel.onIntensityChangeFinished()  // ← push snapshot on release
            }
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

        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnRedo.setOnClickListener { viewModel.redo() }
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
                if (frame.faces.size != lastChipCount) {
                    buildChips(frame.faces.size)
                }
                binding.chipsRow.visibility = if (frame.faces.isNotEmpty()) View.VISIBLE else View.GONE
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
                if (playing) {
                    binding.effectsLayout.visibility = View.GONE
                    binding.chipsRow.visibility = View.GONE
                } else if (viewModel.frames.value.getOrNull(viewModel.currentFrameIndex.value)?.faces?.isNotEmpty() == true) {
                    binding.chipsRow.visibility = View.VISIBLE
                }
            }
        }

        // Face selection collector
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedFaceIndices.collectLatest { selected ->
                binding.videoCanvas.overlayView.syncSelection(selected)
                syncChips(selected)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isPreviewMode.collectLatest { isPreview ->
                binding.btnDoneEdit.text = if (isPreview) "Edit" else "Done"
                binding.videoCanvas.overlayView.visibility =
                    if (isPreview) View.GONE else View.VISIBLE
                if (isPreview) {
                    binding.effectsLayout.visibility = View.GONE
                    binding.chipsRow.visibility = View.GONE
                }
            }
        }
        // Undo state — show/hide the whole row based on whether any history exists
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.canUndo.collectLatest { canUndo ->
                // Show undo/redo row only when there's history
                binding.undoRedoLayout.visibility =
                    if (canUndo || viewModel.canRedo.value) View.VISIBLE else View.GONE
                binding.btnUndo.isEnabled = canUndo
                binding.btnUndo.alpha = if (canUndo) 1f else 0.4f
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.canRedo.collectLatest { canRedo ->
                binding.undoRedoLayout.visibility =
                    if (viewModel.canUndo.value || canRedo) View.VISIBLE else View.GONE
                binding.btnRedo.isEnabled = canRedo
                binding.btnRedo.alpha = if (canRedo) 1f else 0.4f
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeEffect.collectLatest { effect ->
                highlightActiveEffectButton(effect)
                currentEffect = effect
            }
        }
    }
    private fun getRealPath(uri: Uri): String? {
        val tempFile = File(requireContext().cacheDir, "input_video_temp.mp4")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { input.copyTo(it) }
        }
        return tempFile.absolutePath
    }
    private fun highlightActiveEffectButton(effect: FaceEffect) {
        val activeColor = android.graphics.Color.parseColor("#4CAF50")
        val inactiveColor = android.graphics.Color.parseColor("#9E9E9E")

        binding.btnBlur.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (effect == FaceEffect.BLUR) activeColor else inactiveColor
        )
        binding.btnPixelate.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (effect == FaceEffect.PIXELATE) activeColor else inactiveColor
        )
        binding.btnBlackout.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (effect == FaceEffect.BLACKOUT) activeColor else inactiveColor
        )
        binding.btnEmoji.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (effect == FaceEffect.EMOJI) activeColor else inactiveColor
        )
    }
    private fun buildChips(count: Int) {
        binding.chipGroupFaces.removeAllViews()
        repeat(count) { i ->
            val chip = Chip(requireContext()).apply {
                text = "Face ${i + 1}"
                isCheckable = true
                setOnCheckedChangeListener { _, checked ->
                    val current = viewModel.selectedFaceIndices.value.toMutableSet()
                    if (checked) current.add(i) else current.remove(i)
                    viewModel.syncFaceSelection(current)
                }
            }
            binding.chipGroupFaces.addView(chip)
        }
        lastChipCount = count
    }

    private fun syncChips(selected: Set<Int>) {
        for (i in 0 until binding.chipGroupFaces.childCount) {
            val chip = binding.chipGroupFaces.getChildAt(i) as? Chip
            val shouldBeChecked = i in selected
            if (chip?.isChecked != shouldBeChecked) chip?.isChecked = shouldBeChecked
        }
    }
}