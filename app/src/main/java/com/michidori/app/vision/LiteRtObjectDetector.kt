package com.michidori.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real on-device object detector running EfficientDet-Lite0 (int8) through
 * LiteRT CompiledModel, preferring NPU then GPU then CPU. Runs alongside the
 * ML Kit analyzer as an independent engine; inference is dispatched to a
 * dedicated worker so a slow or stuck NPU call never blocks the analyzer
 * thread or the camera pipeline. Failures degrade, never crash.
 */
class LiteRtObjectDetector(
    context: Context,
    private val modelAssetName: String = DEFAULT_MODEL_ASSET,
) : AutoCloseable {
    private var environment: Environment? = null
    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    private val inputBytes = ByteArray(INPUT_SIZE * INPUT_SIZE * RGB_CHANNELS)
    private val inputFloats = FloatArray(INPUT_SIZE * INPUT_SIZE * RGB_CHANNELS)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private var inputIsInt8: Boolean? = null

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "michidori-litert").apply { isDaemon = true }
    }
    private val inferenceInFlight = AtomicBoolean(false)
    @Volatile
    private var inferenceStartNs = 0L
    @Volatile
    private var hung = false

    var onFrameResult: ((VisionFrameResult) -> Unit)? = null

    var activeAccelerator: Accelerator? = null
        private set
    var status: VisionStatus = VisionStatus.UNAVAILABLE
        private set
    var statusMessage: String = "LiteRT検出モデルが未搭載"
        private set

    init {
        runCatching {
            context.assets.open(modelAssetName).use { }
            createAcceleratedModel(context)
        }.onFailure { failure ->
            release()
            status = if (failure is FileNotFoundException) VisionStatus.UNAVAILABLE else VisionStatus.ERROR
            statusMessage = if (status == VisionStatus.UNAVAILABLE) {
                "LiteRT検出モデルが未搭載"
            } else {
                "LiteRT検出モデルを読み込めへん"
            }
        }
    }

    /**
     * Copies the frame pixels synchronously on the caller thread (cheap) and
     * queues NPU inference on the worker. Frames are dropped while an
     * inference is still in flight, and a stuck inference marks the detector
     * degraded instead of piling up work.
     */
    fun submit(bitmap: Bitmap, elapsedNs: Long) {
        if (model == null || hung) return
        if (!inferenceInFlight.compareAndSet(false, true)) {
            if (inferenceStartNs > 0 &&
                SystemClock.elapsedRealtimeNanos() - inferenceStartNs > HANG_THRESHOLD_NS
            ) {
                hung = true
                status = VisionStatus.ERROR
                statusMessage = "LiteRT推論が応答しないためdegraded"
                Log.w(TAG, "LiteRT inference did not return within ${HANG_THRESHOLD_NS / 1_000_000}ms; disabled")
            }
            return
        }
        runCatching {
            preprocess(bitmap)
            val frameWidth = bitmap.width
            val frameHeight = bitmap.height
            worker.execute {
                try {
                    inferenceStartNs = SystemClock.elapsedRealtimeNanos()
                    val objects = runInference(frameWidth, frameHeight, elapsedNs)
                    val inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStartNs) / NANOS_PER_MILLI
                    Log.d(TAG, "inference done accelerator=$activeAccelerator objects=${objects.size} ms=$inferenceMs")
                    onFrameResult?.invoke(
                        VisionFrameResult(
                            elapsedNs = elapsedNs,
                            objects = objects,
                            inferenceMs = inferenceMs,
                            status = status,
                            engine = VisionFrameResult.ENGINE_LITERT,
                            statusMessage = statusMessage,
                        ),
                    )
                } finally {
                    inferenceInFlight.set(false)
                }
            }
        }.onFailure {
            inferenceInFlight.set(false)
            status = VisionStatus.ERROR
            statusMessage = "LiteRT入力画像を処理できへん"
        }
    }

    private fun runInference(frameWidth: Int, frameHeight: Int, elapsedNs: Long): List<DetectedObjectObservation> {
        val activeModel = model ?: return emptyList()
        return runCatching {
            writeInput()
            activeModel.run(inputBuffers, outputBuffers)
            decodeOutputs(frameWidth, frameHeight, elapsedNs)
        }.getOrElse { failure ->
            Log.w(TAG, "LiteRT inference failed", failure)
            status = VisionStatus.ERROR
            statusMessage = "LiteRT物体検出が失敗したためdegraded"
            emptyList()
        }
    }

    /** Stretch-downsamples an ARGB bitmap to 320x320 RGB bytes. */
    private fun preprocess(bitmap: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
        try {
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            var dst = 0
            for (i in pixels.indices) {
                val color = pixels[i]
                inputBytes[dst] = (color shr 16 and 0xFF).toByte()
                inputBytes[dst + 1] = (color shr 8 and 0xFF).toByte()
                inputBytes[dst + 2] = (color and 0xFF).toByte()
                dst += RGB_CHANNELS
            }
        } finally {
            scaled.recycle()
        }
    }

    private fun writeInput() {
        val buffer = inputBuffers.first()
        if (inputIsInt8 != false) {
            if (runCatching { buffer.writeInt8(inputBytes) }.isSuccess) {
                inputIsInt8 = true
                return
            }
            inputIsInt8 = false
        }
        inputBytes.forEachIndexed { index, byte -> inputFloats[index] = (byte.toInt() and 0xFF) / 255f }
        buffer.writeFloat(inputFloats)
    }

    private fun decodeOutputs(frameWidth: Int, frameHeight: Int, elapsedNs: Long): List<DetectedObjectObservation> {
        val scores = outputBuffers.first().readFloat()
        val boxes = outputBuffers[1].readFloat()
        return EfficientDetPostProcessor.decode(scores, boxes).map { detection ->
            DetectedObjectObservation(
                trackingId = null,
                label = labelFor(detection.classIndex),
                confidence = detection.score,
                left = detection.xMin * frameWidth,
                top = detection.yMin * frameHeight,
                right = detection.xMax * frameWidth,
                bottom = detection.yMax * frameHeight,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                elapsedNs = elapsedNs,
            )
        }
    }

    private fun labelFor(classIndex: Int): String = when (classIndex) {
        0 -> "pedestrian"          // COCO 1: person
        1, 3 -> "cyclist"          // COCO 2: bicycle, 4: motorcycle
        2, 5, 7 -> "vehicle"       // COCO 3: car, 6: bus, 8: truck
        9, 12 -> "traffic_signal"  // COCO 10: traffic light, 13: stop sign
        else -> "object_${classIndex + 1}"
    }

    private fun createAcceleratedModel(context: Context) {
        val env = Environment.create(BuiltinNpuAcceleratorProvider(context)).also { environment = it }
        val available = env.getAvailableAccelerators()
        var lastFailure: Throwable? = null
        for (accelerator in ACCELERATOR_PREFERENCE) {
            if (accelerator != Accelerator.CPU && accelerator !in available) continue
            runCatching { compileFor(context, accelerator) }
                .onSuccess { candidate ->
                    model = candidate
                    activeAccelerator = accelerator
                    status = VisionStatus.READY
                    statusMessage = "EfficientDet ($accelerator)"
                }
                .onFailure { lastFailure = it }
            if (model != null) return
        }
        throw lastFailure ?: IllegalStateException("No LiteRT accelerator available")
    }

    private fun compileFor(context: Context, accelerator: Accelerator): CompiledModel {
        val candidate = CompiledModel.create(
            context.assets,
            modelAssetName,
            CompiledModel.Options(accelerator),
        )
        try {
            inputBuffers = candidate.createInputBuffers()
            outputBuffers = candidate.createOutputBuffers()
            return candidate
        } catch (failure: Throwable) {
            runCatching { candidate.close() }
            throw failure
        }
    }

    private fun release() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers = emptyList()
        outputBuffers = emptyList()
        runCatching { model?.close() }
        model = null
        runCatching { environment?.close() }
        environment = null
    }

    override fun close() {
        worker.shutdown()
        release()
    }

    companion object {
        const val DEFAULT_MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val TAG = "LiteRtDetect"
        private const val INPUT_SIZE = 320
        private const val RGB_CHANNELS = 3
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val HANG_THRESHOLD_NS = 10_000_000_000L
        private val ACCELERATOR_PREFERENCE = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
    }
}
