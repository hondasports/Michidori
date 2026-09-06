package com.michidori.app.vision

data class DetectedObjectObservation(
    val trackingId: Int?,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val elapsedNs: Long,
)

data class VisionFrameResult(
    val elapsedNs: Long,
    val objects: List<DetectedObjectObservation>,
    val inferenceMs: Long,
    val status: VisionStatus = VisionStatus.READY,
)

enum class VisionStatus {
    READY,
    UNAVAILABLE,
    DEGRADED,
    ERROR,
}

data class VisionUiState(
    val status: VisionStatus = VisionStatus.UNAVAILABLE,
    val statusMessage: String = "AI未接続",
    val objectCount: Int = 0,
    val lastInferenceMs: Long? = null,
    val lastElapsedNs: Long? = null,
)
