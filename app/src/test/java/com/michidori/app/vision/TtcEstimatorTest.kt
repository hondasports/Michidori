package com.michidori.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtcEstimatorTest {
    @Test
    fun emitsClosingSpeedAndTtcOnlyAfterTwoValidDistances() {
        val estimator = TtcEstimator()
        val first = estimator.estimate(observation(10L, 20f))
        val second = estimator.estimate(observation(1_000_000_010L, 19f))

        assertFalse(first.valid)
        assertTrue(second.valid)
        assertEquals(1f, second.closingSpeedMps!!, 0.001f)
        assertEquals(19f, second.ttcSeconds!!, 0.001f)
        assertNotNull(second.confidence)
    }

    @Test
    fun reportsInvalidWhenDepthIsUnavailableWithoutInventingValues() {
        val estimate = TtcEstimator().estimate(
            observation(10L, null, depthValid = false),
        )

        assertFalse(estimate.valid)
        assertTrue(estimate.ttcSeconds == null)
        assertTrue(estimate.closingSpeedMps == null)
        assertEquals("depthがvalidでない", estimate.reason)
    }

    @Test
    fun rejectsStaleOrNonClosingSamples() {
        val estimator = TtcEstimator()
        estimator.estimate(observation(0L, 10f))
        val stale = estimator.estimate(observation(3_000_000_000L, 9f))
        val receding = estimator.estimate(observation(3_100_000_000L, 10f))

        assertFalse(stale.valid)
        assertEquals("distanceの時間間隔が不連続", stale.reason)
        assertFalse(receding.valid)
        assertEquals("対象が接近中でない", receding.reason)
    }

    private fun observation(
        elapsedNs: Long,
        distanceMeters: Float?,
        depthValid: Boolean = true,
    ) = TtcObservation(
        trackingId = 7,
        label = "vehicle",
        objectConfidence = 0.9f,
        elapsedNs = elapsedNs,
        distanceMeters = distanceMeters,
        depthValid = depthValid,
        depthConfidence = if (depthValid) 0.8f else 0f,
    )
}
