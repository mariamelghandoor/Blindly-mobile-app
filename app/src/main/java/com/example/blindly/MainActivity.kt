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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.*
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import kotlin.math.min

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var doorYoloHelper: YoloTFLiteHelper
    private lateinit var obstacleYoloHelper: YoloTFLiteHelper
    private lateinit var midasHelper: MidasTFLiteHelper
    private lateinit var imageCapture: ImageCapture
    private lateinit var textToSpeech: TextToSpeech
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraRunning = false

    // Class indices for doordetectionyolo11_float32.tflite
    private val DOOR_CLASS = 0
    private val HINGED_CLASS = 1
    private val KNOB_CLASS = 2
    private val LEVER_CLASS = 3

    // obstacle classes for YOLOv8 (COCO dataset indices, adjust based on your model)
    private val OBSTACLE_CLASSES = setOf(
        0,  // person
        56, // chair
        57, // couch
        58, // potted plant
        59, // bed
        60, // dining table
        62, // tv
        63, // laptop
        65  // refrigerator
    )

    private var isAutoCaptureRunning = false
    private val autoCaptureHandler = Handler(Looper.getMainLooper())
    private val autoCaptureRunnable = object : Runnable {
        override fun run() {
            if (isAutoCaptureRunning && isCameraRunning) {
                takePhoto()
                autoCaptureHandler.postDelayed(this, 5000)
            }
        }
    }

    private fun startAutoCapture() {
        if (!isAutoCaptureRunning) {
            isAutoCaptureRunning = true
            autoCaptureHandler.postDelayed(autoCaptureRunnable, 5000)
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

        doorYoloHelper = YoloTFLiteHelper(this, "doordetectionyolo11_float32.tflite", inputSize = 1280)
        obstacleYoloHelper = YoloTFLiteHelper(this, "yolov8n_float32.tflite", inputSize = 640)
        midasHelper = MidasTFLiteHelper(this, "MiDas.tflite")
        textToSpeech = TextToSpeech(this, this)

        setContent {
            val isCameraRunning = remember { mutableStateOf(true) }
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
                    if (isCameraRunning.value) {
                        stopCamera()
                        isCameraRunning.value = false
                    } else {
                        restartCamera()
                        isCameraRunning.value = true
                    }
                },
                isAutoCaptureRunning = isAutoCaptureRunning,
                isCameraRunning = isCameraRunning.value,
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
        cameraProvider?.unbindAll()
        isCameraRunning = false
        stopAutoCapture()
        Toast.makeText(this, "Camera stream stopped", Toast.LENGTH_SHORT).show()
    }

    private fun restartCamera() {
        startCamera()
        Toast.makeText(this, "Camera stream started", Toast.LENGTH_SHORT).show()
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
            // First check for obstacles
            val obstacleDetected = detectObstacle(bitmap)

            // Only proceed with door detection if no obstacles were found
            if (!obstacleDetected) {
                Log.d("Detection", "No obstacles detected, proceeding with door detection")
                val results = doorYoloHelper.detect(bitmap)

                if (results.isEmpty()) {
                    Log.d("DetectionResult", "No objects detected")
                    speak("No door detected in sight. Try turning or walking.")
                } else {
                    var doorDetected = false
                    var knobDetected = false
                    var leverDetected = false

                    for (result in results) {
                        when (result.classIndex) {
                            DOOR_CLASS -> doorDetected = true
                            KNOB_CLASS -> knobDetected = true
                            LEVER_CLASS -> leverDetected = true
                        }
                    }

                    if (doorDetected) {
                        val centerX = results.first { it.classIndex == DOOR_CLASS }.boundingBox.centerX()
                        val imageWidth = bitmap.width
                        if (centerX < imageWidth / 5) {
                            speak("Door detected on the left. Turn slightly left and walk forward.")
                        } else if (centerX > 4 * imageWidth / 5) {
                            speak("Door detected on the right. Turn slightly right and walk forward.")
                        } else {
                            speak("Door detected ahead. Walk straight forward.")
                        }
                    } else if (knobDetected) {
                        val centerX = results.first { it.classIndex == KNOB_CLASS }.boundingBox.centerX()
                        val imageWidth = bitmap.width
                        Log.d("PositionDebug", "Knob detected: centerX=$centerX, imageWidth=$imageWidth")
                        if (centerX < imageWidth / 5) {
                            speak("Door knob detected on the left. Turn slightly left and approach to open.")
                        } else if (centerX > 4 * imageWidth / 5) {
                            speak("Door knob detected on the right. Turn slightly right and approach to open.")
                        } else {
                            speak("Door knob detected ahead. Walk forward and prepare to open the door.")
                        }
                    } else if (leverDetected) {
                        val centerX = results.first { it.classIndex == LEVER_CLASS }.boundingBox.centerX()
                        val imageWidth = bitmap.width
                        Log.d("PositionDebug", "Lever detected: centerX=$centerX, imageWidth=$imageWidth")
                        if (centerX < imageWidth / 5) {
                            speak("Door lever detected on the left. Turn slightly left and approach to open.")
                        } else if (centerX > 4 * imageWidth / 5) {
                            speak("Door lever detected on the right. Turn slightly right and approach to open.")
                        } else {
                            speak("Door lever detected ahead. Walk forward and prepare to open the door.")
                        }
                    } else {
                        speak("No actionable objects detected. Try turning or walking.")
                    }
                }
            } else {
                Log.d("Detection", "Obstacle detected - skipping door detection")
            }
        } catch (e: Exception) {
            Log.e("DetectionError", "Detection failed: ${e.message}", e)
            speak("Detection failed. Please try again.")
        }
    }

    private fun detectObstacle(bitmap: Bitmap): Boolean {
        try {
            // Run YOLOv8 for obstacle detection
            val obstacleResults = obstacleYoloHelper.detect(bitmap)
            if (obstacleResults.isEmpty()) {
                Log.d("ObstacleDetection", "No obstacles detected by YOLOv8")
            } else {
                Log.d("ObstacleDetection", "YOLOv8 detected ${obstacleResults.size} objects")
                for (result in obstacleResults) {
                    Log.d("ObstacleDetection", "Class: ${result.classIndex}, Score: ${result.score}, Box: ${result.boundingBox}")
                }
            }

            // Get depth map from MiDaS
            val depthMap = midasHelper.estimateDepth(bitmap)
            val depthMapWidth = depthMap[0].size
            val depthMapHeight = depthMap.size

            // Process obstacles with depth information
            var nearestObstacle: DetectionResult? = null
            var minDepth = Float.MAX_VALUE
            var obstacleDirection = ""
            val imageWidth = bitmap.width
            val imageHeight = bitmap.height
            val originalAspectRatio = imageWidth.toFloat() / imageHeight

            // Preprocess bitmap for centered 640x640 with padding
            val resizedBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(resizedBitmap)
            val scale = min(640f / imageWidth, 640f / imageHeight)
            val newWidth = (imageWidth * scale).toInt()
            val newHeight = (imageHeight * scale).toInt()
            val left = (640 - newWidth) / 2
            val top = (640 - newHeight) / 2
            canvas.drawBitmap(Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true), left.toFloat(), top.toFloat(), null)

            for (result in obstacleResults) {
                if (result.score > 0.2f) {
                    // Include high-confidence unknown objects or potential tables
                    val isObstacleClass = result.classIndex in OBSTACLE_CLASSES ||
                            (result.score > 0.6f && result.boundingBox.height() > 0.1f * imageHeight) // Heuristic for table
                    if (isObstacleClass) {
                        val box = result.boundingBox
                        // Scale bounding box to depth map coordinates
                        val left = (box.left * depthMapWidth / imageWidth).toInt().coerceIn(0, depthMapWidth - 1)
                        val right = (box.right * depthMapWidth / imageWidth).toInt().coerceIn(0, depthMapWidth - 1)
                        val top = (box.top * depthMapHeight / imageHeight).toInt().coerceIn(0, depthMapHeight - 1)
                        val bottom = (box.bottom * depthMapHeight / imageHeight).toInt().coerceIn(0, depthMapHeight - 1)

                        // Calculate average depth in the bounding box
                        var depthSum = 0f
                        var pixelCount = 0
                        for (y in top until bottom) {
                            for (x in left until right) {
                                depthSum += depthMap[y][x]
                                pixelCount++
                            }
                        }
                        val avgDepth = if (pixelCount > 0) depthSum / pixelCount else Float.MAX_VALUE

                        // Update nearest obstacle with proximity alert
                        if (avgDepth < minDepth) {
                            minDepth = avgDepth
                            nearestObstacle = result
                            val centerX = box.centerX()
                            obstacleDirection = when {
                                centerX < imageWidth / 5 -> "left"
                                centerX > 4 * imageWidth / 5 -> "right"
                                else -> "ahead"
                            }
                            if (avgDepth < 0.5f) { // Imminent obstacle alert
                                speak("Stop! Obstacle very close ahead, less than half a meter!")
                                return true
                            }
                        }
                    }
                }
            }

            // Fallback: Check lower region with depth, vertical span, and higher threshold
            if (nearestObstacle == null) {
                val height = bitmap.height
                val width = bitmap.width
                val startY = (height * 0.5).toInt()
                val lowerRegionHeight = height - startY

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
                val meanGray = grayValues.sum() / grayValues.size
                val threshold = (meanGray - 20).toInt().coerceIn(0, 255)

                val binary = Array(lowerRegionHeight) { IntArray(width) }
                val depthValues = Array(lowerRegionHeight) { FloatArray(width) }
                for (y in startY until height) {
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(x, y)
                        val gray = (0.299f * ((pixel shr 16) and 0xFF) +
                                0.587f * ((pixel shr 8) and 0xFF) +
                                0.114f * (pixel and 0xFF)).toInt()
                        val depthY = (y - startY).coerceIn(0, depthMapHeight - 1)
                        val depthX = (x * depthMapWidth / width).coerceIn(0, depthMapWidth - 1)
                        val depth = depthMap[depthY][depthX]
                        binary[y - startY][x] = if (gray < threshold) 1 else 0
                        depthValues[y - startY][x] = depth
                    }
                }

                val visited = Array(lowerRegionHeight) { BooleanArray(width) }
                val queue = LinkedList<Pair<Int, Int>>()
                var maxRegionSize = 0
                var maxRegionDepth = Float.MAX_VALUE
                var maxVerticalSpan = 0

                for (y in 0 until lowerRegionHeight) {
                    for (x in 0 until width) {
                        if (binary[y][x] == 1 && !visited[y][x]) {
                            queue.offer(Pair(y, x))
                            visited[y][x] = true
                            var regionSize = 0
                            var regionDepthSum = 0f
                            var regionPixelCount = 0
                            var minY = y
                            var maxY = y

                            while (queue.isNotEmpty()) {
                                val (currentY, currentX) = queue.poll()
                                regionSize++
                                regionDepthSum += depthValues[currentY][currentX]
                                regionPixelCount++
                                minY = minOf(minY, currentY)
                                maxY = maxOf(maxY, currentY)

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
                            val avgRegionDepth = if (regionPixelCount > 0) regionDepthSum / regionPixelCount else Float.MAX_VALUE
                            val verticalSpan = maxY - minY + 1
                            if (regionSize > maxRegionSize && avgRegionDepth < 5f && verticalSpan < lowerRegionHeight * 0.8f) {
                                maxRegionSize = regionSize
                                maxRegionDepth = avgRegionDepth
                                maxVerticalSpan = verticalSpan
                            }
                        }
                    }
                }

                val totalPixels = lowerRegionHeight * width
                val regionProportion = maxRegionSize.toFloat() / totalPixels
                Log.d("ObstacleDetection", "Fallback: Proportion: $regionProportion, Depth: $maxRegionDepth m, Vertical Span: $maxVerticalSpan")
                if (regionProportion > 0.2f && maxRegionDepth < 3f) { // Increased to 20% with depth and span check
                    val centerX = width / 2f
                    obstacleDirection = when {
                        centerX < imageWidth / 5 -> "left"
                        centerX > 4 * imageWidth / 5 -> "right"
                        else -> "ahead"
                    }
                    speak("Obstacle detected $obstacleDirection. Proceed with caution.")
                    return true
                }
            }

            // Provide feedback for the nearest obstacle
            if (nearestObstacle != null) {
                val classIndex = nearestObstacle.classIndex
                val className = when (classIndex) {
                    0 -> "person"
                    56 -> "chair"
                    57 -> "couch"
                    58 -> "potted plant"
                    59 -> "bed"
                    60 -> "table"
                    62 -> "TV"
                    63 -> "laptop"
                    65 -> "refrigerator"
                    else -> if (nearestObstacle.score > 0.6f) "unknown obstacle" else "ignored object"
                }
                val depthMeters = minDepth.coerceIn(0f, 10f)
                Log.d("ObstacleDetection", "Nearest obstacle: $className, Depth: $depthMeters m, Direction: $obstacleDirection")
                Toast.makeText(this, "$className detected at $depthMeters meters", Toast.LENGTH_SHORT).show()

                if (depthMeters < 0.5f) {
                    speak("Stop! $className very close ahead, less than half a meter!")
                } else if (depthMeters < 3f) {
                    speak("$className detected $depthMeters meters $obstacleDirection. Proceed with caution.")
                }
                return true
            }

            Log.d("ObstacleDetection", "No significant obstacles within 3 meters")
            return false
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