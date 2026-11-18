package com.steve1316.uma_android_automation.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.steve1316.automation_library.data.SharedData
import java.nio.FloatBuffer
import java.util.Collections

/**
 * Helper class for YOLOv8 object detection using ONNX Runtime.
 *
 * This class handles the initialization of the ONNX environment and session, preprocessing of images (including letterboxing and normalization), inference, and post-processing (parsing detections and
 * applying Non-Maximum Suppression).
 *
 * @property context The application context for accessing assets.
 */
class YoloDetector(private val context: Context) {
    /** The ONNX Runtime environment instance. */
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** The active ONNX Runtime session for model inference. */
    private var ortSession: OrtSession? = null

    /**
     * Data class representing a single detected object in the image.
     *
     * @property x Top-left X coordinate in the original image space.
     * @property y Top-left Y coordinate in the original image space.
     * @property w Width of the bounding box in the original image space.
     * @property h Height of the bounding box in the original image space.
     * @property label The detected class label.
     * @property confidence The detection confidence score (0.0 to 1.0).
     */
    data class Detection(val x: Float, val y: Float, val w: Float, val h: Float, val label: String, val confidence: Float)

    /**
     * Internal helper to store letterbox transformation parameters.
     *
     * @property bitmap The resized and padded bitmap for model input.
     * @property ratio The scaling ratio applied to the original image.
     * @property padX Horizontal padding applied to maintain aspect ratio.
     * @property padY Vertical padding applied to maintain aspect ratio.
     */
    private data class LetterboxInfo(val bitmap: Bitmap, val ratio: Float, val padX: Float, val padY: Float)

