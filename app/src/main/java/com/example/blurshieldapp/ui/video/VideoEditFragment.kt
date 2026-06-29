package com.example.blurshieldapp.ui.video

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.blurshieldapp.data.model.FaceEffect
import com.example.blurshieldapp.data.model.VideoEditorState
import com.example.blurshieldapp.databinding.FragmentVideoEditBinding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.blurshieldapp.data.model.FaceData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File


class VideoEditFragment : Fragment() {

    private var _binding: FragmentVideoEditBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoEditorViewModel by viewModels()
    private var isSeeking = false

    private var pendingFaces: List<FaceData> = emptyList()
    private var pendingBitmapSize: Pair<Int, Int> = 0 to 0

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val path = getRealPath(uri) ?: return@registerForActivityResult
        viewModel.preprocessVideo(path, requireContext().cacheDir)
    }
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
        setupSurfaceView()
        setupFaceOverlay()
        setupControls()
        observeViewModel()
    }

    // AFTER
    private fun setupSurfaceView() {
        binding.surfaceView.onTransformUpdated = { matrix ->
            // matrix is always fresh here — fires AFTER layout pass completes
            binding.faceOverlay.updateTransform(matrix)

            // Update face rects using the pending data stored by the bitmap collector
            val (bitmapW, bitmapH) = pendingBitmapSize
            if (bitmapW > 0 && bitmapH > 0) {
                binding.faceOverlay.setFacesFromRects(
                    rects = pendingFaces.map { it.boundingBox },
                    bitmapWidth = bitmapW,
                    bitmapHeight = bitmapH
                )
            }
        }
    }

    private fun setupFaceOverlay() {
        binding.faceOverlay.isClickable = true
        binding.faceOverlay.isFocusable = true

        binding.faceOverlay.onFaceSelectionChanged = { selected ->
            viewModel.syncFaceSelection(selected)
                binding.effectsLayout.visibility =
                    if (selected.isNotEmpty()) View.VISIBLE else View.GONE
        }

        binding.faceOverlay.onBoxAdjusted = { faceIndex, newRect ->
            viewModel.updateFaceBox(viewModel.currentFrameIndex.value, faceIndex, newRect)
        }
    }

    private fun setupControls() {
        binding.btnPickVideo.setOnClickListener {
            videoPicker.launch("video/*")
        }

        binding.btnPlayPause.setOnClickListener {
            if (viewModel.isPlaying.value) viewModel.pause()
            else viewModel.play()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.seekToFrame(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) { isSeeking = false }
        })

        binding.btnBlur.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.BLUR)
            binding.effectsLayout.visibility = View.GONE
        }
        binding.btnPixelate.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.PIXELATE)
            binding.effectsLayout.visibility = View.GONE
        }
        binding.btnBlackout.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.BLACKOUT)
            binding.effectsLayout.visibility = View.GONE
        }
        binding.btnEmoji.setOnClickListener {
            viewModel.applyEffectToSelected(FaceEffect.EMOJI)
            binding.effectsLayout.visibility = View.GONE
        }
    }

    private fun observeViewModel() {

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.editorState.collectLatest { state ->
                when (state) {
                    is VideoEditorState.Idle -> {
                        binding.preprocessingLayout.visibility = View.GONE
                    }
                    is VideoEditorState.Preprocessing -> {
                        binding.preprocessingLayout.visibility = View.VISIBLE
                        binding.tvPreprocessingMessage.text = state.message
                        binding.progressBar.progress = state.progress
                        binding.tvProgressPercent.text = "${state.progress}%"
                    }
                    is VideoEditorState.Ready -> {
                        binding.preprocessingLayout.visibility = View.GONE
                        binding.seekBar.max = state.totalFrames - 1
                    }
                    is VideoEditorState.Error -> {
                        binding.preprocessingLayout.visibility = View.GONE
                    }
                }
            }
        }

        // Collector 1: bitmap changes → draw to screen + update overlay
        // This is the source of truth for rendering. The bitmap is only set
        // AFTER the frame is ready, so we never draw a stale image.
        // AFTER
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentBitmap.collectLatest { bitmap ->
                bitmap ?: return@collectLatest
                val index = viewModel.currentFrameIndex.value
                val frames = viewModel.frames.value
                val frame = frames.getOrNull(index) ?: return@collectLatest

                // Store face data so onTransformUpdated callback can use it
                pendingFaces = frame.faces
                pendingBitmapSize = bitmap.width to bitmap.height

                // drawFrame triggers setImageBitmap → post { computeMatrix }
                // → onTransformUpdated fires with fresh matrix → overlay updates there
                binding.surfaceView.drawFrame(bitmap)
            }
        }

        // Collector 2: frame index changes → update seekbar + counter only
        // Kept separate because seekbar/counter are cheap UI updates,
        // not tied to whether the bitmap is ready yet.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentFrameIndex.collectLatest { index ->
                val frames = viewModel.frames.value
                if (!isSeeking) binding.seekBar.progress = index
                binding.tvFrameCounter.text = "Frame: $index / ${frames.size}"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isPlaying.collectLatest { playing ->
                binding.btnPlayPause.text = if (playing) "Pause" else "Play"
                binding.faceOverlay.setDrawingEnabled(!playing)
                if (playing) binding.effectsLayout.visibility = View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedFaceIndices.collectLatest { selected ->
                binding.faceOverlay.syncSelection(selected)
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
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}