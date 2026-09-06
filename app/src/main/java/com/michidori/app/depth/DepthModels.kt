package com.michidori.app.depth

data class DepthSample(
    val elapsedNs: Long,
    val distanceMeters: Float?,
    val valid: Boolean,
    val confidence: Float,
    val source: String,
    val reason: String? = null,
)

enum class DepthStatus {
    CHECKING,
    READY,
    UNSUPPORTED,
    DEGRADED,
    ERROR,
}

data class DepthUiState(
    val status: DepthStatus = DepthStatus.CHECKING,
    val statusMessage: String = "Depthを確認中",
    val lastDistanceMeters: Float? = null,
    val lastValid: Boolean = false,
    val lastConfidence: Float = 0f,
    val lastElapsedNs: Long? = null,
)
