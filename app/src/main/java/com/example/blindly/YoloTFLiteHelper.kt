package com.example.blindly

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(
    val boundingBox: RectF,
    val score: Float,
    val classIndex: Int
)

class YoloTFLiteHelper(context: Context, modelFileName: String) {
    private val interpreter: Interpreter
    private val inputSize = 1280
    private val confidenceThreshold = 0.5f
    private val iouThreshold = 0.4f
    private val numClasses = if (modelFileName.contains("openclose")) 3 else 4 // 3 for closed/open/semi-open, 4 for door/hinged/knob/lever

    init {
        try {
            // Load model from assets folder
            val assetFileDescriptor = context.assets.openFd(modelFileName)
            val inputStream: InputStream = assetFileDescriptor.createInputStream()
            val byteBuffer = convertStreamToByteBuffer(inputStream)
            val options = Interpreter.Options().apply {
                setUseNNAPI(true) // Enable NNAPI
            }
            interpreter = Interpreter(byteBuffer)
            Log.d("YoloTFLiteHelper", "Model: $modelFileName, Input shape: ${interpreter.getInputTensor(0).shape().contentToString()}")
            Log.d("YoloTFLiteHelper", "Model: $modelFileName, Input type: ${interpreter.getInputTensor(0).dataType()}")
            Log.d("YoloTFLiteHelper", "Model: $modelFileName, Output shape: ${interpreter.getOutputTensor(0).shape().contentToString()}")
        } catch (e: Exception) {
            throw RuntimeException("Failed to load YOLO model ($modelFileName): ${e.message}", e)
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        try {
            // Resize and preprocess bitmap
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

            // Prepare output buffer based on model output shape [1, 8, 33600]
            val outputShape = interpreter.getOutputTensor(0).shape()
            val outputBuffer = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }

            // Run inference
            interpreter.run(inputBuffer, outputBuffer)

            // Process output
            return processOutput(outputBuffer[0])
        } catch (e: Exception) {
            Log.e("YoloTFLiteHelper", "Detection failed for : ${e.message}", e)
            return emptyList()
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4) // Float32
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in intValues) {
            byteBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            byteBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            byteBuffer.putFloat((pixel and 0xFF) / 255.0f)         // B
        }
        return byteBuffer
    }

    private fun processOutput(output: Array<FloatArray>): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()

        // Output shape: [8, 33600] where first 4 are box coordinates, next are class probabilities
        for (i in 0 until output[0].size) { // Iterate over 33600 detections
            val x = output[0][i] * inputSize // Scale if normalized
            val y = output[1][i] * inputSize
            val w = output[2][i] * inputSize
            val h = output[3][i] * inputSize
            val confidence = output[4][i] // Objectness score

            // Find the class with the highest probability
            var maxClassScore = 0f
            var classIndex = -1
            for (j in 0 until numClasses) {
                val classScore = output[4 + j][i]
                if (classScore > maxClassScore) {
                    maxClassScore = classScore
                    classIndex = j
                }
            }

            val score = confidence * maxClassScore
            if (score > confidenceThreshold) {
                val rect = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
                detections.add(DetectionResult(rect, score, classIndex))
            }
        }

        // Apply Non-Max Suppression
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        val sortedDetections = detections.sortedByDescending { it.score }
        val selectedDetections = mutableListOf<DetectionResult>()

        for (det in sortedDetections) {
            var keep = true
            for (selected in selectedDetections) {
                if (computeIoU(det.boundingBox, selected.boundingBox) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) selectedDetections.add(det)
        }
        return selectedDetections
    }

    private fun computeIoU(box1: RectF, box2: RectF): Float {
        val x1 = maxOf(box1.left, box2.left)
        val y1 = maxOf(box1.top, box2.top)
        val x2 = minOf(box1.right, box2.right)
        val y2 = minOf(box1.bottom, box2.bottom)

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        return if (area1 + area2 - intersection > 0) intersection / (area1 + area2 - intersection) else 0f
    }

    private fun convertStreamToByteBuffer(inputStream: InputStream): ByteBuffer {
        try {
            val byteArray = inputStream.readBytes()
            val byteBuffer = ByteBuffer.allocateDirect(byteArray.size)
            byteBuffer.put(byteArray)
            byteBuffer.rewind() // Prepare buffer for reading
            return byteBuffer
        } catch (e: Exception) {
            throw RuntimeException("Failed to convert model to ByteBuffer: ${e.message}", e)
        }
    }
}