package com.michidori.app.recording

import com.michidori.app.depth.DepthUiState
import com.michidori.app.ai.EventExplanation
import com.michidori.app.vision.VisionUiState

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
    val qualityProfile: String? = null,
    val codecMimeType: String? = null,
    val lensMode: String? = null,
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
    val source: String = "manual",
    val details: String? = null,
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
    val capture: CaptureUiState = CaptureUiState(),
    val vision: VisionUiState = VisionUiState(),
    val depth: DepthUiState = DepthUiState(),
    val trafficModelStatus: String = "MODEL_MISSING",
    val trafficModelMessage: String = "LiteRT modelが未搭載",
    val segments: List<RecordingSegment> = emptyList(),
    val events: List<DashcamEvent> = emptyList(),
    val eventExplanations: Map<String, EventExplanation> = emptyMap(),
    val explainingEventId: String? = null,
) {
    val isRecording: Boolean
        get() = status == RecordingStatus.RECORDING || status == RecordingStatus.STOPPING
}
