package com.michidori.app.events

import kotlin.math.abs
import kotlin.math.sqrt

/** Motion events are candidates for review, not an ADAS or impact guarantee. */
enum class MotionEventType(val id: String) {
    HARD_BRAKE("HARD_BRAKE"),
    HARD_ACCELERATION("HARD_ACCELERATION"),
    SHARP_TURN("SHARP_TURN"),
    IMPACT("IMPACT"),
}

data class MotionSample(
    val elapsedNs: Long,
    val accelerationX: Float? = null,
    val accelerationY: Float? = null,
    val accelerationZ: Float? = null,
    val gyroscopeZ: Float? = null,
    val source: String = "imu",
    val isLinearAcceleration: Boolean = true,
)

data class MotionEventCandidate(
    val type: MotionEventType,
    val elapsedNs: Long,
    val severity: String,
    val confidence: Float,
    val source: String,
    val details: String,
)

class MotionEventDetector(
    private val cooldownNs: Long = DEFAULT_COOLDOWN_NS,
    private val hardBrakeThresholdMps2: Float = 5f,
    private val hardAccelerationThresholdMps2: Float = 4f,
    private val sharpTurnThresholdRadPerSec: Float = 1.3f,
    private val impactThresholdMps2: Float = 8f,
) {
    private val lastEmittedNs = mutableMapOf<MotionEventType, Long>()

    fun reset() {
        lastEmittedNs.clear()
    }

    fun onSample(sample: MotionSample): List<MotionEventCandidate> {
        val candidates = buildList {
            sample.accelerationX?.takeIf { sample.isLinearAcceleration }?.let { longitudinal ->
                if (longitudinal <= -hardBrakeThresholdMps2) {
                    add(
                        candidate(
                            type = MotionEventType.HARD_BRAKE,
                            sample = sample,
                            value = abs(longitudinal),
                            threshold = hardBrakeThresholdMps2,
                            details = "longitudinalMps2=${format(longitudinal)}",
                        ),
                    )
                }
                if (longitudinal >= hardAccelerationThresholdMps2) {
                    add(
                        candidate(
                            type = MotionEventType.HARD_ACCELERATION,
                            sample = sample,
                            value = longitudinal,
                            threshold = hardAccelerationThresholdMps2,
                            details = "longitudinalMps2=${format(longitudinal)}",
                        ),
                    )
                }
            }

            val magnitude = if (sample.isLinearAcceleration) {
                vectorMagnitude(sample.accelerationX, sample.accelerationY, sample.accelerationZ)
            } else {
                null
            }
            if (magnitude != null && magnitude >= impactThresholdMps2) {
                add(
                    candidate(
                        type = MotionEventType.IMPACT,
                        sample = sample,
                        value = magnitude,
                        threshold = impactThresholdMps2,
                        details = "accelerationMagnitudeMps2=${format(magnitude)}",
                    ),
                )
            }

            sample.gyroscopeZ?.let { yawRate ->
                if (abs(yawRate) >= sharpTurnThresholdRadPerSec) {
                    add(
                        candidate(
                            type = MotionEventType.SHARP_TURN,
                            sample = sample,
                            value = abs(yawRate),
                            threshold = sharpTurnThresholdRadPerSec,
                            details = "yawRateRadPerSec=${format(yawRate)}",
                        ),
                    )
                }
            }
        }
        return candidates.filter(::isAllowed).also { emitted ->
            emitted.forEach { lastEmittedNs[it.type] = it.elapsedNs }
        }
    }

    private fun isAllowed(candidate: MotionEventCandidate): Boolean {
        val previous = lastEmittedNs[candidate.type] ?: return true
        return candidate.elapsedNs - previous >= cooldownNs
    }

    private fun candidate(
        type: MotionEventType,
        sample: MotionSample,
        value: Float,
        threshold: Float,
        details: String,
    ): MotionEventCandidate = MotionEventCandidate(
        type = type,
        elapsedNs = sample.elapsedNs,
        severity = if (value >= threshold * 1.75f) "HIGH" else "MEDIUM",
        confidence = confidence(value, threshold),
        source = sample.source,
        details = details,
    )

    private fun confidence(value: Float, threshold: Float): Float =
        (0.55f + ((value - threshold) / threshold.coerceAtLeast(0.1f)) * 0.3f)
            .coerceIn(0.55f, 0.98f)

    private fun vectorMagnitude(x: Float?, y: Float?, z: Float?): Float? {
        if (x == null && y == null && z == null) return null
        return sqrt(
            (x ?: 0f) * (x ?: 0f) +
                (y ?: 0f) * (y ?: 0f) +
                (z ?: 0f) * (z ?: 0f),
        )
    }

    private fun format(value: Float): String = "%.3f".format(java.util.Locale.US, value)

    companion object {
        const val DEFAULT_COOLDOWN_NS = 2_000_000_000L
    }
}
