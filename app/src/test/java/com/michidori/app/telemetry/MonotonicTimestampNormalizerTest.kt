package com.michidori.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class MonotonicTimestampNormalizerTest {
    @Test
    fun normalizesEqualAndRegressingSamplesToStrictlyIncreasingValues() {
        val normalizer = MonotonicTimestampNormalizer()

        assertEquals(100L, normalizer.normalize(100L))
        assertEquals(101L, normalizer.normalize(100L))
        assertEquals(102L, normalizer.normalize(99L))
        assertEquals(200L, normalizer.normalize(200L))
    }
}
