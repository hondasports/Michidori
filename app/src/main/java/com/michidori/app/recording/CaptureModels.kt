package com.michidori.app.recording

import androidx.camera.video.Quality

private const val DEFAULT_HEVC_MIME = "video/hevc"

enum class CaptureQualityProfile(
    val id: String,
    val displayName: String,
    val preferredQualityName: String,
    val targetFps: Int,
    val preferredMimeType: String,
) {
    HIGH(
        id = "high",
        displayName = "4K / 30fps / H.265",
        preferredQualityName = "UHD",
        targetFps = 30,
        preferredMimeType = DEFAULT_HEVC_MIME,
    ),
    BALANCED(
        id = "balanced",
        displayName = "1080p / 60fps / H.265",
        preferredQualityName = "FHD",
        targetFps = 60,
        preferredMimeType = DEFAULT_HEVC_MIME,
    ),
    ECO(
        id = "eco",
        displayName = "1080p / 30fps / H.265",
        preferredQualityName = "FHD",
        targetFps = 30,
        preferredMimeType = DEFAULT_HEVC_MIME,
    ),
    ;

    companion object {
        const val VIDEO_MIME_HEVC = "video/hevc"
        const val VIDEO_MIME_AVC = "video/avc"

        fun fromId(id: String?): CaptureQualityProfile =
            entries.firstOrNull { it.id == id } ?: HIGH
    }
}

enum class LensMode(
    val id: String,
    val displayName: String,
) {
    MAIN_1X("main_1x", "1x"),
    ULTRA_WIDE_0_5X("ultra_wide_0_5x", "0.5x"),
    ;

    companion object {
        fun fromId(id: String?): LensMode = entries.firstOrNull { it.id == id } ?: MAIN_1X
    }
}

data class CaptureSelection(
    val requestedQuality: CaptureQualityProfile = CaptureQualityProfile.HIGH,
    val appliedQuality: CaptureQualityProfile = CaptureQualityProfile.HIGH,
    val requestedLens: LensMode = LensMode.MAIN_1X,
    val appliedLens: LensMode = LensMode.MAIN_1X,
    val codecMimeType: String = CaptureQualityProfile.VIDEO_MIME_AVC,
    val fallbackReason: String? = null,
    val supportedQualities: Set<Quality> = emptySet(),
    val supportedMimeTypes: Set<String> = emptySet(),
    val actualCameraQuality: Quality? = null,
)

data class CaptureUiState(
    val selection: CaptureSelection = CaptureSelection(),
    val thermalStatus: Int = 0,
    val thermalLabel: String = "NONE",
    val batteryPercent: Float? = null,
    val isCharging: Boolean = false,
    val batteryTemperatureC: Float? = null,
    val lastQualityFallback: String? = null,
)
