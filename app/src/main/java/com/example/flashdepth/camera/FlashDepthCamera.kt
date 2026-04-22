package com.example.flashdepth.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.flashdepth.processor.DepthProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class FlashDepthCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var camera: Camera? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processingScope = CoroutineScope(Dispatchers.Default)

    private val _depthFlow = MutableStateFlow<Bitmap?>(null)
    val depthFlow: StateFlow<Bitmap?> = _depthFlow.asStateFlow()

    @Volatile
    var targetFps: Int = 8
        set(value) { field = value.coerceIn(1, 30) }

    // アナライザスレッド専用変数（同期不要）
    private var isTorchOn = false
    private var skipFrames = 0
    private var ambientFrame: Bitmap? = null
    private var lastDepthMs = 0L

    fun startPreview(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(analysisExecutor, ::onFrame)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onFrame(imageProxy: ImageProxy) {
        if (skipFrames > 0) {
            skipFrames--
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()
        imageProxy.close()

        if (!isTorchOn) {
            ambientFrame = bitmap
            camera?.cameraControl?.enableTorch(true)
            isTorchOn = true
            skipFrames = 1  // トーチが安定するまで1フレームスキップ
        } else {
            val ambient = ambientFrame
            if (ambient != null) {
                val now = System.currentTimeMillis()
                if (now - lastDepthMs >= 1000L / targetFps) {
                    lastDepthMs = now
                    val torchBitmap = bitmap
                    processingScope.launch {
                        _depthFlow.value = DepthProcessor.process(ambient, torchBitmap)
                    }
                }
            }
            camera?.cameraControl?.enableTorch(false)
            isTorchOn = false
            skipFrames = 1  // トーチ消灯が安定するまで1フレームスキップ
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val strideWidth = plane.rowStride / plane.pixelStride

        val bitmap = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        val trimmed = if (strideWidth > width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap

        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) return trimmed
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(trimmed, 0, 0, trimmed.width, trimmed.height, matrix, true)
    }
}
