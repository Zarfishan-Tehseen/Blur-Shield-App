package com.example.blurshieldapp.ui.edit

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.blurshieldapp.R
import com.example.blurshieldapp.databinding.FragmentEditBinding
import com.example.blurshieldapp.utils.FaceEffect
import com.example.blurshieldapp.utils.ImageProcessor
import com.example.blurshieldapp.view.BrushTool
import kotlinx.coroutines.launch

class EditFragment : Fragment() {

    private var _binding: FragmentEditBinding? = null
    private val binding get() = _binding!!

    private val args: EditFragmentArgs by navArgs()
    private val viewModel: EditViewModel by navGraphViewModels(R.id.nav_graph)
    //track undo/redo
    private var lastRestoredMask: Bitmap? = null

    // ── True once we have called setImageBitmap for the current URI.
    private var canvasImageSet = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val uriString = args.imageUri
        val isNewImage = viewModel.loadImageIfNeeded(uriString) {
            MediaStore.Images.Media.getBitmap(
                requireContext().contentResolver, uriString.toUri()
            )
        }

        if (isNewImage) {
            val bitmap = viewModel.uiState.value.originalBitmap!!
            // Update to pass the viewModel into layout initialization blocks
            binding.editCanvas.setImageBitmap(bitmap, viewModel)
            canvasImageSet = true
            detectFaces(bitmap)
        } else {
            val state = viewModel.uiState.value
            state.originalBitmap?.let { bmp ->
                binding.editCanvas.setImageBitmap(bmp, viewModel)
                canvasImageSet = true
                binding.editCanvas.post {
                    binding.editCanvas.restoreBrushLayers(state.maskBitmap, null)
                    lastRestoredMask = state.maskBitmap
                }
            }
        }

