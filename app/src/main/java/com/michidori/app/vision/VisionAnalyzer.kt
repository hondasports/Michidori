package com.michidori.app.vision

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CameraX analyzer. It never owns the recording output and closes every frame. */
@SuppressLint("UnsafeOptInUsageError")
class VisionAnalyzer(
    private val onResult: (VisionFrameResult) -> Unit,
    private val onStatus: (VisionStatus, String) -> Unit,
    private val minIntervalNs: Long = DEFAULT_MIN_INTERVAL_NS,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "michidori-vision").apply { isDaemon = true }
    }
    private val detector = runCatching {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build(),
        )
    }.getOrNull()
    private var lastAcceptedElapsedNs = Long.MIN_VALUE

    @Volatile
    private var enabled = false

    val initiallyAvailable: Boolean
        get() = detector != null

    val initialStatusMessage: String
        get() = if (detector != null) "ML Kit object tracking" else "ML Kit object detectorを初期化できへん"

    val analysisExecutor: ExecutorService
        get() = executor

    init {
        if (detector == null) onStatus(VisionStatus.UNAVAILABLE, "ML Kit object detectorを初期化できへん")
    }

    override fun analyze(image: ImageProxy) {
        if (!enabled) {
            image.close()
            return
        }
        val mediaImage = image.image
        val elapsedNs = image.imageInfo.timestamp.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
        if (mediaImage == null || detector == null) {
            image.close()
            return
        }
        if (elapsedNs - lastAcceptedElapsedNs < minIntervalNs) {
            image.close()
            return
        }
        lastAcceptedElapsedNs = elapsedNs
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val input = runCatching {
            InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        }.getOrElse {
            image.close()
            onStatus(VisionStatus.DEGRADED, "ML Kit入力画像を作れへん")
            return
        }
        detector.process(input)
            .addOnSuccessListener { detectedObjects ->
                val observations = detectedObjects.map { detectedObject ->
                    val label = detectedObject.labels.maxByOrNull { it.confidence }
                    val bounds = detectedObject.boundingBox
                    DetectedObjectObservation(
                        trackingId = detectedObject.trackingId,
                        label = label?.text ?: "unknown",
                        confidence = label?.confidence ?: 0f,
                        left = bounds.left.toFloat(),
                        top = bounds.top.toFloat(),
                        right = bounds.right.toFloat(),
                        bottom = bounds.bottom.toFloat(),
                        frameWidth = image.width,
                        frameHeight = image.height,
                        elapsedNs = elapsedNs,
                    )
                }
                onResult(
                    VisionFrameResult(
                        elapsedNs = elapsedNs,
                        objects = observations,
                        inferenceMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / NANOS_PER_MILLI,
                    ),
                )
                onStatus(VisionStatus.READY, "ML Kit object tracking")
            }
            .addOnFailureListener {
                onStatus(VisionStatus.DEGRADED, "ML Kit推論が一時的に失敗")
            }
            .addOnCompleteListener {
                image.close()
            }
    }

    override fun close() {
        detector?.close()
        executor.shutdownNow()
    }

    fun reportExternalDegraded(message: String) {
        onStatus(VisionStatus.DEGRADED, message)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) lastAcceptedElapsedNs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_NS = 250_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
