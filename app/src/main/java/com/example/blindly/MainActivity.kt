package com.example.blindly

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.*
import java.util.LinkedList
import android.os.Handler
import android.os.Looper

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var yoloHelper: YoloTFLiteHelper
    private lateinit var imageCapture: ImageCapture
    private lateinit var textToSpeech: TextToSpeech
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraRunning = false

    // Class index for the single-class model
    private val DOOR_CLASS = 0

    private var isAutoCaptureRunning = false
    private val autoCaptureHandler = Handler(Looper.getMainLooper())
    private val autoCaptureRunnable = object : Runnable {
        override fun run() {
            if (isAutoCaptureRunning && isCameraRunning) {
                takePhoto()
                autoCaptureHandler.postDelayed(this, 5000) // 5 seconds
            }
        }
    }

    private fun startAutoCapture() {
        if (!isAutoCaptureRunning) {
            isAutoCaptureRunning = true
            autoCaptureHandler.postDelayed(autoCaptureRunnable, 10000)
            Toast.makeText(this, "Auto capture started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAutoCapture() {
        if (isAutoCaptureRunning) {
            isAutoCaptureRunning = false
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable)
            Toast.makeText(this, "Auto capture stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        yoloHelper = YoloTFLiteHelper(this, "yolo12s.tflite")
        textToSpeech = TextToSpeech(this, this)

        setContent {
            val isCameraRunningState = remember { mutableStateOf(true) }
            CameraScreen(
                onCaptureClick = { takePhoto() },
                onAutoCaptureClick = {
                    if (isAutoCaptureRunning) {
                        stopAutoCapture()
                    } else {
                        startAutoCapture()
                    }
                },
                onToggleStreamClick = {
                    if (isCameraRunningState.value) {
                        stopCamera()
                        isCameraRunningState.value = false
                    } else {
                        restartCamera()
                        isCameraRunningState.value = true
                    }
                },
                isAutoCaptureRunning = isAutoCaptureRunning,
                isCameraRunning = isCameraRunningState.value,
                lifecycleOwner = this
            )
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.US
        } else {
            Log.e("TTS", "Text-to-Speech initialization failed")
            Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this as LifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            isCameraRunning = true
            if (isAutoCaptureRunning) {
                autoCaptureHandler.postDelayed(autoCaptureRunnable, 5000)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        runOnUiThread {
            cameraProvider?.unbindAll()
            isCameraRunning = false
            stopAutoCapture()
            Toast.makeText(this, "Camera stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartCamera() {
        runOnUiThread {
            if (!isCameraRunning) {
                startCamera()
                Toast.makeText(this, "Camera started", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun takePhoto() {
        if (!isCameraRunning || cameraProvider == null) {
            Toast.makeText(this, "Cannot capture: Camera is stopped", Toast.LENGTH_SHORT).show()
            return
        }
        val photoFile = File(externalCacheDir, "photo.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    val rotatedBitmap = rotateBitmapIfNeeded(bitmap)
                    processImage(rotatedBitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CaptureError", "Error capturing photo: ${exception.message}", exception)
                    Toast.makeText(this@MainActivity, "Error capturing photo", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(90f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun processImage(bitmap: Bitmap) {
        try {
            if (detectObstacle(bitmap)) {
                Log.d("ObstacleDetection    ", "Obstacle detected in lower region")
                speak("Obstacle detected ahead. Stop immediately.")
                Toast.makeText(this, "Obstacle detected", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d("Debug", "Starting detection with doordetectionyolo11_float32.tflite on bitmap ${bitmap.width}x${bitmap.height}")
            val results = yoloHelper.detect(bitmap)
            if (results.isEmpty()) {
                Log.d("DetectionResult", "No door detected")
                Toast.makeText(this, "No door detected", Toast.LENGTH_SHORT).show()
                speak("No door detected in sight. Try turning or walking.")
            } else {
                val doorResult = results.firstOrNull { it.classIndex == DOOR_CLASS }
                if (doorResult != null) {
                    val centerX = doorResult.boundingBox.centerX()
                    val imageWidth = bitmap.width
                    Log.d("PositionDebug", "Door detected: centerX=$centerX, imageWidth=$imageWidth, score=${doorResult.score}")
                    val speechText = when {
                        centerX < imageWidth / 5 -> "Door detected with high probability on the left. Turn slightly left and walk forward."
                        centerX > 4 * imageWidth / 5 -> "Door detected with high probability on the right. Turn slightly right and walk forward."
                        else -> "Door detected with high probability ahead. Walk straight forward."
                    }
                    speak(speechText)
                    Toast.makeText(this, "Door detected (score: ${String.format("%.2f", doorResult.score)})", Toast.LENGTH_SHORT).show()
                } else {
                    Log.d("DetectionResult", "Unexpected class detected")
                    Toast.makeText(this, "Unexpected detection", Toast.LENGTH_SHORT).show()
                    speak("No door detected. Try turning or walking.")
                }
            }
        } catch (e: Exception) {
            Log.e("DetectionError", "YOLO detection failed: ${e.message}", e)
            Toast.makeText(this, "Detection failed: ${e.message}", Toast.LENGTH_LONG).show()
            speak("Detection failed. Please try again.")
        }
    }

    private fun detectObstacle(bitmap: Bitmap): Boolean {
        val height = bitmap.height
        val width = bitmap.width
        val startY = (height * 0.7).toInt()
        val lowerRegionHeight = height - startY

        try {
            val grayValues = mutableListOf<Int>()
            for (y in startY until height) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    val gray = (0.299f * ((pixel shr 16) and 0xFF) +
                            0.587f * ((pixel shr 8) and 0xFF) +
                            0.114f * (pixel and 0xFF)).toInt()
                    grayValues.add(gray)
                }
            }

            val meanGray = if (grayValues.isNotEmpty()) grayValues.sum() / grayValues.size else 128
            val threshold = meanGray - 20
            Log.d("ObstacleDetection", "Mean Gray: $meanGray, Threshold: $threshold")

            val binary = Array(lowerRegionHeight) { IntArray(width) }
            for (y in startY until height) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    val gray = (0.299f * ((pixel shr 16) and 0xFF) +
                            0.587f * ((pixel shr 8) and 0xFF) +
                            0.114f * (pixel and 0xFF)).toInt()
                    binary[y - startY][x] = if (gray < threshold) 1 else 0
                }
            }

            var maxRegionSize = 0
            val visited = Array(lowerRegionHeight) { BooleanArray(width) }
            val queue = LinkedList<Pair<Int, Int>>()

            for (y in 0 until lowerRegionHeight) {
                for (x in 0 until width) {
                    if (binary[y][x] == 1 && !visited[y][x]) {
                        queue.offer(Pair(y, x))
                        visited[y][x] = true
                        var regionSize = 0

                        while (queue.isNotEmpty()) {
                            val (currentY, currentX) = queue.poll()
                            regionSize++

                            for ((dy, dx) in listOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))) {
                                val newY = currentY + dy
                                val newX = currentX + dx
                                if (newY in 0 until lowerRegionHeight && newX in 0 until width &&
                                    !visited[newY][newX] && binary[newY][newX] == 1) {
                                    queue.offer(Pair(newY, newX))
                                    visited[newY][newX] = true
                                }
                            }
                        }
                        maxRegionSize = maxOf(maxRegionSize, regionSize)
                    }
                }
            }

            val totalPixels = lowerRegionHeight * width
            val regionProportion = maxRegionSize.toFloat() / totalPixels
            Log.d("ObstacleDetection", "Largest region size: $maxRegionSize, Proportion: $regionProportion")

            val proportionThreshold = 0.15f
            val isObstacle = regionProportion > proportionThreshold
            Log.d("ObstacleDetection", "Obstacle detected: $isObstacle")

            return isObstacle
        } catch (e: Exception) {
            Log.e("ObstacleDetection", "Error in detectObstacle: ${e.message}", e)
            return false
        }
    }

    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoCapture()
        textToSpeech.stop()
        textToSpeech.shutdown()
        cameraProvider?.unbindAll()
    }
}

@Composable
fun CameraScreen(
    onCaptureClick: () -> Unit,
    onAutoCaptureClick: () -> Unit,
    onToggleStreamClick: () -> Unit,
    isAutoCaptureRunning: Boolean,
    isCameraRunning: Boolean,
    lifecycleOwner: LifecycleOwner
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    if (isCameraRunning) {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(this.surfaceProvider)
                            }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onCaptureClick,
                modifier = Modifier.weight(1f),
                enabled = isCameraRunning
            ) {
                Text("Manual Capture")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAutoCaptureClick,
                modifier = Modifier.weight(1f),
                enabled = isCameraRunning,
                colors = if (isAutoCaptureRunning) {
                    ButtonDefaults.buttonColors(containerColor = Color.Red)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (isAutoCaptureRunning) "Stop Auto" else "Start Auto")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onToggleStreamClick,
                modifier = Modifier.weight(1f),
                colors = if (isCameraRunning) {
                    ButtonDefaults.buttonColors(containerColor = Color.Gray)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (isCameraRunning) "Stop Stream" else "Start Stream")
            }
        }
    }
}