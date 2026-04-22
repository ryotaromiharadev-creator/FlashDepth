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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    // ストリーミングモード
    data object Streaming : AppState
    // 撮影モード
    data object PhotoReady : AppState
    data object Capturing : AppState
    data object Processing : AppState
    data class PhotoResult(val depthMap: Bitmap) : AppState
    // エラー
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
    var fps by remember { mutableIntStateOf(8) }

    // モード切替時にカメラ設定を同期
    LaunchedEffect(appState) {
        when (appState) {
            is AppState.Streaming -> {
                camera.targetFps = fps
                camera.setStreamingActive(true)
            }
            is AppState.PhotoReady -> camera.setStreamingActive(false)
            else -> {}
        }
    }

    // FPS変更時にシャッタースピードも更新
    LaunchedEffect(fps) {
        camera.targetFps = fps
        if (appState is AppState.Streaming) camera.applyManualExposure(fps)
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
                    // カメラプレビュー（モード切替時に再起動しないよう常時表示）
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { camera.startPreview(it) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    when (state) {
                        is AppState.Streaming -> StreamingOverlay(
                            depthBitmap = depthBitmap,
                            fps = fps,
                            onFpsChange = { fps = it }
                        )

                        is AppState.PhotoReady,
                        is AppState.Capturing,
                        is AppState.Processing -> {
                            val isBusy = state !is AppState.PhotoReady
                            val statusLabel = when (state) {
                                is AppState.Capturing -> "撮影中... (フラッシュが光ります)"
                                is AppState.Processing -> "深度マップを計算中..."
                                else -> ""
                            }
                            PhotoCaptureOverlay(
                                isBusy = isBusy,
                                statusLabel = statusLabel,
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
                    val canToggle = state is AppState.Streaming || state is AppState.PhotoReady || state is AppState.PhotoResult
                    if (canToggle) {
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
    fps: Int,
    onFpsChange: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        depthBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.65f),
                contentScale = ContentScale.Fit
            )
        }

        if (depthBitmap == null) {
            Text(
                text = "深度推定を開始中...",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // コントロールパネル
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.65f), MaterialTheme.shapes.medium)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // カラーレジェンド
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("近い", color = Color(0xFF4444FF), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
                Text("──────────▶", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
                Text("遠い", color = Color(0xFFFF4444), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // FPS & シャッタースピード表示
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "更新レート: $fps fps",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "シャッター: 1/${fps * 2}秒",
                    color = Color(0xFFAADDFF),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Slider(
                value = fps.toFloat(),
                onValueChange = { onFpsChange(it.roundToInt().coerceIn(1, 30)) },
                valueRange = 1f..30f,
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }
    }
}

// ---- 撮影モード UI ----

@Composable
fun PhotoCaptureOverlay(
    isBusy: Boolean,
    statusLabel: String,
    onCapture: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isBusy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(statusLabel, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        // シャッターボタン
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            bitmap = depthMap.asImageBitmap(),
            contentDescription = "深度マップ",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
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
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("カメラの許可が必要です", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "深度推定にはカメラとフラッシュライトへのアクセスが必要です。",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("許可する") }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
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
