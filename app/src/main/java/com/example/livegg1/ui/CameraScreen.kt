package com.example.livegg1.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.livegg1.Utils.cropBitmapToAspectRatio
import com.example.livegg1.Utils.takePhoto
import com.example.livegg1.ui.theme.LiveGG1Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.concurrent.ExecutorService
import androidx.camera.core.Preview as CameraPreview
import androidx.core.content.ContextCompat

@Composable
fun CameraScreen(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val screenAspectRatio = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()

    // --- 状态管理 ---
    var imageToShow by remember { mutableStateOf<Bitmap?>(null) }
    var model by remember { mutableStateOf<Model?>(null) }
    var speechService by remember { mutableStateOf<SpeechService?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 新的状态管理：用于连续识别
    val recognizedSentences = remember { mutableStateListOf<String>() }
    var currentPartialText by remember { mutableStateOf("") }

    // 组合最终显示的字幕
    val captionToShow by remember {
        derivedStateOf {
            val combinedText = (recognizedSentences + currentPartialText).joinToString(separator = "\n")
            errorText ?: combinedText
        }
    }


    // --- 相机设置 ---
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    // --- Vosk 监听器 (连续识别逻辑) ---
    val listener = object : RecognitionListener {
        override fun onFinalResult(hypothesis: String?) {
            hypothesis?.let {
                try {
                    val text = JSONObject(it).getString("text")
                    if (text.isNotBlank()) {
                        Log.d("Vosk", "onFinalResult: $text")
                        recognizedSentences.add(text) // 添加完整句子
                        currentPartialText = "" // 清空部分结果
                    }
                } catch (e: Exception) {
                    Log.e("Vosk", "Error parsing final result: $it", e)
                }
            }
        }

        override fun onPartialResult(hypothesis: String?) {
            hypothesis?.let {
                try {
                    val partialText = JSONObject(it).getString("partial")
                    currentPartialText = partialText // 更新部分结果
                } catch (e: Exception) {
                    // 忽略解析错误
                }
            }
        }

        override fun onError(e: Exception?) {
            Log.e("Vosk", "Recognition error", e)
            errorText = "语音识别错误: ${e?.message}"
        }

        override fun onResult(hypothesis: String?) { /* onFinalResult 已经处理，这里忽略 */ }
        override fun onTimeout() { /* 在连续识别中通常不处理超时 */ }
    }

    // --- 核心逻辑：初始化 ---
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val sourcePath = "model"
                val targetPath = StorageService.sync(context, sourcePath, "model")
                Log.d("Vosk", "Model sync completed. Target path: $targetPath")
                model = Model(targetPath)
                speechService = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
                Log.d("Vosk", "Model and SpeechService loaded successfully.")
            } catch (e: IOException) {
                Log.e("Vosk", "Failed to initialize Vosk.", e)
                errorText = "错误: 初始化语音模型失败。"
            } finally {
                isLoading = false
            }
        }
    }

    // --- 核心逻辑：绑定相机和资源管理 ---
    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = CameraPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("CameraScreen", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProviderFuture.get().unbindAll()
            speechService?.stop()
            speechService?.shutdown()
            model?.close()
        }
    }

    // --- 核心逻辑：启动连续语音识别 ---
    LaunchedEffect(speechService) {
        speechService?.startListening(listener)
        Log.d("Vosk", "Continuous listening started.")
    }

    // --- 核心逻辑：定时拍照更新背景 ---
    LaunchedEffect(imageCapture) {
        // 首次启动时，先拍一张照片作为背景
        takePhoto(imageCapture, cameraExecutor, { imageToShow = cropBitmapToAspectRatio(it, screenAspectRatio) }, {})
        delay(1000)

        while (isActive) {
            delay(6000L) // 每6秒更新一次背景
            takePhoto(
                imageCapture = imageCapture,
                executor = cameraExecutor,
                onImageCaptured = { newBitmap ->
                    val croppedBitmap = cropBitmapToAspectRatio(newBitmap, screenAspectRatio)
                    imageToShow?.recycle() // 释放旧的 bitmap
                    imageToShow = croppedBitmap
                    Log.d("MainLoop", "Background photo updated.")
                },
                onError = { Log.e("MainLoop", "Photo capture failed", it) }
            )
        }
    }

    // --- UI 界面 ---
    CameraScreenContent(
        isLoading = isLoading,
        imageToShow = imageToShow,
        captionToShow = captionToShow,
        previewView = { AndroidView({ previewView }, modifier = Modifier.fillMaxSize()) }
    )
}

@Composable
private fun CameraScreenContent(
    isLoading: Boolean,
    imageToShow: Bitmap?,
    captionToShow: String,
    previewView: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 摄像头预览或占位图一直在最底层
        previewView()

        // 2. 图像或加载指示器
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text("正在加载语音模型...", modifier = Modifier.padding(top = 8.dp), color = Color.White)
                    }
                }
            }
            imageToShow != null -> {
                Image(
                    bitmap = imageToShow.asImageBitmap(),
                    contentDescription = "Captured Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // 将渐变背景和字幕包裹在一个父 Box 中
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            // 渐变背景层
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.4f)
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.5f to Color(0xCCFFC0CB)
                            )
                        )
                    )
            ) {}

            // 字幕层
            if (captionToShow.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 100.dp, bottom = 70.dp, end = 24.dp)
                ) {
                    val shadow = Shadow(color = Color.Black, offset = Offset(4f, 4f), blurRadius = 8f)
                    Text(
                        text = captionToShow,
                        color = Color.White,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Start,
                        style = TextStyle(shadow = shadow)
                    )
                }
            }
        }
    }
}

@Preview(
    showBackground = true,
    widthDp = 853,
    heightDp = 480,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    device = "spec:parent=pixel_5,orientation=landscape"
)
@Composable
fun CameraScreenPreview() {
    LiveGG1Theme {
        val placeholderBitmap = Bitmap.createBitmap(853, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
        }
        CameraScreenContent(
            isLoading = false,
            imageToShow = placeholderBitmap,
            captionToShow = "这是第一句已经识别完成的字幕。\n这是第二句，仍在识别中...",
            previewView = {
                Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
            }
        )
    }
}
