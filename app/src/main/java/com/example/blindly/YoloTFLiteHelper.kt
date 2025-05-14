package com.example.blindly

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DetectionResult(
    val boundingBox: RectF,
    val score: Float,
    val classIndex: Int
)

class YoloTFLiteHelper(context: Context, modelFileName: String) {
    private val interpreter: Interpreter
    private val inputSize = 640
    private val confidenceThreshold = 0.25f // Lowered to capture more detections
    private val iouThreshold = 0.4f
    private val numClasses = 1 // Only for door

    init {
        try {
            val assetFileDescriptor = context.assets.openFd(modelFileName)
            val inputStream: InputStream = assetFileDescriptor.createInputStream()
            val byteBuffer = convertStreamToByteBuffer(inputStream)
            val options = Interpreter.Options().apply {
                setUseNNAPI(true)
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(byteBuffer, options)
            val inputShape = interpreter.getInputTensor(0).shape().contentToString()
            val outputShape = interpreter.getOutputTensor(0).shape().contentToString()
            Log.d("YoloTFLiteHelper", "Model: $modelFileName, Input shape: $inputShape")
            Log.d("YoloTFLiteHelper", "Model: $modelFileName, Input type: ${interpreter.getInputTensor(0).dataType()}")
            Log.d("YoloTFLiteHelper", "Model: $modelFileName, Output shape: $outputShape")
            if (outputShape != "[1, 5, 8400]") {
                Log.w("YoloTFLiteHelper", "Unexpected output shape: $outputShape, expected [1, 5, 8400]")
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to load YOLO model ($modelFileName): ${e.message}", e)
        }
    }

    fun detect(bitmap: Bitmap, bypassNMS: Boolean = false): List<DetectionResult> {
        return try {
            val startTime = System.nanoTime()
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            byteBuffer.order(ByteOrder.nativeOrder())

            val intValues = IntArray(inputSize * inputSize)
            resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

            var pixel = 0
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val value = intValues[pixel++]
                    byteBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f) // R
                    byteBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)  // G
                    byteBuffer.putFloat((value and 0xFF) / 255.0f)         // B
                }
            }

            val outputBuffer = Array(1) { Array(5) { FloatArray(8400) } }
            interpreter.run(byteBuffer, outputBuffer)

            // Log sample outputs for debugging
            for (i in 0 until minOf(5, 8400)) {
                Log.d("YoloTFLiteHelper", "Output[$i]: x=${outputBuffer[0][0][i]}, y=${outputBuffer[0][1][i]}, " +
                        "w=${outputBuffer[0][2][i]}, h=${outputBuffer[0][3][i]}, score=${outputBuffer[0][4][i]}")
            }

            val detections = processOutput(outputBuffer[0])
            val inferenceTime = (System.nanoTime() - startTime) / 1_000_000.0
            Log.d("YoloTFLiteHelper", "Inference time: $inferenceTime ms, Detections: ${detections.size}")

            if (bypassNMS) detections else applyNMS(detections)
        } catch (e: Exception) {
            Log.e("YoloTFLiteHelper", "Detection failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun processOutput(output: Array<FloatArray>): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()

        for (i in 0 until 8400) {
            val x = output[0][i]
            val y = output[1][i]
            val w = output[2][i]
            val h = output[3][i]
            val classScore = output[4][i]

            // Log raw values for low-scoring detections
            if (classScore > 0.1f && classScore <= confidenceThreshold) {
                Log.d("YoloTFLiteHelper", "Low score detection[$i]: score=$classScore, x=$x, y=$y, w=$w, h=$h")
            }

            if (classScore > confidenceThreshold) {
                // Assume coordinates are normalized [0, 1]; scale to image size
                val scaledX = x * inputSize
                val scaledY = y * inputSize
                val scaledW = w * inputSize
                val scaledH = h * inputSize
                val rect = RectF(
                    scaledX - scaledW / 2,
                    scaledY - scaledH / 2,
                    scaledX + scaledW / 2,
                    scaledY + scaledH / 2
                )
                // Validate bounding box
                if (rect.left >= 0 && rect.top >= 0 && rect.right <= inputSize && rect.bottom <= inputSize) {
                    detections.add(DetectionResult(rect, classScore, 0))
                    Log.d("YoloTFLiteHelper", "Valid detection[$i]: score=$classScore, box=$rect")
                } else {
                    Log.w("YoloTFLiteHelper", "Invalid box[$i]: score=$classScore, box=$rect")
                }
            }
        }

        Log.d("YoloTFLiteHelper", "Total detections before NMS: ${detections.size}")
        return detections
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
        Log.d("YoloTFLiteHelper", "Detections after NMS: ${selectedDetections.size}")
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
            byteBuffer.rewind()
            return byteBuffer
        } catch (e: Exception) {
            throw RuntimeException("Failed to convert model to ByteBuffer: ${e.message}", e)
        }
    }
}