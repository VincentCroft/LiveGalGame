package com.example.livegg1

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.livegg1.ui.theme.LiveGG1Theme
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var cameraPermissionGranted by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Camera permission granted")
                cameraPermissionGranted = true
            } else {
                Log.d("MainActivity", "Camera permission denied")
                // The user will see the "permission denied" message.
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        when (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "Camera permission already granted")
                cameraPermissionGranted = true
            }
            else -> {
                Log.d("MainActivity", "Requesting camera permission")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        setContent {
            LiveGG1Theme {
                if (cameraPermissionGranted) {
                    CameraScreen(cameraExecutor)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Camera permission denied. Please grant permission to use the camera.")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        Log.d("MainActivity", "CameraExecutor shut down")
    }
}

@Composable
fun CameraScreen(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp
    val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()

    var capturedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showPreview by remember { mutableStateOf(true) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember {
        PreviewView(context).apply {
            this.scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(lifecycleOwner) {
        try {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageCapture
            )
            Log.d("CameraScreen", "Camera use cases bound to lifecycle")
        } catch (exc: Exception) {
            Log.e("CameraScreen", "Use case binding failed", exc)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000L)
            Log.d("CameraScreen", "Attempting to take photo...")
            takePhoto(
                imageCapture = imageCapture,
                executor = cameraExecutor,
                onImageCaptured = { originalBitmap ->
                    val croppedBitmap = cropBitmapToAspectRatio(originalBitmap, screenAspectRatio)
                    // 释放旧图片内存并更新为新图片
                    capturedImageBitmap?.recycle()
                    capturedImageBitmap = croppedBitmap
                    Log.d("CameraScreen", "Image captured and bitmap updated.")
                },
                onError = { exception ->
                    Log.e("CameraScreen", "Photo capture failed: ${exception.message}", exception)
                    // 拍照失败时可以考虑是否需要提示用户
                }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 摄像头预览一直在底层运行
        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        // 拍摄的照片在顶层，会覆盖预览
        // 当 capturedImageBitmap 为 null 时 (初始状态)，Image 不会显示
        capturedImageBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, // 确保图片填满屏幕
                alignment = Alignment.Center
            )
            Log.d("CameraScreen", "Showing captured image")
        }
    }
}

fun takePhoto(
    imageCapture: ImageCapture,
    executor: ExecutorService,
    onImageCaptured: (Bitmap) -> Unit,
    onError: (ImageCaptureException) -> Unit
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                Log.d("takePhoto", "Capture success. Image format: ${imageProxy.format}")
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val buffer: ByteBuffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                imageProxy.close()

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    // Rotate the bitmap if necessary
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    onImageCaptured(rotatedBitmap)
                } else {
                    Log.e("takePhoto", "BitmapFactory.decodeByteArray returned null")
                    onError(
                        ImageCaptureException(
                            ImageCapture.ERROR_UNKNOWN,
                            "Failed to decode bitmap",
                            null
                        )
                    )
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(
                    "takePhoto",
                    "Photo capture error: ${exception.message} (code: ${exception.imageCaptureError})",
                    exception
                )
                onError(exception)
            }
        }
    )
}