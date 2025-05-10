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

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var yoloHelper: YoloTFLiteHelper
    private lateinit var openCloseYoloHelper: YoloTFLiteHelper
    private lateinit var imageCapture: ImageCapture
    private lateinit var textToSpeech: TextToSpeech

    // Class indices for doordetectionyolo11_float32.tflite (from data.yaml)
    private val DOOR_CLASS = 0
    private val HINGED_CLASS = 1
    private val KNOB_CLASS = 2
    private val LEVER_CLASS = 3

    // Class indices for dooropenclose_float32.tflite (closed, open, semi-open)
    private val CLOSED_CLASS = 0
    private val OPEN_CLASS = 1
    private val SEMI_OPEN_CLASS = 2

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

        // Initialize YOLO TFLite helpers for both models from assets
        yoloHelper = YoloTFLiteHelper(this, "doordetectionyolo11_float32.tflite") // For door, hinged, knob, lever detection
        openCloseYoloHelper = YoloTFLiteHelper(this, "dooropenclose_float32.tflite") // For closed, open, semi-open detection

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
                preview,
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
                    runDetection(rotatedBitmap)
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

    private fun runDetection(bitmap: Bitmap) {
        try {
            // Step 1: Use doordetectionyolo11_float32.tflite for initial detection
            Log.d("Debug", "Starting initial detection with doordetectionyolo11_float32.tflite on bitmap ${bitmap.width}x${bitmap.height}")
            val results = yoloHelper.detect(bitmap)
            if (results.isEmpty()) {
                Log.d("DetectionResult", "No objects detected")
                Toast.makeText(this, "No objects detected", Toast.LENGTH_SHORT).show()
                speak("No door detected in sight. Try turning or walking.")
            } else {
                var doorDetected = false
                var hingedDetected = false
                var knobDetected = false
                var leverDetected = false
                for (result in results) {
                    Log.d("DetectionResult", "Class: ${result.classIndex}, Score: ${result.score}, Box: ${result.boundingBox}")
                    when (result.classIndex) {
                        DOOR_CLASS -> doorDetected = true
                        HINGED_CLASS -> hingedDetected = true
                        KNOB_CLASS -> knobDetected = true
                        LEVER_CLASS -> leverDetected = true
                    }
                }

                // Step 2: Provide navigation instructions based on initial detection
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
                } else if (hingedDetected) {
                    // Step 3: Use dooropenclose_float32.tflite to confirm door state when hinges are detected
                    Log.d("Debug", "Hinges detected, using dooropenclose_float32.tflite to check door state")
                    val openCloseResults = openCloseYoloHelper.detect(bitmap)
                    var doorState = "closed" // Default to closed if no state is detected
                    for (result in openCloseResults) {
                        Log.d("OpenCloseResult", "Class: ${result.classIndex}, Score: ${result.score}, Box: ${result.boundingBox}")
                        when (result.classIndex) {
                            CLOSED_CLASS -> {
                                doorState = "closed"
                                break
                            }
                            OPEN_CLASS -> {
                                doorState = "open"
                                break
                            }
                            SEMI_OPEN_CLASS -> {
                                doorState = "semi-open"
                                break
                            }
                        }
                    }
                    // Step 4: Provide instructions based on door state
                    when (doorState) {
                        "closed" -> speak("Closed door detected. Approach the door and check for a knob or lever to open.")
                        "open" -> speak("Open door detected. Walk forward to pass through.")
                        "semi-open" -> speak("Semi-open door detected. Approach cautiously and push to open fully or pass through carefully.")
                    }
                }
                Toast.makeText(this, "${results.size} objects detected", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DetectionError", "YOLO detection failed: ${e.message}", e)
            Toast.makeText(this, "Detection failed: ${e.message}", Toast.LENGTH_LONG).show()
            speak("Detection failed. Please try again.")
        }
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