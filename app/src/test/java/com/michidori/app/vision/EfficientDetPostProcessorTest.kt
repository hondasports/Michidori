package com.michidori.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class EfficientDetPostProcessorTest {

    @Test
    fun returnsEmptyWhenAllScoresAreBelowThreshold() {
        val logits = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * EfficientDetPostProcessor.NUM_CLASSES) { -10f }
        val boxes = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * 4)

        val detections = EfficientDetPostProcessor.decode(logits, boxes)

        assertTrue(detections.isEmpty())
    }

    @Test
    fun decodesIdentityDeltaAtAnchorCenter() {
        val logits = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * EfficientDetPostProcessor.NUM_CLASSES) { -10f }
        val boxes = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * 4)
        // Anchor 0: level3, cell (0,0), center = (4,4) px, size = 4*8*1 = 32px square.
        logits[0 * EfficientDetPostProcessor.NUM_CLASSES + 1] = logit(0.99f)

        val detections = EfficientDetPostProcessor.decode(logits, boxes, scoreThreshold = 0.5f)

        assertEquals(1, detections.size)
        val det = detections[0]
        assertEquals(1, det.classIndex)
        // Anchor center (4,4) px / 320 = 0.0125 normalized.
        assertEquals(0.0125f, (det.xMin + det.xMax) / 2f, 1e-4f)
        assertEquals(0.0125f, (det.yMin + det.yMax) / 2f, 1e-4f)
        // Anchor size 32px / 320 = 0.1 normalized.
        assertEquals(0.1f, det.xMax - det.xMin, 1e-4f)
        assertEquals(0.1f, det.yMax - det.yMin, 1e-4f)
    }

    @Test
    fun nmsSuppressesOverlappingDuplicates() {
        val logits = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * EfficientDetPostProcessor.NUM_CLASSES) { -10f }
        val boxes = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * 4)
        // Anchors 0 and 1 sit at the same cell center; give both strong scores.
        logits[0 * EfficientDetPostProcessor.NUM_CLASSES + 0] = logit(0.9f)
        logits[1 * EfficientDetPostProcessor.NUM_CLASSES + 0] = logit(0.8f)

        val detections = EfficientDetPostProcessor.decode(logits, boxes, scoreThreshold = 0.5f)

        assertEquals(1, detections.size)
        assertEquals(0.9f, detections[0].score, 1e-4f)
    }

    @Test
    fun picksArgmaxClassPerAnchor() {
        val logits = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * EfficientDetPostProcessor.NUM_CLASSES) { -10f }
        val boxes = FloatArray(EfficientDetPostProcessor.NUM_ANCHORS * 4)
        logits[0 * EfficientDetPostProcessor.NUM_CLASSES + 2] = logit(0.95f)
        logits[0 * EfficientDetPostProcessor.NUM_CLASSES + 5] = logit(0.6f)

        val detections = EfficientDetPostProcessor.decode(logits, boxes, scoreThreshold = 0.5f)

        assertEquals(1, detections.size)
        assertEquals(2, detections[0].classIndex)
    }

    private fun logit(probability: Float): Float =
        ln((probability / (1f - probability)).toDouble()).toFloat()
}
