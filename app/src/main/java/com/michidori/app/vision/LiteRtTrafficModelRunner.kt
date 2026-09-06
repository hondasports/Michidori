package com.michidori.app.vision

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

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
    private var interpreter: Interpreter? = null
    var status: TrafficModelStatus = TrafficModelStatus.MODEL_MISSING
        private set
    var statusMessage: String = "LiteRT traffic modelが未搭載"
        private set

    init {
        runCatching {
            interpreter = Interpreter(loadModelFile(context, modelAssetName))
            status = TrafficModelStatus.READY
            statusMessage = "LiteRT traffic model"
        }.onFailure { failure ->
            interpreter = null
            status = if (failure is java.io.FileNotFoundException) {
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
        val activeInterpreter = interpreter ?: return emptyList()
        return runCatching {
            val scores = Array(1) { FloatArray(4) }
            activeInterpreter.run(arrayOf(features), scores)
            scores[0].mapIndexed { index, score ->
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
        interpreter?.close()
        interpreter = null
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val descriptor = context.assets.openFd(assetName)
        FileInputStream(descriptor.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength,
            )
        }
    }

    companion object {
        const val DEFAULT_MODEL_ASSET = "traffic_model.tflite"
        private const val MIN_CONFIDENCE = 0.35f
        private val TRAFFIC_LABELS = listOf("vehicle", "pedestrian", "cyclist", "traffic_signal")
    }
}
