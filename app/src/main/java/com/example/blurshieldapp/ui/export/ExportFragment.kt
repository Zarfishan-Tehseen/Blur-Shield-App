package com.example.blurshieldapp.ui.export

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.example.blurshieldapp.R
import com.example.blurshieldapp.databinding.FragmentExportBinding
import com.example.blurshieldapp.ui.edit.EditViewModel
import com.example.blurshieldapp.utils.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ExportFragment : Fragment() {

    private var _binding: FragmentExportBinding? = null
    private val binding get() = _binding!!

    // Same nav_graph scope as EditFragment -> shares the prepared export bitmap
    private val viewModel: EditViewModel by navGraphViewModels(R.id.nav_graph)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnSaveToGallery.setOnClickListener { saveCurrentExport() }
        binding.btnShare.setOnClickListener { shareCurrentExport() }

        observeExportBitmap()
    }

    private fun observeExportBitmap() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.exportBitmap.collect { bmp ->
                    if (bmp != null) {
                        binding.progressBar.visibility = View.GONE
                        binding.imagePreview.setImageBitmap(bmp)
                    } else {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun saveCurrentExport() {
        val bmp = viewModel.exportBitmap.value ?: return
        binding.btnSaveToGallery.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                ImageProcessor.saveToGallery(requireContext(), bmp)
            }
            binding.btnSaveToGallery.isEnabled = true
            Toast.makeText(
                requireContext(),
                if (saved) "Saved to gallery!" else "Save failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareCurrentExport() {
        val bmp = viewModel.exportBitmap.value ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val cacheDir = File(requireContext().cacheDir, "shared_images").apply { mkdirs() }
                val file = File(cacheDir, "export_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                }
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share image"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Free the export bitmap reference so it's not held by VM after leaving
        viewModel.clearExport()
        _binding = null
    }
}