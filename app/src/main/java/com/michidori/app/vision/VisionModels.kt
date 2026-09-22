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
    val engine: String = ENGINE_MLKIT,
    val statusMessage: String? = null,
) {
    companion object {
        const val ENGINE_MLKIT = "mlkit"
        const val ENGINE_LITERT = "litert"
    }
}

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
    val litertStatusMessage: String? = null,
    val litertObjectCount: Int = 0,
    val objects: List<DetectedObjectObservation> = emptyList(),
    val litertObjects: List<DetectedObjectObservation> = emptyList(),
)
