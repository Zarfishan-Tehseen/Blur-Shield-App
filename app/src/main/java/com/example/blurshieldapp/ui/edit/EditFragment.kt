package com.example.blurshieldapp.ui.edit

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.launch

class EditFragment : Fragment() {

    private var _binding: FragmentEditBinding? = null
    private val binding get() = _binding!!

    private val args: EditFragmentArgs by navArgs()

    // Shared, nav-graph-scoped ViewModel — survives across this flow, cleared when flow exits
    private val viewModel: EditViewModel by navGraphViewModels(R.id.nav_graph)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        // Load + detect only once (ViewModel survives rotation, guard against re-loading)
        if (viewModel.uiState.value.originalBitmap == null) {
            val uri = args.imageUri.toUri()
            val bitmap = MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
            viewModel.setOriginalImage(bitmap)
            detectFaces(bitmap)
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
            .addOnSuccessListener { faces ->
                viewModel.setDetectedFaces(faces)
            }
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

        // Emoji presets
        val emojiViews = listOf(binding.emoji1, binding.emoji2, binding.emoji3, binding.emoji4)
        emojiViews.forEach { v ->
            v.setOnClickListener {
                viewModel.onEmojiSelected((v as TextView).text.toString())
            }
        }

        // Slider — live update while dragging, snapshot only when finger lifts
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

        // Box drag — live update while dragging, snapshot on release
        binding.editCanvas.overlayView.onBoxAdjusted = { index, rect ->
            viewModel.onBoxAdjusted(index, rect)
        }
        // NOTE: requires FaceOverlayView to also expose an "onAdjustFinished" callback
        // fired on ACTION_UP after a handle drag — see note below.
        binding.editCanvas.overlayView.onBoxAdjustFinished = {
            viewModel.onBoxAdjustFinished()
        }

        // Face selection
        binding.editCanvas.overlayView.onFaceSelectionChanged = { selected ->
            // Diff against ViewModel's current selection to call onFaceSelected per change
            val current = viewModel.uiState.value.selectedFaces
            val added = selected - current
            val removed = current - selected
            added.forEach { viewModel.onFaceSelected(it, true) }
            removed.forEach { viewModel.onFaceSelected(it, false) }
        }

        // Done/Edit toggle
        binding.btnDoneEdit.setOnClickListener {
            viewModel.togglePreviewMode()
        }

        // Undo / Redo
        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnRedo.setOnClickListener { viewModel.redo() }

        // Save
        binding.btnSave.setOnClickListener {
            val state = viewModel.uiState.value
            val bmp = state.originalBitmap ?: return@setOnClickListener
            val result = ImageProcessor.applyEffect(
                context = requireContext(),
                original = bmp,
                boxes = state.boxRects,
                selectedIndices = state.selectedFaces,
                effect = state.effect,
                intensity = state.intensity,
                emoji = state.selectedEmoji
            )
            val saved = ImageProcessor.saveToGallery(requireContext(), result)
            Toast.makeText(
                requireContext(),
                if (saved) "Saved to gallery!" else "Save failed",
                Toast.LENGTH_SHORT
            ).show()
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

        // Render boxes
        binding.editCanvas.overlayView.setFacesFromRects(state.boxRects, bmp.width, bmp.height)
        binding.editCanvas.overlayView.syncSelection(state.selectedFaces)
        binding.editCanvas.overlayView.setDrawingEnabled(!state.isPreviewMode)

        // Render chips (rebuild only if count changed, to avoid wiping mid-interaction)
        if (binding.chipGroupFaces.childCount != state.boxRects.size) {
            buildChips(state.boxRects.size)
        }
        syncChips(state.selectedFaces)

        // Render effect controls
        binding.emojiPresetRow.isVisible = (state.effect == FaceEffect.EMOJI)
        binding.sliderIntensity.isVisible = (state.effect != FaceEffect.EMOJI)
        if (binding.sliderIntensity.value != state.intensity) {
            binding.sliderIntensity.value = state.intensity
        }

        // Render composited preview
        val composited = if (state.selectedFaces.isEmpty()) {
            bmp
        } else {
            ImageProcessor.applyEffect(
                context = requireContext(),
                original = bmp,
                boxes = state.boxRects,
                selectedIndices = state.selectedFaces,
                effect = state.effect,
                intensity = state.intensity,
                emoji = state.selectedEmoji
            )
        }
        binding.editCanvas.updateImagePreservingMatrix(composited)
        // First-time fit (only call setImageBitmap once on initial load — handled separately if needed)

        // Done/Edit button text
        binding.btnDoneEdit.text = if (state.isPreviewMode) "Edit" else "Done"

        // Undo/Redo button states
        binding.btnUndo.isEnabled = state.canUndo
        binding.btnRedo.isEnabled = state.canRedo
        binding.btnUndo.alpha = if (state.canUndo) 1f else 0.4f
        binding.btnRedo.alpha = if (state.canRedo) 1f else 0.4f
    }

    private fun buildChips(count: Int) {
        binding.chipGroupFaces.removeAllViews()
        repeat(count) { i ->
            val chip = Chip(requireContext()).apply {
                text = "Face ${i + 1}"
                isCheckable = true
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
            if (chip?.isChecked != shouldBeChecked) {
                chip?.isChecked = shouldBeChecked
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}