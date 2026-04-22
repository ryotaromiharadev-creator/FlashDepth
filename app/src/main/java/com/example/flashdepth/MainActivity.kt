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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.flashdepth.ui.theme.FlashDepthTheme
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
    data class Error(val message: String) : AppState
}

@Composable
fun FlashDepthApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var appState by remember { mutableStateOf<AppState>(AppState.RequestingPermission) }
    val camera = remember(lifecycleOwner) { FlashDepthCamera(context, lifecycleOwner) }
    val depthBitmap by camera.depthFlow.collectAsState()
    var fps by remember { mutableIntStateOf(8) }

    LaunchedEffect(fps) { camera.targetFps = fps }

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

                is AppState.Streaming -> StreamingScreen(
                    camera = camera,
                    depthBitmap = depthBitmap,
                    fps = fps,
                    onFpsChange = { fps = it }
                )

                is AppState.Error -> ErrorScreen(
                    message = state.message,
                    onRetry = { appState = AppState.Streaming }
                )
            }
        }
    }
}

@Composable
fun StreamingScreen(
    camera: FlashDepthCamera,
    depthBitmap: Bitmap?,
    fps: Int,
    onFpsChange: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // カメラプレビュー（背景）
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { camera.startPreview(it) }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 深度マップオーバーレイ
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

        // コントロールパネル（下部）
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

            // FPSスライダー
            Text(
                text = "更新レート: $fps fps",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = fps.toFloat(),
                onValueChange = { onFpsChange(it.roundToInt().coerceIn(1, 30)) },
                valueRange = 1f..30f,
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }

        // 深度マップ未取得時のヒント
        if (depthBitmap == null) {
            Text(
                text = "カメラをかざすと深度推定が始まります",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

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