    companion object {
        private const val TAG = "${SharedData.loggerTag}YoloDetector"

        /** Path to the ONNX model file in the /assets directory. */
        private const val MODEL_PATH = "best.onnx"

        /** Required input dimensions for the YOLO model. */
        private const val INPUT_SIZE = 128

        /** Minimum confidence score for a detection to be considered valid. */
        private const val CONFIDENCE_THRESHOLD = 0.8f

        /** Overlap threshold for Non-Maximum Suppression. */
        private const val IOU_THRESHOLD = 0.45f

        /** List of class labels supported by the YOLO model. */
        private val LABELS = listOf("+", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9")

        /** Index for the first batch in the model output tensor. */
        private const val BATCH_INDEX = 0
    }

    init {
        loadModel()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /** Loads the ONNX model from the application assets. */
    private fun loadModel() {
        try {
            Log.d(TAG, "[DEBUG] loadMode:: Loading model from $MODEL_PATH...")
            val modelBytes = context.assets.open(MODEL_PATH).readBytes()
            ortSession = ortEnv.createSession(modelBytes)
            Log.i(TAG, "[YOLO] Model loaded successfully with session: $ortSession")
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] loadModel:: Error loading model: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Performs letterbox resizing to prepare the image for YOLO input while maintaining aspect ratio.
     *
     * @param bitmap The source bitmap to be resized.
     * @return A [LetterboxInfo] object containing the transformed bitmap and its scaling metadata.
     */
    private fun letterbox(bitmap: Bitmap): LetterboxInfo {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val ratio = minOf(INPUT_SIZE / w, INPUT_SIZE / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()

        // Create a blank canvas with the model's expected input size.
        val letterboxBitmap = createBitmap(INPUT_SIZE, INPUT_SIZE)
        val canvas = Canvas(letterboxBitmap)

        // Fill background with gray.
        canvas.drawColor(Color.rgb(114, 114, 114))

        val padX = (INPUT_SIZE - newW) / 2f
        val padY = (INPUT_SIZE - newH) / 2f

        // Scale the image and draw it centered onto the padded background.
        val scaledBitmap = bitmap.scale(newW, newH)
        canvas.drawBitmap(scaledBitmap, padX, padY, Paint(Paint.FILTER_BITMAP_FLAG))

        return LetterboxInfo(letterboxBitmap, ratio, padX, padY)
    }

    /**
     * Converts a bitmap into a normalized FloatBuffer suitable for ONNX input.
     *
     * @param bitmap The pre-resized 128x128 bitmap to convert.
     * @return A [FloatBuffer] containing the normalized (0-1) pixel data in CHW (RGB) order.
     */
    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // Normalize to [0, 1] range and convert to CHW (BGR to RGB) format.
        for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
            buffer.put(i, Color.red(pixels[i]) / 255.0f)
            buffer.put(i + INPUT_SIZE * INPUT_SIZE, Color.green(pixels[i]) / 255.0f)
            buffer.put(i + 2 * INPUT_SIZE * INPUT_SIZE, Color.blue(pixels[i]) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Parses the raw output tensor into a list of detection objects.
     *
     * @param batchOutput The raw output float array for the processed batch.
     * @param info The letterbox metadata for coordinate transformation.
     * @return A list of [Detection] objects that passed the confidence threshold.
     */
    private fun parseOutput(batchOutput: Array<FloatArray>, info: LetterboxInfo): List<Detection> {
        // Output contains 4 bounding box coords plus scores for each class.
        val detections = mutableListOf<Detection>()

        // Total number of prediction anchors and number of detection classes.
        val numElements = batchOutput[BATCH_INDEX].size
        val numClasses = LABELS.size

        for (i in 0 until numElements) {
            var maxConf = -1.0f
            var maxIdx = -1

            // Identify the class with the highest confidence score.
            for (j in 0 until numClasses) {
                val conf = batchOutput[j + 4][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxIdx = j
                }
            }

            // Process detections that exceed the confidence threshold.
            if (maxConf > CONFIDENCE_THRESHOLD) {
                val cx = batchOutput[0][i]
                val cy = batchOutput[1][i]
                val w = batchOutput[2][i]
                val h = batchOutput[3][i]

                // Scale back to original coordinates and convert from center-based to top-left.
                val resX = (cx - w / 2f - info.padX) / info.ratio
                val resY = (cy - h / 2f - info.padY) / info.ratio
                val resW = w / info.ratio
                val resH = h / info.ratio

                // Ensure detections are within the physical bounds of the input bitmap.
                // Allowing a slight margin for edge detections.
                if (resX >= -2f && resY >= -2f && resX < info.bitmap.width && resY < info.bitmap.height) {
                    detections.add(Detection(maxOf(0f, resX), maxOf(0f, resY), resW, resH, LABELS[maxIdx], maxConf))
                }
            }
        }

        // Apply Non-Maximum Suppression to filter overlapping redundant boxes.
        val finalDetections = nms(detections)
        Log.d(TAG, "[DEBUG] parseOutput:: Detections after NMS: ${finalDetections.size} -> ${finalDetections.joinToString { "${it.label} (${it.x})" }}")
        return finalDetections
    }

    /**
     * Applies Non-Maximum Suppression (NMS) to eliminate duplicate detections of the same object.
     *
     * @param detections The raw list of candidate detections.
     * @return A filtered list of detections with reduced redundancy.
     */
    private fun nms(detections: MutableList<Detection>): List<Detection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        val ignored = BooleanArray(sortedDetections.size)

        for (i in sortedDetections.indices) {
            if (ignored[i]) continue

            val best = sortedDetections[i]
            result.add(best)

            // Compare the primary detection against all other candidates.
            for (j in i + 1 until sortedDetections.size) {
                if (ignored[j]) continue

                // Ignore detections that have significant overlap with the primary.
                if (iou(best, sortedDetections[j]) > IOU_THRESHOLD) {
                    ignored[j] = true
                }
            }
        }
        return result
    }

    /**
     * Calculates the Intersection over Union (IoU) of two detection bounding boxes.
     *
     * @param a The first detection candidate.
     * @param b The second detection candidate.
     * @return The IoU ratio ranging from 0.0 to 1.0.
     */
    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.w, b.x + b.w)
        val y2 = minOf(a.y + a.h, b.y + b.h)

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val union = a.w * a.h + b.w * b.h - intersection
        return if (union > 0) intersection / union else 0f
    }

    /** Closes the ONNX Runtime session and environment to free resources. */
    fun close() {
        ortSession?.close()
        ortEnv.close()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Runs object detection inference on the provided bitmap region.
     *
     * @param bitmap The input image crop to analyze.
     * @return A list of [Detection] objects scaled back to the original bitmap's coordinate system.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (ortSession == null) {
            Log.e(TAG, "[ERROR] detect:: OrtSession is null. Cannot run detection. Check if loadModel() failed.")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()

        // Pre-process image with letterboxing.
        val letterboxInfo = letterbox(bitmap)
        val floatBuffer = bitmapToFloatBuffer(letterboxInfo.bitmap)
        val inputName = ortSession?.inputNames?.iterator()?.next() ?: "images"

        try {
            // Create input tensor and run inference. The tensor is only freed by close(), so close it once the run is done.
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
            val results = inputTensor.use { ortSession?.run(Collections.singletonMap(inputName, it)) }

            results?.use {
                val outputValue = it.get(0).value

                // Standard YOLOv8 output format is float[1][attributes][anchors] (e.g., [1][15][336]).
                if (outputValue is Array<*> && outputValue[BATCH_INDEX] is Array<*>) {
                    val batchOutput = outputValue[BATCH_INDEX] as Array<FloatArray>

                    // Parse detections and scale coordinates back.
                    val detections = parseOutput(batchOutput, letterboxInfo)
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[DEBUG] detect:: Inference completed in ${duration}ms. Found ${detections.size} detections.")
                    return detections
                } else {
                    Log.e(TAG, "[ERROR] detect:: Unexpected output tensor structure: ${outputValue?.javaClass?.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] detect:: Error during inference: ${e.message}")
            e.printStackTrace()
        }

        return emptyList()
    }
}
