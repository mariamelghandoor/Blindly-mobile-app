package com.example.blindly

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

class MidasTFLiteHelper(context: Context, modelFileName: String) {
    private val interpreter: Interpreter
    private val inputSize = 256 // MiDaS small typically uses 256x256

    init {
        try {
            val assetFileDescriptor = context.assets.openFd(modelFileName)
            val inputStream: InputStream = assetFileDescriptor.createInputStream()
            val byteBuffer = inputStream.readBytes().let {
                ByteBuffer.allocateDirect(it.size).apply {
                    put(it)
                    rewind()
                }
            }
            interpreter = Interpreter(byteBuffer)
            Log.d("MidasTFLiteHelper", "Model: $modelFileName loaded, Input shape: ${interpreter.getInputTensor(0).shape().contentToString()}")
        } catch (e: Exception) {
            throw RuntimeException("Failed to load MiDaS model ($modelFileName): ${e.message}", e)
        }
    }

    fun estimateDepth(bitmap: Bitmap): Array<FloatArray> {
        try {
            // Resize and preprocess bitmap
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

            // Output shape: [1, 256, 256, 1]
            val outputShape = interpreter.getOutputTensor(0).shape()
            val outputBuffer = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }

            // Run inference
            interpreter.run(inputBuffer, outputBuffer)

            // Post-process depth map (MiDaS outputs inverse depth, convert to linear depth)
            val depthMap = Array(inputSize) { FloatArray(inputSize) }
            val maxDepth = 10f // Max depth in meters (adjust based on environment)
            for (i in 0 until inputSize) {
                for (j in 0 until inputSize) {
                    val inverseDepth = outputBuffer[0][i][j]
                    depthMap[i][j] = maxDepth / (1f + exp(-inverseDepth)) // Approximate linear depth
                }
            }
            return depthMap
        } catch (e: Exception) {
            Log.e("MidasTFLiteHelper", "Depth estimation failed: ${e.message}", e)
            return Array(inputSize) { FloatArray(inputSize) } // Return empty depth map on error
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
}