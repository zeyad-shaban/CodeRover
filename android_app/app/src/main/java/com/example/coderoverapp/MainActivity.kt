package com.example.coderoverapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.example.coderoverapp.ui.theme.CodeRoverAppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.opencv.android.OpenCVLoader


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OpenCVLoader.initDebug()) {
            Log.i("OpenCV", "OpenCV loaded (initDebug).")
        } else {
            Log.e("OpenCV", "OpenCV initialization failed!")
            // You can try initAsync or show error — but initDebug() usually works with AAR
        }

        enableEdgeToEdge()
        setContent {
            CodeRoverAppTheme {
                CameraScreen()
            }
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }

    // State variables to hold the "Shape" of the image
    var imageWidth by remember { mutableIntStateOf(0) }
    var imageHeight by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        launcher.launch(android.Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            // 1. The Camera Stream
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onFrameAnalysis = { w, h ->
                    imageWidth = w
                    imageHeight = h
                }
            )

            // 2. The UI Overlay (The Styling you like)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = "Resolution: $imageWidth x $imageHeight px",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                )
            }

        } else {
            Text(
                text = "We need camera permission.",
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}


@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onFrameAnalysis: (width: Int, height: Int) -> Unit // A callback to send data back to the UI
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { androidx.core.content.ContextCompat.getMainExecutor(context) }

    // This is the container for our camera view
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = androidx.camera.view.PreviewView(ctx)
            val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // 1. Setup Preview (The Visuals)
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // 2. Setup ImageAnalysis (The Brain/Pixels)
                // We set the strategy to ONLY give us the latest frame to keep it fast
                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    onFrameAnalysis(imageProxy.width, imageProxy.height)

                    imageProxy.close()
                }

                // 3. Bind everything to the phone's lifecycle
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    android.util.Log.e("Camera", "Use case binding failed", e)
                }

            }, executor)
            previewView
        }
    )
}

