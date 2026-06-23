package com.example.blurshieldapp.ui.home

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.blurshieldapp.R
import com.example.blurshieldapp.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var currentBitmap: android.graphics.Bitmap? = null
    private var currentUri: Uri? = null
    private var faceCount = 0

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        currentUri = uri
        val bitmap = MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
        currentBitmap = bitmap
        binding.photoCanvas.setImageBitmap(bitmap)
        binding.emptyState.isVisible = false
        binding.photoCanvas.overlayView.isVisible = false
        binding.tvFaceCount.isVisible = false
        binding.btnProceed.isVisible = false
        binding.btnDetect.isEnabled = true
        faceCount = 0
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        findNavController().navigate(
            R.id.videoEditFragment,
            Bundle().apply { putString("videoUri", uri.toString()) }
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPickImage.setOnClickListener {
            pickImage.launch("image/*")
        }
        binding.btnPickVideo.setOnClickListener {
            pickVideo.launch("video/*")
        }

        binding.btnDetect.setOnClickListener {
            val bitmap = currentBitmap ?: return@setOnClickListener
            runDetection(bitmap)
        }

        binding.btnProceed.setOnClickListener {
            val uri = currentUri?.toString() ?: return@setOnClickListener
            val action = HomeFragmentDirections.actionHomeToEdit(uri)
            findNavController().navigate(action)
        }
    }

    private fun runDetection(bitmap: android.graphics.Bitmap) {
        binding.progress.isVisible = true
        binding.btnDetect.isEnabled = false

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .build()

        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        detector.process(image)
            .addOnSuccessListener { faces ->
                binding.progress.isVisible = false
                faceCount = faces.size

                if (faces.isEmpty()) {
                    binding.tvFaceCount.text = "No faces detected"
                    binding.tvFaceCount.isVisible = true
                    binding.btnProceed.isVisible = false
                } else {
                    binding.photoCanvas.overlayView.isVisible = true

                    binding.photoCanvas.post {
                        binding.photoCanvas.overlayView.setFaces(faces, bitmap.width, bitmap.height)
                    }
                    binding.tvFaceCount.text = "${faces.size} face(s) detected — tap to select"
                    binding.tvFaceCount.isVisible = true
                    binding.btnProceed.isVisible = true
                }
            }
            .addOnFailureListener {
                binding.progress.isVisible = false
                binding.btnDetect.isEnabled = true
                binding.tvFaceCount.text = "Detection failed: ${it.message}"
                binding.tvFaceCount.isVisible = true
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}