package com.michidori.app.vision

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Decodes raw EfficientDet-Lite0 head outputs (MediaPipe int8 export).
 * The model emits [1, 19206, 90] class logits and [1, 19206, 4] box deltas for
 * a 320x320 input; anchors follow the standard EfficientDet pyramid layout.
 */
object EfficientDetPostProcessor {
    const val IMAGE_SIZE = 320
    const val NUM_ANCHORS = 19206
    const val NUM_CLASSES = 90

    data class Detection(
        val classIndex: Int,
        val score: Float,
        val yMin: Float,
        val xMin: Float,
        val yMax: Float,
        val xMax: Float,
    )

    private const val MIN_LEVEL = 3
    private const val MAX_LEVEL = 7
    private const val NUM_SCALES = 3
    private const val ANCHOR_SCALE = 4.0f
    private val ASPECT_RATIOS = floatArrayOf(1.0f, 2.0f, 0.5f)

    private val anchors: FloatArray by lazy { buildAnchors() }

    fun decode(
        classLogits: FloatArray,
        boxDeltas: FloatArray,
        scoreThreshold: Float = 0.4f,
        iouThreshold: Float = 0.5f,
        maxResults: Int = 10,
    ): List<Detection> {
        require(classLogits.size >= NUM_ANCHORS * NUM_CLASSES) { "unexpected logits size" }
        require(boxDeltas.size >= NUM_ANCHORS * 4) { "unexpected box size" }

        val candidates = mutableListOf<Detection>()
        for (anchorIndex in 0 until NUM_ANCHORS) {
            var bestClass = -1
            var bestLogit = Float.NEGATIVE_INFINITY
            val base = anchorIndex * NUM_CLASSES
            for (clazz in 0 until NUM_CLASSES) {
                val logit = classLogits[base + clazz]
                if (logit > bestLogit) {
                    bestLogit = logit
                    bestClass = clazz
                }
            }
            val score = sigmoid(bestLogit)
            if (score < scoreThreshold) continue

            val anchorBase = anchorIndex * 4
            val anchorCy = anchors[anchorBase]
            val anchorCx = anchors[anchorBase + 1]
            val anchorH = anchors[anchorBase + 2]
            val anchorW = anchors[anchorBase + 3]
            val deltaBase = anchorIndex * 4
            val cy = boxDeltas[deltaBase] * anchorH + anchorCy
            val cx = boxDeltas[deltaBase + 1] * anchorW + anchorCx
            val h = exp(boxDeltas[deltaBase + 2].toDouble()).toFloat() * anchorH
            val w = exp(boxDeltas[deltaBase + 3].toDouble()).toFloat() * anchorW
            candidates += Detection(
                classIndex = bestClass,
                score = score,
                yMin = (cy - h / 2f) / IMAGE_SIZE,
                xMin = (cx - w / 2f) / IMAGE_SIZE,
                yMax = (cy + h / 2f) / IMAGE_SIZE,
                xMax = (cx + w / 2f) / IMAGE_SIZE,
            )
        }
        return nms(candidates, iouThreshold, maxResults)
    }

    private fun buildAnchors(): FloatArray {
        val result = FloatArray(NUM_ANCHORS * 4)
        var index = 0
        for (level in MIN_LEVEL..MAX_LEVEL) {
            val stride = 1 shl level
            val grid = ceil(IMAGE_SIZE.toFloat() / stride).toInt()
            for (cellY in 0 until grid) {
                for (cellX in 0 until grid) {
                    val cx = (cellX + 0.5f) * stride
                    val cy = (cellY + 0.5f) * stride
                    for (octave in 0 until NUM_SCALES) {
                        val octaveScale = 2.0.pow(octave.toDouble() / NUM_SCALES).toFloat()
                        for (aspect in ASPECT_RATIOS) {
                            val size = ANCHOR_SCALE * stride * octaveScale
                            result[index * 4] = cy
                            result[index * 4 + 1] = cx
                            result[index * 4 + 2] = size / sqrt(aspect)
                            result[index * 4 + 3] = size * sqrt(aspect)
                            index++
                        }
                    }
                }
            }
        }
        check(index == NUM_ANCHORS) { "anchor count mismatch: $index" }
        return result
    }

    private fun nms(candidates: List<Detection>, iouThreshold: Float, maxResults: Int): List<Detection> {
        val sorted = candidates.sortedByDescending { it.score }
        val kept = mutableListOf<Detection>()
        for (candidate in sorted) {
            if (kept.size >= maxResults) break
            if (kept.none { iou(it, candidate) > iouThreshold }) kept += candidate
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val intersectW = max(0f, min(a.xMax, b.xMax) - max(a.xMin, b.xMin))
        val intersectH = max(0f, min(a.yMax, b.yMax) - max(a.yMin, b.yMin))
        val intersection = intersectW * intersectH
        val union = areaOf(a) + areaOf(b) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun areaOf(d: Detection): Float =
        max(0f, d.xMax - d.xMin) * max(0f, d.yMax - d.yMin)

    private fun sigmoid(value: Float): Float = (1f / (1f + exp(-value.toDouble()))).toFloat()
}
