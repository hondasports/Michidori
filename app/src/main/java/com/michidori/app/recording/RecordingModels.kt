package com.michidori.app.recording

enum class RecordingStatus {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    ERROR,
}

data class RecordingSegment(
    val id: String,
    val fileName: String,
    val startElapsedNs: Long,
    val endElapsedNs: Long,
    val isProtected: Boolean = false,
) {
    fun overlaps(startNs: Long, endNs: Long): Boolean =
        startElapsedNs <= endNs && endElapsedNs >= startNs
}

data class DashcamEvent(
    val id: String,
    val type: String,
    val elapsedNs: Long,
    val epochMs: Long,
    val severity: String = "NORMAL",
    val confidence: Float = 1f,
)

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val elapsedMs: Long = 0L,
    val segmentCount: Int = 0,
    val protectedSegmentCount: Int = 0,
    val cameraReady: Boolean = false,
    val gpsAvailable: Boolean = false,
    val speedKmh: Float? = null,
    val telemetrySampleCount: Long = 0L,
    val lastEventType: String? = null,
    val lastError: String? = null,
) {
    val isRecording: Boolean
        get() = status == RecordingStatus.RECORDING || status == RecordingStatus.STOPPING
}
