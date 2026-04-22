package com.example.flashdepth.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCamera2Interop::class)
class FlashDepthCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processingScope = CoroutineScope(Dispatchers.Default)

    private val _depthFlow = MutableStateFlow<Bitmap?>(null)
    val depthFlow: StateFlow<Bitmap?> = _depthFlow.asStateFlow()

    @Volatile var targetFps: Int = 8
        set(value) { field = value.coerceIn(1, 30) }

    // アナライザスレッド専用（同期不要）
    private var isTorchOn = false
    private var skipFrames = 0
    private var ambientFrame: Bitmap? = null
    private var lastDepthMs = 0L

    // ストリーミング有効フラグ（volatile: メインスレッドとアナライザスレッドで共有）
    @Volatile private var streamingActive = false
    // カメラ初期化後に初回フレームで露出を適用するためのフラグ
    @Volatile private var pendingExposureApply = false

    fun startPreview(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            val capture = ImageCapture.Builder()
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

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
                    capture,
                    analysis
                )
                // カメラ初期化完了後、保留中の露出設定を適用
                if (pendingExposureApply) {
                    applyManualExposure(targetFps)
                    pendingExposureApply = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, mainExecutor)
    }

    /** ストリーミングモードの有効/無効切り替え */
    fun setStreamingActive(active: Boolean) {
        streamingActive = active
        if (active) {
            val cam = camera
            if (cam != null) applyManualExposure(targetFps) else pendingExposureApply = true
        } else {
            camera?.cameraControl?.enableTorch(false)
            isTorchOn = false
            pendingExposureApply = false
            restoreAutoExposure()
            _depthFlow.value = null
        }
    }

    /** FPSに基づいてシャッタースピードを手動設定
     *  露出時間 = 1 / (fps × 2) 秒（トーチON/OFFの1サイクル分）
     *  範囲: 4ms (1/250s) 〜 200ms (1/5s) */
    fun applyManualExposure(fps: Int) {
        val cam = camera ?: return
        val exposureNs = (1_000_000_000L / (fps.toLong() * 2))
            .coerceIn(4_000_000L, 200_000_000L)
        Camera2CameraControl.from(cam.cameraControl)
            .setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, 800)
                    .build()
            )
            .addListener({}, mainExecutor)
    }

    private fun restoreAutoExposure() {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl)
            .setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    .build()
            )
            .addListener({}, mainExecutor)
    }

    // ---- ストリーミングモード ----

    private fun onFrame(imageProxy: ImageProxy) {
        if (!streamingActive) {
            imageProxy.close()
            return
        }

        if (skipFrames > 0) {
            skipFrames--
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toStreamBitmap()
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

    // ---- 撮影モード ----

    suspend fun captureAmbientAndFlash(): Pair<Bitmap, Bitmap> {
        val capture = checkNotNull(imageCapture) { "カメラが初期化されていません" }

        capture.setFlashMode(ImageCapture.FLASH_MODE_OFF)
        val ambient = capture.takePictureSuspend()

        delay(300L)

        capture.setFlashMode(ImageCapture.FLASH_MODE_ON)
        val withFlash = capture.takePictureSuspend()
        capture.setFlashMode(ImageCapture.FLASH_MODE_OFF)

        return ambient to withFlash
    }

    private suspend fun ImageCapture.takePictureSuspend(): Bitmap =
        suspendCancellableCoroutine { cont ->
            takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toCapturedBitmap()
                    image.close()
                    cont.resume(bitmap)
                }
                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    // ---- Bitmap変換 ----

    /** ImageAnalysis (RGBA_8888) フレーム用 */
    private fun ImageProxy.toStreamBitmap(): Bitmap {
        val plane = planes[0]
        val strideWidth = plane.rowStride / plane.pixelStride
        val bitmap = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        val trimmed = if (strideWidth > width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
        return trimmed.rotated(imageInfo.rotationDegrees)
    }

    /** ImageCapture (JPEG) フレーム用 */
    private fun ImageProxy.toCapturedBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).rotated(imageInfo.rotationDegrees)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}
