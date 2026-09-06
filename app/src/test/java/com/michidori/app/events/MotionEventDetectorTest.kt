package com.michidori.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEventDetectorTest {
    @Test
    fun detectsMotionCandidatesWithConfidenceAndEvidence() {
        val detector = MotionEventDetector()

        val candidates = detector.onSample(
            MotionSample(
                elapsedNs = 1_000_000_000L,
                accelerationX = -6f,
                gyroscopeZ = 1.6f,
                source = "imu_linear",
            ),
        )

        assertEquals(
            setOf(MotionEventType.HARD_BRAKE, MotionEventType.SHARP_TURN),
            candidates.map(MotionEventCandidate::type).toSet(),
        )
        assertTrue(candidates.all { it.confidence in 0f..1f })
        assertTrue(candidates.all { it.details.isNotBlank() })
    }

    @Test
    fun suppressesDuplicateEventsWithinCooldown() {
        val detector = MotionEventDetector()

        assertEquals(1, detector.onSample(MotionSample(0L, accelerationX = 5f)).size)
        assertTrue(detector.onSample(MotionSample(1_000_000_000L, accelerationX = 6f)).isEmpty())
        assertEquals(1, detector.onSample(MotionSample(2_100_000_000L, accelerationX = 6f)).size)
    }

    @Test
    fun detectsImpactFromAccelerationMagnitude() {
        val detector = MotionEventDetector()

        val event = detector.onSample(
            MotionSample(10L, accelerationX = 3f, accelerationY = 4f, accelerationZ = 7f),
        ).single()

        assertEquals(MotionEventType.IMPACT, event.type)
        assertEquals("MEDIUM", event.severity)
    }
}
