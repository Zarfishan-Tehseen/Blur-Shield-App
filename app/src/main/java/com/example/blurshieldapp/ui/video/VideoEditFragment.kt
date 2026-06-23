package com.example.blurshieldapp.ui.video

import android.graphics.*
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.blurshieldapp.databinding.FragmentVideoEditBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class VideoEditFragment : Fragment() {

    private val viewModel: VideoEditViewModel by viewModels()

    private var _binding: FragmentVideoEditBinding? = null
    private val binding get() = _binding!!

    private var mediaPlayer: MediaPlayer? = null
    private var isPlayerPrepared = false

    // ML Kit Detector for Live Preview
    private val previewDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
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

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        val uriString = arguments?.getString("videoUri")
        if (uriString.isNullOrEmpty()) {
            Toast.makeText(context, "No video source provided.", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        val videoUri = Uri.parse(uriString)
        viewModel.setVideoUri(videoUri)

        setupVideoPlayer(videoUri)
        setupActionListeners()
        observeUiState()

        // Asynchronously sample frames from TextureView at 5 FPS to draw live face edits
        startLiveFrameSampling()
    }

    private fun setupVideoPlayer(videoUri: Uri) {
        mediaPlayer = MediaPlayer().apply {
            isLooping = true
        }

        binding.videoTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(surfaceTexture)
                mediaPlayer?.setSurface(surface)
                try {
                    mediaPlayer?.setDataSource(requireContext(), videoUri)
                    mediaPlayer?.prepareAsync()
                    mediaPlayer?.setOnPreparedListener { mp ->
                        isPlayerPrepared = true
                        adjustAspectRatio(mp.videoWidth, mp.videoHeight)
                        mp.start()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load video: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                mediaPlayer?.setSurface(null)
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        val viewWidth = binding.cardVideoContainer.width
        val viewHeight = binding.cardVideoContainer.height
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val videoAspectRatio = videoWidth.toFloat() / videoHeight
        val containerAspectRatio = viewWidth.toFloat() / viewHeight

        val newWidth: Int
        val newHeight: Int
        if (videoAspectRatio > containerAspectRatio) {
            newWidth = viewWidth
            newHeight = (viewWidth / videoAspectRatio).toInt()
        } else {
            newWidth = (viewHeight * videoAspectRatio).toInt()
            newHeight = viewHeight
        }

        val textureParams = binding.videoTextureView.layoutParams
        textureParams.width = newWidth
        textureParams.height = newHeight
        binding.videoTextureView.layoutParams = textureParams

        val overlayParams = binding.overlayView.layoutParams
        overlayParams.width = newWidth
        overlayParams.height = newHeight
        binding.overlayView.layoutParams = overlayParams
    }

    private fun setupActionListeners() {
        binding.btnEffectNone.setOnClickListener { viewModel.selectEffect(VideoEffect.NONE) }
        binding.btnEffectBlur.setOnClickListener { viewModel.selectEffect(VideoEffect.BLUR) }
        binding.btnEffectPixelate.setOnClickListener { viewModel.selectEffect(VideoEffect.PIXELATE) }
        binding.btnEffectBlackout.setOnClickListener { viewModel.selectEffect(VideoEffect.BLACKOUT) }
        binding.btnEffectEmoji.setOnClickListener { viewModel.selectEffect(VideoEffect.EMOJI) }

        binding.emoji1.setOnClickListener { viewModel.selectEmoji("😊") }
        binding.emoji2.setOnClickListener { viewModel.selectEmoji("😎") }
        binding.emoji3.setOnClickListener { viewModel.selectEmoji("🐱") }
        binding.emoji4.setOnClickListener { viewModel.selectEmoji("❤️") }

        binding.btnExport.setOnClickListener {
            viewModel.exportVideo()
        }
    }

    private fun observeUiState() {
        viewModel.uiState.onEach { state ->
            if (state.error != null) {
                Toast.makeText(context, state.error, Toast.LENGTH_LONG).show()
                viewModel.clearError()
                if (state.durationMs > 30_000L) {
                    findNavController().navigateUp()
                }
            }

            updateEffectButtonSelection(state.selectedEffect)
            binding.scrollEmojiPanel.isVisible = state.selectedEffect == VideoEffect.EMOJI

            binding.layoutProgressOverlay.isVisible = state.isProcessing
            binding.progressBar.progress = state.progress
            binding.txtProgressPercent.text = "${state.progress}%"

            if (state.exportedVideoUri != null) {
                Toast.makeText(context, "Video saved successfully to:\n${state.exportedVideoUri.path}", Toast.LENGTH_LONG).show()
                findNavController().navigateUp()
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun updateEffectButtonSelection(selectedEffect: VideoEffect) {
        val activeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#7C4DFF"))
        val inactiveColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C2C30"))

        binding.btnEffectNone.backgroundTintList = if (selectedEffect == VideoEffect.NONE) activeColor else inactiveColor
        binding.btnEffectBlur.backgroundTintList = if (selectedEffect == VideoEffect.BLUR) activeColor else inactiveColor
        binding.btnEffectPixelate.backgroundTintList = if (selectedEffect == VideoEffect.PIXELATE) activeColor else inactiveColor
        binding.btnEffectBlackout.backgroundTintList = if (selectedEffect == VideoEffect.BLACKOUT) activeColor else inactiveColor
        binding.btnEffectEmoji.backgroundTintList = if (selectedEffect == VideoEffect.EMOJI) activeColor else inactiveColor
    }

    private fun startLiveFrameSampling() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                val isPlaying = mediaPlayer?.isPlaying == true
                val effect = viewModel.uiState.value.selectedEffect

                if (isPlaying && isPlayerPrepared && effect != VideoEffect.NONE) {
                    val w = binding.videoTextureView.width
                    val h = binding.videoTextureView.height

                    if (w > 0 && h > 0) {
                        val frameBitmap = binding.videoTextureView.getBitmap(w / 2, h / 2)
                        if (frameBitmap != null) {
                            processLiveFrame(frameBitmap, w, h)
                        }
                    }
                } else if (effect == VideoEffect.NONE) {
                    binding.overlayView.setImageDrawable(null)
                }
                delay(200)
            }
        }
    }

    private fun processLiveFrame(frameBitmap: Bitmap, viewWidth: Int, viewHeight: Int) {
        val inputImage = InputImage.fromBitmap(frameBitmap, 0)

        previewDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty() && isAdded && _binding != null) {
                    val overlayBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(overlayBitmap)

                    val effect = viewModel.uiState.value.selectedEffect
                    val emoji = viewModel.uiState.value.selectedEmoji

                    val scaleX = viewWidth.toFloat() / frameBitmap.width
                    val scaleY = viewHeight.toFloat() / frameBitmap.height

                    for (face in faces) {
                        val box = face.boundingBox
                        val scaledBox = Rect(
                            (box.left * scaleX).toInt(),
                            (box.top * scaleY).toInt(),
                            (box.right * scaleX).toInt(),
                            (box.bottom * scaleY).toInt()
                        )
                        applyEffectToPreview(canvas, scaledBox, frameBitmap, scaleX, scaleY, effect, emoji)
                    }
                    binding.overlayView.setImageBitmap(overlayBitmap)
                } else {
                    binding.overlayView.setImageDrawable(null)
                }
                frameBitmap.recycle()
            }
            .addOnFailureListener {
                frameBitmap.recycle()
            }
    }

    private fun applyEffectToPreview(
        canvas: Canvas,
        rect: Rect,
        frameBitmap: Bitmap,
        scaleX: Float,
        scaleY: Float,
        effect: VideoEffect,
        emojiChar: String?
    ) {
        val left = rect.left.coerceIn(0, canvas.width)
        val top = rect.top.coerceIn(0, canvas.height)
        val right = rect.right.coerceIn(0, canvas.width)
        val bottom = rect.bottom.coerceIn(0, canvas.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return

        when (effect) {
            VideoEffect.BLUR -> {
                val frameLeft = (left / scaleX).toInt().coerceIn(0, frameBitmap.width - 1)
                val frameTop = (top / scaleY).toInt().coerceIn(0, frameBitmap.height - 1)
                val frameRight = (right / scaleX).toInt().coerceIn(0, frameBitmap.width)
                val frameBottom = (bottom / scaleY).toInt().coerceIn(0, frameBitmap.height)
                val fw = frameRight - frameLeft
                val fh = frameBottom - frameTop

                if (fw > 0 && fh > 0) {
                    val faceCrop = Bitmap.createBitmap(frameBitmap, frameLeft, frameTop, fw, fh)
                    val small = Bitmap.createScaledBitmap(faceCrop, Math.max(1, fw / 8), Math.max(1, fh / 8), true)
                    canvas.drawBitmap(small, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                    faceCrop.recycle()
                    small.recycle()
                }
            }
            VideoEffect.PIXELATE -> {
                val frameLeft = (left / scaleX).toInt().coerceIn(0, frameBitmap.width - 1)
                val frameTop = (top / scaleY).toInt().coerceIn(0, frameBitmap.height - 1)
                val frameRight = (right / scaleX).toInt().coerceIn(0, frameBitmap.width)
                val frameBottom = (bottom / scaleY).toInt().coerceIn(0, frameBitmap.height)
                val fw = frameRight - frameLeft
                val fh = frameBottom - frameTop

                if (fw > 0 && fh > 0) {
                    val faceCrop = Bitmap.createBitmap(frameBitmap, frameLeft, frameTop, fw, fh)
                    val small = Bitmap.createScaledBitmap(faceCrop, Math.max(1, fw / 16), Math.max(1, fh / 16), false)
                    val paint = Paint().apply { isFilterBitmap = false }
                    canvas.drawBitmap(small, null, rect, paint)
                    faceCrop.recycle()
                    small.recycle()
                }
            }
            VideoEffect.BLACKOUT -> {
                val paint = Paint().apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                }
                canvas.drawRect(rect, paint)
            }
            VideoEffect.EMOJI -> {
                val emoji = emojiChar ?: "😊"
                val paint = Paint().apply {
                    textSize = h * 0.85f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val x = rect.centerX().toFloat()
                val y = rect.centerY().toFloat() - ((paint.descent() + paint.ascent()) / 2f)
                canvas.drawText(emoji, x, y, paint)
            }
            VideoEffect.NONE -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isPlayerPrepared) {
            mediaPlayer?.start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mediaPlayer?.release()
        mediaPlayer = null
        previewDetector.close()
        _binding = null
    }
}