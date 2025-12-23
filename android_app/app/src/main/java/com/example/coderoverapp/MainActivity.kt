package com.example.coderoverapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageProxy
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.coderoverapp.ble.BLEManager
import org.opencv.android.OpenCVLoader
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import org.opencv.objdetect.Dictionary
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas


private val arucoDictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
private val arucoDetector = ArucoDetector(arucoDictionary)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OpenCVLoader.initDebug()) {
            Log.i("OpenCV", "OpenCV loaded (initDebug).")
        } else {
            Log.e("OpenCV", "OpenCV initialization failed!")
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
    val bleManager = remember { BLEManager(context) }
    val isConnected by remember { derivedStateOf { bleManager.isConnectedState.value } }

    val density = LocalDensity.current

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = with(density) { maxWidth.toPx() }.toDouble()
        val screenHeight = with(density) { maxHeight.toPx() }.toDouble()

        var hasCameraPermission by remember { mutableStateOf(false) }

        // We store the detected result in a state to trigger a redraw of the Canvas
        var detectedMarker by remember { mutableStateOf<ArucoResult?>(null) }
        var imageSize by remember { mutableStateOf(org.opencv.core.Size(0.0, 0.0)) }

        var targetPoint by remember { mutableStateOf<Offset?>(null) }
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted -> hasCameraPermission = granted }
        )

        // Request camera permission and start BLE scan when screen appears
        LaunchedEffect(Unit) {
            launcher.launch(android.Manifest.permission.CAMERA)
            // start scanning for BLE device (non-blocking)
            bleManager.startScanningWithRetry()
        }

        // Throttle / last-sent bookkeeping
        var lastSendMillis by remember { mutableStateOf(0L) }
        var lastLeft by remember { mutableStateOf(Double.NaN) }
        var lastRight by remember { mutableStateOf(Double.NaN) }
        var vLeft by remember { mutableDoubleStateOf(0.0) }
        var vRight by remember { mutableDoubleStateOf(0.0) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        targetPoint = if (targetPoint == null) offset else null
                    }
                }
        ) {
            // Only show camera preview when we have permission AND BLE is connected
            if (hasCameraPermission) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onMarkerDetected = { result, size ->
                        // IMPORTANT: this callback is NOT a composable context.
                        // Do not call composables here. Only update state.

                        // update state so UI layer can redraw
                        detectedMarker = result
                        imageSize = size

                        if (targetPoint != null && result != null && imageSize.width > 0) {
                            // compute vLeft / vRight (pure math, safe here)
                            val kw = 0.6
                            val kv = 0.3
                            val wheelbase = 100.0 // mm

                            fun mapX(camX: Double, camY: Double) = (1 - (camY / imageSize.height)) * screenWidth
                            fun mapY(camX: Double, camY: Double) = (camX / imageSize.width) * screenHeight

                            val xt = targetPoint!!.x.toDouble()
                            val yt = targetPoint!!.y.toDouble()

                            val xCntr = mapX(result.centerX, result.centerY)
                            val yCntr = mapY(result.centerX, result.centerY)

                            val xTop = mapX(result.topX, result.topY)
                            val yTop = mapY(result.topX, result.topY)

                            val xBtm = mapX(result.bottomX, result.bottomY)
                            val yBtm = mapY(result.bottomX, result.bottomY)

                            val theta = Math.atan2(yTop - yBtm, xTop - xBtm)
                            val angleToTarget = Math.atan2(yt - yCntr, xt - xCntr)

                            var angleError = angleToTarget - theta
                            while (angleError > Math.PI) angleError -= 2 * Math.PI
                            while (angleError < -Math.PI) angleError += 2 * Math.PI

                            val w = kw * angleError
                            val distanceError = Math.sqrt(Math.pow(xt - xCntr, 2.0) + Math.pow(yt - yCntr, 2.0))
                            val forwardScale = if (Math.abs(angleError) > Math.PI / 4) 0.0 else 1.0;
                            val v = kv * distanceError

                            vRight = v - (w * wheelbase / 2.0)
                            vLeft = v + (w * wheelbase / 2.0)

                            Log.d("RoverControl", "V_Left: ${"%.2f".format(vLeft)} | V_Right: ${"%.2f".format(vRight)}")

                            if(isConnected) {
                                val now = System.currentTimeMillis()
                                if (now - lastSendMillis > 50) {
                                    lastSendMillis = now
                                    lastLeft = vLeft
                                    lastRight = vRight
                                    bleManager.sendDrive(vLeft, vRight, 0)
                                }
                            }
                        } else {
                            vRight = 0.0;
                            vLeft = 0.0;
                        }
                        
                        if (result == null) 
                            detectedMarker = null;
                    }
                )
            } else {
                // If camera permission missing OR BLE not connected -> show message and DO NOT show camera
                val msg = when {
                    !hasCameraPermission -> "Camera permission required"
                    !isConnected -> "Bluetooth not connected — please connect to CarRover"
                    else -> "Waiting..."
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(msg, color = Color.White)
                }
            }

            // Drawing Layer (overlay). If camera preview is hidden this will usually show nothing.
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Draw target (red dot)
                targetPoint?.let { point ->
                    drawCircle(color = Color.Red, center = point, radius = 20f)
                }
                detectedMarker?.let { marker ->
                    if (imageSize.width > 0 && imageSize.height > 0) {
                        fun mapPoint(camX: Double, camY: Double): Offset {
                            val screenX = (1 - (camY / imageSize.height)) * size.width
                            val screenY = (camX / imageSize.width) * size.height
                            return Offset(screenX.toFloat(), screenY.toFloat())
                        }

                        val startPoint = mapPoint(marker.bottomX, marker.bottomY)
                        val endPoint = mapPoint(marker.topX, marker.topY)

                        drawLine(
                            color = Color.Blue,
                            start = startPoint,
                            end = endPoint,
                            strokeWidth = 8f
                        )

                        drawCircle(
                            color = Color.Blue,
                            center = endPoint,
                            radius = 15f
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                if (!isConnected) {
                    Text(text = "BLE not connected..., , vl: ${"%.2f".format(vLeft)}, vr: ${"%.2f".format(vRight)}", color = Color.White)
                } else {
                    Text(text = "Marker: ${detectedMarker?.id ?: "Searching..."}, vl: ${"%.2f".format(vLeft)}, vr: ${"%.2f".format(vRight)}", color = Color.White)
                }
            }
        }
    }
}


