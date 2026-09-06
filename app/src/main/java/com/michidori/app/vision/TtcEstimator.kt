package com.michidori.app.vision

data class TtcObservation(
    val trackingId: Int?,
    val label: String,
    val objectConfidence: Float,
    val elapsedNs: Long,
    val distanceMeters: Float?,
    val depthValid: Boolean,
    val depthConfidence: Float,
)

data class TtcEstimate(
    val trackingId: Int?,
    val label: String,
    val elapsedNs: Long,
    val ttcSeconds: Float?,
    val closingSpeedMps: Float?,
    val valid: Boolean,
    val confidence: Float,
    val reason: String?,
)

/** Estimates time-to-collision only when successive valid distance samples are closing. */
class TtcEstimator(
    private val maxSampleGapNs: Long = DEFAULT_MAX_SAMPLE_GAP_NS,
) {
    private val previous = mutableMapOf<Int, TtcObservation>()

    fun reset() {
        previous.clear()
    }

    fun estimate(observation: TtcObservation): TtcEstimate {
        val previousObservation = observation.trackingId?.let(previous::get)
        if (observation.trackingId == null) {
            return invalid(observation, "tracking idが無い")
        }
        if (!observation.depthValid || observation.distanceMeters == null) {
            previous[observation.trackingId] = observation
            return invalid(observation, "depthがvalidでない")
        }
        val currentDistance = observation.distanceMeters
        if (currentDistance <= 0f || !currentDistance.isFinite()) {
            previous[observation.trackingId] = observation
            return invalid(observation, "distanceがvalidでない")
        }
        if (previousObservation == null) {
            previous[observation.trackingId] = observation
            return invalid(observation, "前回のdistanceが無い")
        }
        val deltaNs = observation.elapsedNs - previousObservation.elapsedNs
        val previousDistance = previousObservation.distanceMeters
        previous[observation.trackingId] = observation
        if (deltaNs <= 0L || deltaNs > maxSampleGapNs || previousDistance == null || previousDistance <= 0f) {
            return invalid(observation, "distanceの時間間隔が不連続")
        }
        val deltaSeconds = deltaNs / NANOS_PER_SECOND
        val closingSpeed = (previousDistance - currentDistance) / deltaSeconds
        if (closingSpeed <= MIN_CLOSING_SPEED_MPS || !closingSpeed.isFinite()) {
            return TtcEstimate(
                trackingId = observation.trackingId,
                label = observation.label,
                elapsedNs = observation.elapsedNs,
                ttcSeconds = null,
                closingSpeedMps = closingSpeed.takeIf(Float::isFinite),
                valid = false,
                confidence = 0f,
                reason = "対象が接近中でない",
            )
        }
        val ttcSeconds = currentDistance / closingSpeed
        val confidence = (observation.objectConfidence * observation.depthConfidence)
            .coerceIn(0f, 1f)
        if (!ttcSeconds.isFinite() || ttcSeconds <= 0f) {
            return invalid(observation, "TTCがvalidでない")
        }
        return TtcEstimate(
            trackingId = observation.trackingId,
            label = observation.label,
            elapsedNs = observation.elapsedNs,
            ttcSeconds = ttcSeconds,
            closingSpeedMps = closingSpeed,
            valid = confidence > 0f,
            confidence = confidence,
            reason = null,
        )
    }

    private fun invalid(observation: TtcObservation, reason: String): TtcEstimate = TtcEstimate(
        trackingId = observation.trackingId,
        label = observation.label,
        elapsedNs = observation.elapsedNs,
        ttcSeconds = null,
        closingSpeedMps = null,
        valid = false,
        confidence = 0f,
        reason = reason,
    )

    companion object {
        const val DEFAULT_MAX_SAMPLE_GAP_NS = 2_000_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MIN_CLOSING_SPEED_MPS = 0.1f
    }
}
