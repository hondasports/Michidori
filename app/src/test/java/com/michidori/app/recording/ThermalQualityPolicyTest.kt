package com.michidori.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalQualityPolicyTest {
    @Test
    fun keepsRequestedQualityWhenThermalIsNormal() {
        val decision = ThermalQualityPolicy.choose(CaptureQualityProfile.HIGH, ThermalQualityPolicy.THERMAL_NONE)

        assertEquals(CaptureQualityProfile.HIGH, decision.applied)
        assertNull(decision.reason)
    }

    @Test
    fun lowersHighToBalancedAtModerateThermal() {
        val decision = ThermalQualityPolicy.choose(CaptureQualityProfile.HIGH, ThermalQualityPolicy.THERMAL_MODERATE)

        assertEquals(CaptureQualityProfile.BALANCED, decision.applied)
        assertNotNull(decision.reason)
    }

    @Test
    fun lowersAnyProfileToEcoAtSevereThermal() {
        val decision = ThermalQualityPolicy.choose(CaptureQualityProfile.BALANCED, ThermalQualityPolicy.THERMAL_SEVERE)

        assertEquals(CaptureQualityProfile.ECO, decision.applied)
    }
}