@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onMarkerDetected: (ArucoResult?, org.opencv.core.Size) -> Unit
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
                    val mat = imageProxy.toGrayMat()
                    val detections = detectAruco(mat)

                    if (detections.isNotEmpty()) {
                        onMarkerDetected(detections[0], org.opencv.core.Size(mat.width().toDouble(), mat.height().toDouble()))
                    } else {
                        onMarkerDetected(null, org.opencv.core.Size(0.0, 0.0))
                    }

                    mat.release()
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


fun detectAruco(mat: org.opencv.core.Mat): List<ArucoResult> {
    val results = mutableListOf<ArucoResult>()
    val corners = ArrayList<org.opencv.core.Mat>()
    val ids = org.opencv.core.Mat()

    arucoDetector.detectMarkers(mat, corners, ids)

    if (!ids.empty()) {
        for (i in 0 until ids.rows()) {
            val id = ids.get(i, 0)[0].toInt()
            val c = corners[i]

            // FIX: Use FloatArray (size 8 for the 4 corners x,y)
            val pts = FloatArray(8)
            c.get(0, 0, pts)

            // Convert Floats to Double for your math
            val x0 = pts[0].toDouble(); val y0 = pts[1].toDouble() // TL
            val x1 = pts[2].toDouble(); val y1 = pts[3].toDouble() // TR
            val x2 = pts[4].toDouble(); val y2 = pts[5].toDouble() // BR
            val x3 = pts[6].toDouble(); val y3 = pts[7].toDouble() // BL

            // Calculate points
            val tX = (x0 + x1) / 2
            val tY = (y0 + y1) / 2
            val bX = (x2 + x3) / 2
            val bY = (y2 + y3) / 2
            val cX = (x0 + x1 + x2 + x3) / 4
            val cY = (y0 + y1 + y2 + y3) / 4

            val area = org.opencv.imgproc.Imgproc.contourArea(c)
            results.add(ArucoResult(id, tX, tY, bX, bY, cX, cY, area))
        }
    }

    ids.release()
    corners.forEach { it.release() }
    return results
}
data class ArucoResult(
    val id: Int,
    val topX: Double, val topY: Double,
    val bottomX: Double, val bottomY: Double,
    val centerX: Double, val centerY: Double,
    val area: Double
)

fun ImageProxy.toGrayMat(): org.opencv.core.Mat {
    val buffer = planes[0].buffer // The Y-plane is the first plane
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    val mat = org.opencv.core.Mat(height, width, org.opencv.core.CvType.CV_8UC1)
    mat.put(0, 0, data)
    return mat
}
