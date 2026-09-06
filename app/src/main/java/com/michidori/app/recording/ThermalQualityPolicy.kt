package com.michidori.app.recording

object ThermalQualityPolicy {
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val THERMAL_EMERGENCY = 4
    const val THERMAL_SHUTDOWN = 5

    data class Decision(
        val requested: CaptureQualityProfile,
        val applied: CaptureQualityProfile,
        val reason: String? = null,
    )

    fun choose(requested: CaptureQualityProfile, thermalStatus: Int): Decision {
        val applied = when {
            thermalStatus >= THERMAL_SEVERE -> CaptureQualityProfile.ECO
            thermalStatus >= THERMAL_MODERATE && requested == CaptureQualityProfile.HIGH -> {
                CaptureQualityProfile.BALANCED
            }
            else -> requested
        }
        return Decision(
            requested = requested,
            applied = applied,
            reason = applied.takeUnless { it == requested }?.let {
                "thermal=$thermalStatus のため録画継続を優先して ${it.displayName} にfallback"
            },
        )
    }

    fun label(status: Int): String = when (status) {
        THERMAL_NONE -> "NONE"
        THERMAL_LIGHT -> "LIGHT"
        THERMAL_MODERATE -> "MODERATE"
        THERMAL_SEVERE -> "SEVERE"
        THERMAL_EMERGENCY -> "EMERGENCY"
        THERMAL_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
