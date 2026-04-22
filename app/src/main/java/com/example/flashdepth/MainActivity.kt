package com.example.flashdepth

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.flashdepth.camera.FlashDepthCamera
import com.example.flashdepth.processor.DepthProcessor
import com.example.flashdepth.ui.theme.FlashDepthTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlashDepthTheme {
                FlashDepthApp()
            }
        }
    }
}

sealed interface AppState {
    data object RequestingPermission : AppState
    data object Streaming : AppState
    data object PhotoReady : AppState
    data object Capturing : AppState
    data object Processing : AppState
    data class PhotoResult(val depthMap: Bitmap) : AppState
    data class Error(val message: String) : AppState
}

@Composable
fun FlashDepthApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var appState by remember { mutableStateOf<AppState>(AppState.RequestingPermission) }
    val camera = remember(lifecycleOwner) { FlashDepthCamera(context, lifecycleOwner) }

    val depthBitmap by camera.depthFlow.collectAsState()
    val lightIntensity by camera.lightIntensityFlow.collectAsState()
    val bufferFill by camera.bufferFillFlow.collectAsState()

    // ---- ストリーミング設定パラメータ ----
    var lightPeriodSec by remember { mutableFloatStateOf(1.0f) }   // 0.5..2.0s
    var captureFps by remember { mutableIntStateOf(10) }            // 1..30 fps
    var estimationPeriodSec by remember { mutableFloatStateOf(2.0f) } // 0.5..5.0s
    var bufferN by remember { mutableIntStateOf(16) }               // 5..64 枚

    // パラメータをカメラに同期
    LaunchedEffect(lightPeriodSec) { camera.lightPeriodMs = (lightPeriodSec * 1000).toLong() }
    LaunchedEffect(captureFps) { camera.captureIntervalMs = 1000L / captureFps }
    LaunchedEffect(estimationPeriodSec) { camera.estimationIntervalMs = (estimationPeriodSec * 1000).toLong() }
    LaunchedEffect(bufferN) { camera.bufferSize = bufferN }

    // モード切替時にカメラ状態を同期
    LaunchedEffect(appState) {
        when (appState) {
            is AppState.Streaming -> camera.setStreamingActive(true)
            is AppState.PhotoReady,
            is AppState.Capturing,
            is AppState.Processing,
            is AppState.PhotoResult -> camera.setStreamingActive(false)
            else -> {}
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        appState = if (granted) AppState.Streaming else AppState.RequestingPermission
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = appState) {
                is AppState.RequestingPermission -> PermissionScreen(onRequest = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                })

                is AppState.Error -> ErrorScreen(
                    message = state.message,
                    onRetry = { appState = AppState.Streaming }
                )

                else -> {
                    // カメラプレビュー（モード切替時も再起動しない）
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { camera.startPreview(it) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    when (state) {
                        is AppState.Streaming -> StreamingOverlay(
                            depthBitmap = depthBitmap,
                            lightIntensity = lightIntensity,
                            bufferFill = bufferFill,
                            lightPeriodSec = lightPeriodSec,
                            onLightPeriodChange = { lightPeriodSec = it },
                            captureFps = captureFps,
                            onCaptureFpsChange = { captureFps = it },
                            estimationPeriodSec = estimationPeriodSec,
                            onEstimationPeriodChange = { estimationPeriodSec = it },
                            bufferN = bufferN,
                            onBufferNChange = { bufferN = it }
                        )

                        is AppState.PhotoReady,
                        is AppState.Capturing,
                        is AppState.Processing -> {
                            val isBusy = state !is AppState.PhotoReady
                            PhotoCaptureOverlay(
                                isBusy = isBusy,
                                statusLabel = when (state) {
                                    is AppState.Capturing -> "撮影中... (フラッシュが光ります)"
                                    is AppState.Processing -> "深度マップを計算中..."
                                    else -> ""
                                },
                                onCapture = {
                                    scope.launch {
                                        appState = AppState.Capturing
                                        try {
                                            val (ambient, flash) = camera.captureAmbientAndFlash()
                                            appState = AppState.Processing
                                            val depthMap = withContext(Dispatchers.Default) {
                                                DepthProcessor.process(ambient, flash)
                                            }
                                            appState = AppState.PhotoResult(depthMap)
                                        } catch (e: Exception) {
                                            appState = AppState.Error(e.message ?: "不明なエラー")
                                        }
                                    }
                                }
                            )
                        }

                        is AppState.PhotoResult -> PhotoResultOverlay(
                            depthMap = state.depthMap,
                            onRetake = { appState = AppState.PhotoReady }
                        )

                        else -> {}
                    }

                    // モード切替ボタン（撮影中・処理中は非表示）
                    if (state is AppState.Streaming || state is AppState.PhotoReady || state is AppState.PhotoResult) {
                        ModeToggle(
                            isStreaming = state is AppState.Streaming,
                            onToggle = {
                                appState = if (state is AppState.Streaming) AppState.PhotoReady else AppState.Streaming
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---- モード切替ボタン ----

@Composable
fun ModeToggle(isStreaming: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.large)
            .padding(4.dp)
    ) {
        TextButton(
            onClick = { if (!isStreaming) onToggle() },
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (isStreaming) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                contentColor = Color.White
            )
        ) { Text("ストリーム") }

        TextButton(
            onClick = { if (isStreaming) onToggle() },
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (!isStreaming) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                contentColor = Color.White
            )
        ) { Text("撮影") }
    }
}

// ---- ストリーミングモード UI ----

@Composable
fun StreamingOverlay(
    depthBitmap: Bitmap?,
    lightIntensity: Float,
    bufferFill: Pair<Int, Int>,
    lightPeriodSec: Float, onLightPeriodChange: (Float) -> Unit,
    captureFps: Int, onCaptureFpsChange: (Int) -> Unit,
    estimationPeriodSec: Float, onEstimationPeriodChange: (Float) -> Unit,
    bufferN: Int, onBufferNChange: (Int) -> Unit
) {
    var settingsExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {

        // 深度マップオーバーレイ
        depthBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(0.65f),
                contentScale = ContentScale.Fit
            )
        }

        // ライト強度インジケーター（上部）
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ライト強度: ${(lightIntensity * 100).roundToInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "バッファ: ${bufferFill.first}/${bufferFill.second}枚",
                    color = Color(0xFFAADDFF),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { lightIntensity },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Color(0xFFFFDD44),
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }

        // 深度マップ未取得時のヒント
        if (depthBitmap == null) {
            Text(
                text = "バッファを蓄積中...",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 下部コントロールパネル
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // カラーレジェンド（常時表示）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("近い", color = Color(0xFF4444FF), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
                Text("──────────▶", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
                Text("遠い", color = Color(0xFFFF4444), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))

            // 設定展開/折りたたみボタン
            TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                Text(
                    if (settingsExpanded) "設定を閉じる ▼" else "設定を開く ▲",
                    color = Color(0xFFCCCCCC),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (settingsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.Start
                ) {
                    SettingSliderFloat(
                        label = "ライト周期",
                        value = lightPeriodSec,
                        displayValue = "%.1fs".format(lightPeriodSec),
                        range = 0.5f..2.0f,
                        onValueChange = onLightPeriodChange
                    )
                    SettingSliderInt(
                        label = "撮影レート",
                        value = captureFps,
                        displayValue = "$captureFps fps",
                        range = 1..30,
                        onValueChange = onCaptureFpsChange
                    )
                    SettingSliderFloat(
                        label = "推定周期",
                        value = estimationPeriodSec,
                        displayValue = "%.1fs".format(estimationPeriodSec),
                        range = 0.5f..5.0f,
                        onValueChange = onEstimationPeriodChange
                    )
                    SettingSliderInt(
                        label = "バッファ N",
                        value = bufferN,
                        displayValue = "$bufferN 枚",
                        range = 5..64,
                        onValueChange = onBufferNChange
                    )
                }
            }
        }
    }
}

@Composable
fun SettingSliderFloat(
    label: String,
    value: Float,
    displayValue: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(72.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            displayValue,
            color = Color(0xFFAADDFF),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(48.dp)
        )
    }
}

