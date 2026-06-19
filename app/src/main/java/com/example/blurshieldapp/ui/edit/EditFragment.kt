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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.blurshieldapp.R
import com.example.blurshieldapp.databinding.FragmentEditBinding
import com.example.blurshieldapp.utils.FaceEffect
import com.example.blurshieldapp.utils.ImageProcessor

class EditFragment : Fragment() {

    private var _binding: FragmentEditBinding? = null
    private val binding get() = _binding!!

    private val args: EditFragmentArgs by navArgs()

    private var originalBitmap: Bitmap? = null
    private var detectedFaces: List<Face> = emptyList()
    private var currentEffect = FaceEffect.BLUR
    private var isPreviewMode = false
    private var selectedEmoji = "😀"


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Toolbar back
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.btnDoneEdit.setOnClickListener {
            isPreviewMode = !isPreviewMode
            binding.editCanvas.overlayView.setDrawingEnabled(!isPreviewMode)
            binding.btnDoneEdit.text = if (isPreviewMode) "Edit" else "Done"

            // Optionally hide the selection/effect controls while previewing
            binding.chipGroupFaces.isEnabled = !isPreviewMode
            binding.toggleEffect.isEnabled = !isPreviewMode
            binding.sliderIntensity.isEnabled = !isPreviewMode

            // Re-render the canvas to reflect box visibility change immediately
            binding.editCanvas.overlayView.invalidate()
        }

        // Load bitmap + re-detect
        val uri = args.imageUri.toUri()
        val bitmap = MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
        originalBitmap = bitmap
        binding.editCanvas.setImageBitmap(bitmap)

        detectFaces(bitmap)

        // Effect toggle
        binding.toggleEffect.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentEffect = when (checkedId) {
                R.id.btn_blur -> FaceEffect.BLUR
                R.id.btn_pixelate -> FaceEffect.PIXELATE
                R.id.btn_blackout -> FaceEffect.BLACKOUT
                R.id.btn_emoji -> FaceEffect.EMOJI
                else -> FaceEffect.BLUR
            }
            binding.emojiPresetRow.isVisible = (currentEffect == FaceEffect.EMOJI)
            binding.sliderIntensity.isVisible = (currentEffect != FaceEffect.EMOJI)
            applyEffect()
        }
        binding.toggleEffect.check(R.id.btn_blur)

        val emojiViews = listOf(binding.emoji1, binding.emoji2, binding.emoji3, binding.emoji4)
        emojiViews.forEach { view ->
            view.setOnClickListener {
                selectedEmoji = (view as TextView).text.toString()
                emojiViews.forEach { it.alpha = 0.4f }
                view.alpha = 1f
                applyEffect()
            }
        }
        // highlight default selection on load
        binding.emoji1.alpha = 1f
        binding.emoji2.alpha = 0.4f
        binding.emoji3.alpha = 0.4f
        binding.emoji4.alpha = 0.4f

        binding.editCanvas.overlayView.onBoxAdjusted = { _, _ ->
            applyEffect()
        }

        // Slider
        binding.sliderIntensity.addOnChangeListener { _, _, _ -> applyEffect() }

        // Face overlay tap → sync chips
        binding.editCanvas.overlayView.onFaceSelectionChanged = { selected ->
            syncChips(selected)
            applyEffect()
        }

        // Save
        binding.btnSave.setOnClickListener {
            val result = getEffectBitmap() ?: return@setOnClickListener
            val saved = ImageProcessor.saveToGallery(requireContext(), result)
            Toast.makeText(
                requireContext(),
                if (saved) "Saved to gallery!" else "Save failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun detectFaces(bitmap: Bitmap) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .build()

        FaceDetection.getClient(options)
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                detectedFaces = faces
                binding.editCanvas.overlayView.setFaces(faces, bitmap.width, bitmap.height)
                buildChips(faces)
            }
    }

    private fun buildChips(faces: List<Face>) {
        binding.chipGroupFaces.removeAllViews()
        faces.forEachIndexed { i, _ ->
            val chip = Chip(requireContext()).apply {
                text = "Face ${i + 1}"
                isCheckable = true
                isChecked = false
                setOnCheckedChangeListener { _, checked ->
                    binding.editCanvas.overlayView.selectFace(i, checked)
                    applyEffect()
                }
            }
            binding.chipGroupFaces.addView(chip)
        }
    }

    private fun syncChips(selectedIndices: Set<Int>) {
        for (i in 0 until binding.chipGroupFaces.childCount) {
            val chip = binding.chipGroupFaces.getChildAt(i) as? Chip
            chip?.isChecked = i in selectedIndices
        }
    }

    private fun applyEffect() {
        val bmp = originalBitmap ?: return
        val result = if (binding.editCanvas.overlayView.selectedFaces.isEmpty()) {
            bmp
        } else {
            getEffectBitmap() ?: bmp
        }
        binding.editCanvas.updateImagePreservingMatrix(result)
    }

    private fun getEffectBitmap(): Bitmap? {
        val bmp = originalBitmap ?: return null
        val boxes = detectedFaces.indices.map {
            binding.editCanvas.overlayView.getBoxRect(it) ?: RectF()
        }
        return ImageProcessor.applyEffect(
            context = requireContext(),
            original = bmp,
            boxes = boxes,
            selectedIndices = binding.editCanvas.overlayView.selectedFaces.toSet(),
            effect = currentEffect,
            intensity = binding.sliderIntensity.value,
            emoji = selectedEmoji

        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}