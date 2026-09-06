package com.michidori.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraLensSelectorTest {
    @Test
    fun choosesShortestMateriallyWiderPhysicalLens() {
        val selected = CameraLensSelector.chooseUltraWide(
            mainFocalLengthMm = 6.9f,
            candidates = listOf(
                PhysicalLensCandidate("2", 6.9f),
                PhysicalLensCandidate("4", 17.9f),
                PhysicalLensCandidate("3", 2.02f),
            ),
        )

        assertEquals(PhysicalLensCandidate("3", 2.02f), selected)
    }

    @Test
    fun doesNotCallMainLensUltraWide() {
        val selected = CameraLensSelector.chooseUltraWide(
            mainFocalLengthMm = 6.9f,
            candidates = listOf(PhysicalLensCandidate("2", 6.9f)),
        )

        assertNull(selected)
    }

    @Test
    fun rejectsInvalidCandidate() {
        val selected = CameraLensSelector.chooseUltraWide(
            mainFocalLengthMm = 6.9f,
            candidates = listOf(
                PhysicalLensCandidate("", 2f),
                PhysicalLensCandidate("bad", Float.NaN),
                PhysicalLensCandidate("3", 2.02f),
            ),
        )

        assertEquals("3", selected?.cameraId)
    }
}
