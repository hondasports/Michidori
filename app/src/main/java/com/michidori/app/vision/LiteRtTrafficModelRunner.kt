package com.michidori.app.vision

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.FileNotFoundException

enum class TrafficModelStatus {
    READY,
    MODEL_MISSING,
    ERROR,
}

data class TrafficPrediction(
    val label: String,
    val confidence: Float,
    val elapsedNs: Long,
)

/** Optional local model hook. A missing asset is a recorded degraded state, not a crash. */
class LiteRtTrafficModelRunner(
    context: Context,
    private val modelAssetName: String = DEFAULT_MODEL_ASSET,
) : AutoCloseable {
    private var environment: Environment? = null
    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    var activeAccelerator: Accelerator? = null
        private set
    var status: TrafficModelStatus = TrafficModelStatus.MODEL_MISSING
        private set
    var statusMessage: String = "LiteRT traffic modelが未搭載"
        private set

    init {
        runCatching {
            context.assets.open(modelAssetName).use { }
            createAcceleratedModel(context)
        }.onFailure { failure ->
            release()
            status = if (failure is FileNotFoundException) {
                TrafficModelStatus.MODEL_MISSING
            } else {
                TrafficModelStatus.ERROR
            }
            statusMessage = if (status == TrafficModelStatus.MODEL_MISSING) {
                "LiteRT modelが未搭載"
            } else {
                "LiteRT modelを読み込めへん"
            }
        }
    }

    /**
     * Runs a deliberately small contract: the bundled model receives [1, featureCount]
     * floats and returns [1, 4] scores. A custom model may be swapped in without changing
     * capture or storage; shape/inference failures remain degraded.
     */
    fun infer(features: FloatArray, elapsedNs: Long): List<TrafficPrediction> {
        val activeModel = model ?: return emptyList()
        return runCatching {
            inputBuffers.first().writeFloat(features)
            activeModel.run(inputBuffers, outputBuffers)
            outputBuffers.first().readFloat().mapIndexed { index, score ->
                TrafficPrediction(
                    label = TRAFFIC_LABELS.getOrElse(index) { "class_$index" },
                    confidence = score.coerceIn(0f, 1f),
                    elapsedNs = elapsedNs,
                )
            }.filter { it.confidence > MIN_CONFIDENCE }
        }.getOrElse {
            status = TrafficModelStatus.ERROR
            statusMessage = "LiteRT推論が失敗したためdegraded"
            emptyList()
        }
    }

    override fun close() {
        release()
    }

    private fun createAcceleratedModel(context: Context) {
        val env = Environment.create().also { environment = it }
        val available = env.getAvailableAccelerators()
        var lastFailure: Throwable? = null
        for (accelerator in ACCELERATOR_PREFERENCE) {
            if (accelerator != Accelerator.CPU && accelerator !in available) continue
            runCatching { compileFor(context, accelerator) }
                .onSuccess { candidate ->
                    model = candidate
                    activeAccelerator = accelerator
                    status = TrafficModelStatus.READY
                    statusMessage = "LiteRT traffic model ($accelerator)"
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

    companion object {
        const val DEFAULT_MODEL_ASSET = "traffic_model.tflite"
        private const val MIN_CONFIDENCE = 0.35f
        private val ACCELERATOR_PREFERENCE = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
        private val TRAFFIC_LABELS = listOf("vehicle", "pedestrian", "cyclist", "traffic_signal")
    }
}