@Composable
fun SettingSliderInt(
    label: String,
    value: Int,
    displayValue: String,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(72.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f)
        )
        Text(
            displayValue,
            color = Color(0xFFAADDFF),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(48.dp)
        )
    }
}

// ---- 撮影モード UI ----

@Composable
fun PhotoCaptureOverlay(isBusy: Boolean, statusLabel: String, onCapture: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isBusy) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(statusLabel, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Button(
            onClick = onCapture,
            enabled = !isBusy,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .size(72.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                disabledContainerColor = Color.Gray
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text("●", color = Color.DarkGray, style = MaterialTheme.typography.headlineMedium)
        }

        if (!isBusy) {
            Text(
                text = "フラッシュOFF→ONで2枚撮影して深度を推定します",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun PhotoResultOverlay(depthMap: Bitmap, onRetake: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = depthMap.asImageBitmap(),
            contentDescription = "深度マップ",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("近い", color = Color(0xFF4444FF), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text("──────────▶", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text("遠い", color = Color(0xFFFF4444), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetake) { Text("再撮影") }
        }
    }
}

// ---- 共通 UI ----

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("カメラの許可が必要です", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("深度推定にはカメラとフラッシュライトへのアクセスが必要です。", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("許可する") }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("エラーが発生しました", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("再試行") }
    }
}
