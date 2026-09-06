package com.michidori.app.telemetry

data class TelemetrySample(
    val elapsedNs: Long,
    val epochMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val locationAccuracyMeters: Float? = null,
    val accelerometerX: Float? = null,
    val accelerometerY: Float? = null,
    val accelerometerZ: Float? = null,
    val gyroscopeX: Float? = null,
    val gyroscopeY: Float? = null,
    val gyroscopeZ: Float? = null,
    val rotationX: Float? = null,
    val rotationY: Float? = null,
    val rotationZ: Float? = null,
)

data class TelemetryUiState(
    val gpsAvailable: Boolean = false,
    val speedKmh: Float? = null,
    val sensorsAvailable: Boolean = false,
    val sampleCount: Long = 0L,
)

class MonotonicTimestampNormalizer(initial: Long? = null) {
    private var lastTimestampNs: Long? = initial

    @Synchronized
    fun normalize(candidateNs: Long): Long {
        val previous = lastTimestampNs
        val normalized = if (previous == null) {
            candidateNs
        } else {
            maxOf(candidateNs, if (previous == Long.MAX_VALUE) Long.MAX_VALUE else previous + 1L)
        }
        lastTimestampNs = normalized
        return normalized
    }
}
