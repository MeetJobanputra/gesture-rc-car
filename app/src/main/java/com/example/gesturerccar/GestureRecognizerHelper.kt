package com.example.gesturerccar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

/**
 * Wraps MediaPipe's GestureRecognizer task in LIVE_STREAM mode: feed it camera
 * frames as they arrive, get gesture callbacks back asynchronously.
 *
 * Recognizable gestures out of the box: Closed_Fist, Open_Palm, Pointing_Up,
 * Thumb_Down, Thumb_Up, Victory, ILoveYou.
 */
class GestureRecognizerHelper(
    private val context: Context,
    private val listener: GestureResultListener,
    private val minHandDetectionConfidence: Float = 0.5f,
    private val minHandTrackingConfidence: Float = 0.5f,
    private val minHandPresenceConfidence: Float = 0.5f
) {
    interface GestureResultListener {
        fun onGestureResult(categoryName: String?, score: Float, timestampMs: Long)
        fun onError(error: String)
    }

    private var gestureRecognizer: GestureRecognizer? = null

    init {
        setupGestureRecognizer()
    }

    private fun setupGestureRecognizer() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .setDelegate(Delegate.CPU)
                .build()

            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(minHandDetectionConfidence)
                .setMinTrackingConfidence(minHandTrackingConfidence)
                .setMinHandPresenceConfidence(minHandPresenceConfidence)
                .setResultListener(::returnLiveStreamResult)
                .setErrorListener(::returnLiveStreamError)
                .build()

            gestureRecognizer = GestureRecognizer.createFromOptions(context, options)
        } catch (e: Exception) {
            listener.onError("Failed to initialize gesture recognizer: ${e.message}")
            Log.e(TAG, "MediaPipe init error", e)
        }
    }

    /** Call this from a CameraX ImageAnalysis analyzer for every frame. */
    fun recognizeLiveStream(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()

        try {
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            val mpImage: MPImage = BitmapImageBuilder(rotatedBitmap).build()
            gestureRecognizer?.recognizeAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun returnLiveStreamResult(result: GestureRecognizerResult, input: MPImage) {
        val gestures = result.gestures()
        if (gestures.isNotEmpty() && gestures[0].isNotEmpty()) {
            val topCategory = gestures[0][0]
            listener.onGestureResult(topCategory.categoryName(), topCategory.score(), result.timestampMs())
        } else {
            listener.onGestureResult(null, 0f, result.timestampMs())
        }
    }

    private fun returnLiveStreamError(error: RuntimeException) {
        listener.onError(error.message ?: "Unknown gesture recognizer error")
        Log.e(TAG, "Gesture recognizer runtime error", error)
    }

    fun close() {
        gestureRecognizer?.close()
        gestureRecognizer = null
    }

    companion object {
        private const val TAG = "GestureRecognizerHelper"
        private const val MODEL_ASSET_PATH = "gesture_recognizer.task"
    }
}