        setupListeners()
        observeState()
    }
    private fun detectFaces(bitmap: Bitmap) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .build()
        FaceDetection.getClient(options)
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces -> viewModel.setDetectedFaces(faces) }
    }
    private fun setupListeners() {
        // Effect toggle
        binding.toggleEffect.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val effect = when (checkedId) {
                R.id.btn_blur     -> FaceEffect.BLUR
                R.id.btn_pixelate -> FaceEffect.PIXELATE
                R.id.btn_blackout -> FaceEffect.BLACKOUT
                R.id.btn_emoji    -> FaceEffect.EMOJI
                else              -> FaceEffect.BLUR
            }
            viewModel.onEffectChanged(effect)
        }

        // Emoji preset row
        listOf(binding.emoji1, binding.emoji2, binding.emoji3, binding.emoji4).forEach { v ->
            v.setOnClickListener {
                viewModel.onEmojiSelected((v as TextView).text.toString())
            }
        }

        // Intensity slider
        binding.sliderIntensity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.onIntensityChanged(value)
        }
        binding.sliderIntensity.addOnSliderTouchListener(object :
            com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                viewModel.onIntensityChangeFinished()
            }
        })

        // Face overlay callbacks
        binding.editCanvas.overlayView.onBoxAdjusted = { index, rect ->
            viewModel.onBoxAdjusted(index, rect)
        }
        binding.editCanvas.overlayView.onBoxAdjustFinished = {
            viewModel.onBoxAdjustFinished()
        }
        binding.editCanvas.overlayView.onFaceSelectionChanged = { selected ->
            val current = viewModel.uiState.value.selectedFaces
            (selected - current).forEach { viewModel.onFaceSelected(it, true) }
            (current - selected).forEach { viewModel.onFaceSelected(it, false) }
        }

        // Preview / undo / redo
        binding.btnDoneEdit.setOnClickListener { viewModel.togglePreviewMode() }
        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnRedo.setOnClickListener { viewModel.redo() }

        // Brush controls
        binding.btnBrushMode.setOnClickListener { viewModel.toggleBrushEnabled() }

        binding.toggleBrushTool.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tool = if (checkedId == R.id.btn_brush_paint) BrushTool.PAINT else BrushTool.ERASE
            viewModel.onBrushToolChanged(tool)
        }

        binding.sliderBrushSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.onBrushRadiusChanged(value)
        }

        binding.btnBrushClear.setOnClickListener { viewModel.clearBrushLayers() }

        // Linked structural callbacks are initialized directly within the view layout now
        binding.editCanvas.brushView.onMaskStrokeFinished = { mask, pathPoints ->
            lastRestoredMask = mask
            viewModel.onMaskStrokeFinished(mask, pathPoints)
        }

        // Save / export
        binding.btnSave.setOnClickListener {
            viewModel.prepareExport { bmp, state -> compositeImage(bmp, state) }
            findNavController().navigate(R.id.exportFragment)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: EditUiState) {
        val bmp = state.originalBitmap ?: return

        // ── Face overlay ──
        binding.editCanvas.overlayView.setFacesFromRects(state.boxRects, bmp.width, bmp.height)
        binding.editCanvas.overlayView.syncSelection(state.selectedFaces)
        binding.editCanvas.overlayView.setDrawingEnabled(!state.isPreviewMode)

        // ── Chips ──
        if (binding.chipGroupFaces.childCount != state.boxRects.size) buildChips(state.boxRects.size)
        syncChips(state.selectedFaces)

        // ── Effect UI ──
        binding.emojiPresetRow.isVisible = (state.effect == FaceEffect.EMOJI)
        binding.sliderIntensity.isVisible = (state.effect != FaceEffect.EMOJI)
        if (binding.sliderIntensity.value != state.intensity) binding.sliderIntensity.value = state.intensity

        // ── Preview / undo / redo ──
        binding.btnDoneEdit.text = if (state.isPreviewMode) "Edit" else "Done"
        binding.btnUndo.isEnabled = state.canUndo
        binding.btnRedo.isEnabled = state.canRedo
        binding.btnUndo.alpha = if (state.canUndo) 1f else 0.4f
        binding.btnRedo.alpha = if (state.canRedo) 1f else 0.4f

        // ── Brush config sync ──
        binding.editCanvas.setBrushEnabled(state.isBrushEnabled)
        binding.brushControlsRow.isVisible = state.isBrushEnabled
        binding.btnBrushMode.alpha = if (state.isBrushEnabled) 1f else 0.7f
        binding.editCanvas.brushView.currentTool   = state.brushTool
        binding.editCanvas.brushView.currentEffect = state.effect
        binding.editCanvas.brushView.currentEmoji  = state.selectedEmoji
        binding.editCanvas.brushView.brushRadiusBitmapPx = state.brushRadius
        if (binding.sliderBrushSize.value != state.brushRadius) binding.sliderBrushSize.value = state.brushRadius

        // ── Undo/redo restore detection ──
        val snapMask = state.maskBitmap
        if (snapMask !== lastRestoredMask) {
            lastRestoredMask = snapMask
            binding.editCanvas.restoreBrushLayers(snapMask, null)
        }

        // ── Composite preview ──
        val liveMask = binding.editCanvas.getLiveMask()
        val finalBmp = compositeForPreview(bmp, state, liveMask)
        binding.editCanvas.updateImagePreservingMatrix(finalBmp)
    }

    private fun compositeForPreview(
        bmp: Bitmap,
        state: EditUiState,
        liveMask: Bitmap?
    ): Bitmap {
        val withFaces = if (state.selectedFaces.isEmpty()) {
            bmp
        } else {
            ImageProcessor.applyEffect(
                context = requireContext(), original = bmp, boxes = state.boxRects,
                selectedIndices = state.selectedFaces, effect = state.effect,
                intensity = state.intensity, emoji = state.selectedEmoji
            )
        }

        // Throttling layer: Skip heavy operations while actively tracking touch paths
        if (binding.editCanvas.brushView.isDrawing) {
            return withFaces
        }

        return ImageProcessor.applyBrushMaskEffect(
            context     = requireContext(),
            base        = withFaces,
            mask        = liveMask,
            effect      = state.effect,
            intensity   = state.intensity,
            emoji       = state.selectedEmoji,
            brushRadius = state.brushRadius,
            strokePaths = state.strokePaths
        )
    }

    private fun compositeImage(bmp: Bitmap, state: EditUiState): Bitmap {
        val liveMask = binding.editCanvas.getLiveMask()
        return compositeForPreview(bmp, state, liveMask)
    }

    private fun buildChips(count: Int) {
        binding.chipGroupFaces.removeAllViews()
        repeat(count) { i ->
            val chip = Chip(requireContext()).apply {
                text = "Face ${i + 1}"
                isCheckable = true
                setOnCheckedChangeListener { _, checked -> viewModel.onFaceSelected(i, checked) }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}