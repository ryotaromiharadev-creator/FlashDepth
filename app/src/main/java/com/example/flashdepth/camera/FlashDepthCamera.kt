package com.example.flashdepth.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 撮影済みフレーム。強度はトーチの目標値 0.0..1.0 */
data class CapturedFrame(
    val bitmap: Bitmap,
    val fingerprint: FloatArray,  // 16×16 正規化輝度（ペア選択の高速化用）
    val intensity: Float,
    val timestamp: Long
)

@OptIn(ExperimentalCamera2Interop::class)
class FlashDepthCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    // ---- 設定パラメータ（UI から変更可能） ----
    @Volatile var lightPeriodMs: Long = 1000L        // ライト周期: 0%→100% の時間 (500..2000ms)
    @Volatile var captureIntervalMs: Long = 100L     // 撮影間隔 (1000/30ms .. 1000ms)
    @Volatile var estimationIntervalMs: Long = 2000L // 推定周期 (500..5000ms)
    @Volatile var bufferSize: Int = 16               // 保持枚数 N (5..64)

    // ---- 出力 ----
    private val _depthFlow = MutableStateFlow<Bitmap?>(null)
    val depthFlow: StateFlow<Bitmap?> = _depthFlow.asStateFlow()

    private val _lightIntensityFlow = MutableStateFlow(0f)
    val lightIntensityFlow: StateFlow<Float> = _lightIntensityFlow.asStateFlow()

    /** Pair<現在枚数, 最大枚数> */
    private val _bufferFillFlow = MutableStateFlow(0 to 16)
    val bufferFillFlow: StateFlow<Pair<Int, Int>> = _bufferFillFlow.asStateFlow()

    // ---- 内部 ----
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var maxFlashStrength = 1
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processingScope = CoroutineScope(Dispatchers.Default)

    private val frameBuffer = ArrayDeque<CapturedFrame>()
    private var lastCaptureMs = 0L
    private var lastEstimationMs = 0L

    @Volatile private var streamingActive = false
    private var lightJob: Job? = null

    // ---- カメラ初期化 ----

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
                val cam = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture, analysis
                )
                camera = cam
                // デバイスの可変フラッシュ強度の上限を取得 (非対応なら 1)
                maxFlashStrength = Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, mainExecutor)
    }

    // ---- ストリーミングモード制御 ----

    fun setStreamingActive(active: Boolean) {
        streamingActive = active
        if (active) {
            startLightCycle()
        } else {
            stopLightCycle()
            applyLightIntensity(0f)
            _lightIntensityFlow.value = 0f
            _depthFlow.value = null
            synchronized(frameBuffer) {
                frameBuffer.clear()
                _bufferFillFlow.value = 0 to bufferSize
            }
        }
    }

    private fun startLightCycle() {
        lightJob?.cancel()
        lightJob = processingScope.launch {
            while (streamingActive) {
                val intensity = computeLightIntensity(System.currentTimeMillis())
                applyLightIntensity(intensity)
                _lightIntensityFlow.value = intensity
                delay(50L)  // 約 20Hz でトーチ強度を更新
            }
        }
    }

    private fun stopLightCycle() {
        lightJob?.cancel()
        lightJob = null
    }

    /**
     * 三角波: 0→1→0 の繰り返し
     * lightPeriodMs = 0%→100% にかかる時間
     * 完全 1 サイクル (0→1→0) = lightPeriodMs × 2
     */
    private fun computeLightIntensity(nowMs: Long): Float {
        val period = lightPeriodMs.coerceAtLeast(1L)
        val phase = (nowMs % (period * 2)).toFloat() / period
        return if (phase <= 1f) phase else 2f - phase
    }

    private fun applyLightIntensity(intensity: Float) {
        val cam = camera ?: return
        if (maxFlashStrength > 1) {
            // 可変輝度トーチ対応デバイス
            if (intensity < 0.01f) {
                Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                        .build()
                ).addListener({}, mainExecutor)
            } else {
                val level = (intensity * maxFlashStrength).roundToInt().coerceIn(1, maxFlashStrength)
                Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                        .setCaptureRequestOption(CaptureRequest.FLASH_STRENGTH_LEVEL, level)
                        .build()
                ).addListener({}, mainExecutor)
            }
        } else {
            // ON/OFF のみの場合: 50% 以上で ON
            cam.cameraControl.enableTorch(intensity >= 0.5f)
        }
    }

    // ---- フレーム取得・バッファ管理 ----

    private fun onFrame(imageProxy: ImageProxy) {
        if (!streamingActive) { imageProxy.close(); return }

        val now = System.currentTimeMillis()
        if (now - lastCaptureMs < captureIntervalMs) { imageProxy.close(); return }
        lastCaptureMs = now

        val bitmap = imageProxy.toStreamBitmap()
        imageProxy.close()

        val intensity = computeLightIntensity(now)
        val fingerprint = computeFingerprint(bitmap)
        val frame = CapturedFrame(bitmap, fingerprint, intensity, now)

        synchronized(frameBuffer) {
            frameBuffer.addFirst(frame)
            while (frameBuffer.size > bufferSize) frameBuffer.removeLast()
            _bufferFillFlow.value = frameBuffer.size to bufferSize
        }

        if (now - lastEstimationMs >= estimationIntervalMs) {
            lastEstimationMs = now
            val frames = synchronized(frameBuffer) { frameBuffer.toList() }
            processingScope.launch {
                val pair = selectBestPair(frames) ?: return@launch
                // ペアを (暗い, 明るい) の順に並べて DepthProcessor へ
                val (dark, bright) = if (pair.first.intensity <= pair.second.intensity)
                    pair.first to pair.second else pair.second to pair.first
                _depthFlow.value = DepthProcessor.process(dark.bitmap, bright.bitmap)
            }
        }
    }

    // ---- ペア選択 ----

    /**
     * 全ペアを評価し、lightScore × matchScore が最大のペアを返す。
     *
     * lightScore = |intensity_A - intensity_B|  （光の差）
     * matchScore = ZNCC(fingerprint_A, fingerprint_B)  （シーン一致度）
     */
    private fun selectBestPair(frames: List<CapturedFrame>): Pair<CapturedFrame, CapturedFrame>? {
        if (frames.size < 2) return null

        var bestScore = 0f
        var bestPair: Pair<CapturedFrame, CapturedFrame>? = null

        for (i in frames.indices) {
            for (j in i + 1 until frames.size) {
                val lightScore = abs(frames[i].intensity - frames[j].intensity)
                if (lightScore < 0.1f) continue  // 光量差が小さいペアは除外

                val matchScore = computeMatchScore(frames[i].fingerprint, frames[j].fingerprint)
                val score = lightScore * matchScore

                if (score > bestScore) {
                    bestScore = score
                    bestPair = frames[i] to frames[j]
                }
            }
        }

        return bestPair
    }

    /** ZNCC (零平均正規化相互相関) でシーン一致度を 0.0..1.0 で返す */
    private fun computeMatchScore(fpA: FloatArray, fpB: FloatArray): Float {
        var cov = 0f; var varA = 0f; var varB = 0f
        for (k in fpA.indices) {
            val da = fpA[k] - 1f
            val db = fpB[k] - 1f
            cov += da * db; varA += da * da; varB += db * db
        }
        return (cov / sqrt(varA * varB).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
    }

    /** 16×16 に縮小した正規化輝度マップ（フィンガープリント） */
    private fun computeFingerprint(bitmap: Bitmap): FloatArray {
        val size = 16
        val px = IntArray(size * size).also {
            Bitmap.createScaledBitmap(bitmap, size, size, true).getPixels(it, 0, size, 0, 0, size, size)
        }
        val lum = FloatArray(size * size) { luminance(px[it]) }
        val mean = lum.average().toFloat().coerceAtLeast(1f)
        return FloatArray(size * size) { lum[it] / mean }
    }

    private fun luminance(pixel: Int): Float =
        0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)

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
                    cont.resume(image.toCapturedBitmap().also { image.close() })
                }
                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    // ---- Bitmap 変換 ----

    private fun ImageProxy.toStreamBitmap(): Bitmap {
        val plane = planes[0]
        val strideWidth = plane.rowStride / plane.pixelStride
        val bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        val trimmed = if (strideWidth > width) Bitmap.createBitmap(bmp, 0, 0, width, height) else bmp
        return trimmed.rotated(imageInfo.rotationDegrees)
    }

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
