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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.*
import kotlin.math.abs
import java.util.LinkedList

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var yoloHelper: YoloTFLiteHelper
    private lateinit var imageCapture: ImageCapture
    private lateinit var textToSpeech: TextToSpeech

    // Class indices for doordetectionyolo11_float32.tflite (from data.yaml)
    private val DOOR_CLASS = 0
    private val HINGED_CLASS = 1
    private val KNOB_CLASS = 2
    private val LEVER_CLASS = 3

    // Request camera permissions at runtime
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

        // Initialize YOLO TFLite helper for door detection model from assets
        yoloHelper = YoloTFLiteHelper(this, "doordetectionyolo11_float32.tflite")

        // Initialize Text-to-Speech (local, offline)
        textToSpeech = TextToSpeech(this, this)

        // Set Compose UI
        setContent {
            CameraScreen(
                onCaptureClick = { takePhoto() },
                lifecycleOwner = this
            )
        }

        // Check camera permissions
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
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this as LifecycleOwner,
                cameraSelector,
                preview, // Corrected from previewEmail to preview
                imageCapture
            )

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
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
            // Step 1: Check for obstacles in the lower part of the image
            if (detectObstacle(bitmap)) {
                Log.d("ObstacleDetection", "Obstacle detected in lower region")
                speak("Obstacle detected ahead. Stop immediately.")
                Toast.makeText(this, "Obstacle detected", Toast.LENGTH_SHORT).show()
                return // Prioritize obstacle warning over door detection
            }

            // Step 2: Run YOLO detection for doors, knobs, levers
            Log.d("Debug", "Starting detection with doordetectionyolo11_float32.tflite on bitmap ${bitmap.width}x${bitmap.height}")
            val results = yoloHelper.detect(bitmap)
            if (results.isEmpty()) {
                Log.d("DetectionResult", "No objects detected")
                Toast.makeText(this, "No objects detected", Toast.LENGTH_SHORT).show()
                speak("No door detected in sight. Try turning or walking.")
            } else {
                var doorDetected = false
                var knobDetected = false
                var leverDetected = false
                for (result in results) {
                    Log.d("DetectionResult", "Class: ${result.classIndex}, Score: ${result.score}, Box: ${result.boundingBox}")
                    when (result.classIndex) {
                        DOOR_CLASS -> doorDetected = true
                        KNOB_CLASS -> knobDetected = true
                        LEVER_CLASS -> leverDetected = true
                    }
                }

                // Provide navigation instructions based on detection
                if (doorDetected) {
                    val centerX = results.first { it.classIndex == DOOR_CLASS }.boundingBox.centerX()
                    val imageWidth = bitmap.width
                    Log.d("PositionDebug", "Door detected: centerX=$centerX, imageWidth=$imageWidth, centerX/imageWidth=${centerX/imageWidth}")
                    // Middle region: 20% to 80% of image width
                    if (centerX < imageWidth / 5) { // Left: 0 to 20%
                        speak("Door detected on the left. Turn slightly left and walk forward.")
                    } else if (centerX > 4 * imageWidth / 5) { // Right: 80% to 100%
                        speak("Door detected on the right. Turn slightly right and walk forward.")
                    } else { // Middle: 20% to 80%
                        speak("Door detected ahead. Walk straight forward.")
                    }
                } else if (knobDetected) {
                    val centerX = results.first { it.classIndex == KNOB_CLASS }.boundingBox.centerX()
                    val imageWidth = bitmap.width
                    Log.d("PositionDebug", "Knob detected: centerX=$centerX, imageWidth=$imageWidth, centerX/imageWidth=${centerX/imageWidth}")
                    // Middle region: 20% to 80% of image width
                    if (centerX < imageWidth / 5) { // Left: 0 to 20%
                        speak("Door knob detected on the left. Turn slightly left and approach to open.")
                    } else if (centerX > 4 * imageWidth / 5) { // Right: 80% to 100%
                        speak("Door knob detected on the right. Turn slightly right and approach to open.")
                    } else { // Middle: 20% to 80%
                        speak("Door knob detected ahead. Walk forward and prepare to open the door.")
                    }
                } else if (leverDetected) {
                    val centerX = results.first { it.classIndex == LEVER_CLASS }.boundingBox.centerX()
                    val imageWidth = bitmap.width
                    Log.d("PositionDebug", "Lever detected: centerX=$centerX, imageWidth=$imageWidth, centerX/imageWidth=${centerX/imageWidth}")
                    // Middle region: 20% to 80% of image width
                    if (centerX < imageWidth / 5) { // Left: 0 to 20%
                        speak("Door lever detected on the left. Turn slightly left and approach to open.")
                    } else if (centerX > 4 * imageWidth / 5) { // Right: 80% to 100%
                        speak("Door lever detected on the right. Turn slightly right and approach to open.")
                    } else { // Middle: 20% to 80%
                        speak("Door lever detected ahead. Walk forward and prepare to open the door.")
                    }
                } else {
                    speak("No actionable objects detected. Try turning or walking.")
                }
                Toast.makeText(this, "${results.size} objects detected", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DetectionError", "YOLO detection failed: ${e.message}", e)
            Toast.makeText(this, "Detection failed: ${e.message}", Toast.LENGTH_LONG).show()
            speak("Detection failed. Please try again.")
        }
    }


    private fun detectObstacle(bitmap: Bitmap): Boolean {
        // Analyze the lower 30% of the image for distinct regions (potential obstacles)
        val height = bitmap.height
        val width = bitmap.width
        val startY = (height * 0.7).toInt() // Start from 70% down the image
        val lowerRegionHeight = height - startY

        try {
            // Step 1: Convert lower region to grayscale and calculate a threshold
            val grayValues = mutableListOf<Int>()
            for (y in startY until height) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    // Convert to grayscale: 0.299R + 0.587G + 0.114B
                    val gray = (0.299f * ((pixel shr 16) and 0xFF) +
                            0.587f * ((pixel shr 8) and 0xFF) +
                            0.114f * (pixel and 0xFF)).toInt()
                    grayValues.add(gray)
                }
            }

            // Calculate a simple threshold using the mean grayscale value
            val meanGray = if (grayValues.isNotEmpty()) grayValues.sum() / grayValues.size else 128
            val threshold = meanGray - 20 // Lower threshold to capture darker objects like the stove
            Log.d("ObstacleDetection", "Mean Gray: $meanGray, Threshold: $threshold")

            // Step 2: Binarize the image (1 for foreground, 0 for background)
            val binary = Array(lowerRegionHeight) { IntArray(width) }
            for (y in startY until height) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    val gray = (0.299f * ((pixel shr 16) and 0xFF) +
                            0.587f * ((pixel shr 8) and 0xFF) +
                            0.114f * (pixel and 0xFF)).toInt()
                    binary[y - startY][x] = if (gray < threshold) 1 else 0 // Darker pixels are foreground
                }
            }

            // Step 3: Find the largest contiguous region using an iterative flood-fill
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

                            // Check all four directions
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

            // Step 4: Determine if the largest region indicates an obstacle
            val totalPixels = lowerRegionHeight * width
            val regionProportion = maxRegionSize.toFloat() / totalPixels
            Log.d("ObstacleDetection", "Largest region size: $maxRegionSize, Proportion: $regionProportion")

            // Consider it an obstacle if the region is significant (e.g., >15% of the lower region)
            val proportionThreshold = 0.15f
            val isObstacle = regionProportion > proportionThreshold
            Log.d("ObstacleDetection", "Obstacle detected: $isObstacle")

            return isObstacle
        } catch (e: Exception) {
            Log.e("ObstacleDetection", "Error in detectObstacle: ${e.message}", e)
            return false // Fallback to avoid crash
        }
    }

    // Helper function: Flood-fill algorithm to find the size of a contiguous region
    private fun floodFill(binary: Array<IntArray>, visited: Array<BooleanArray>, y: Int, x: Int, height: Int, width: Int): Int {
        if (y < 0 || y >= height || x < 0 || x >= width || visited[y][x] || binary[y][x] == 0) {
            return 0
        }

        visited[y][x] = true
        var size = 1

        // Recursively check neighboring pixels (4-directional)
        size += floodFill(binary, visited, y - 1, x, height, width) // Up
        size += floodFill(binary, visited, y + 1, x, height, width) // Down
        size += floodFill(binary, visited, y, x - 1, height, width) // Left
        size += floodFill(binary, visited, y, x + 1, height, width) // Right

        return size
    }

    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.stop()
        textToSpeech.shutdown()
    }
}

@Composable
fun CameraScreen(onCaptureClick: () -> Unit, lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
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
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCaptureClick) {
            Text("Capture Photo")
        }
    }
}