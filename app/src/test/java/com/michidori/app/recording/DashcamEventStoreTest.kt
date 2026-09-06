package com.michidori.app.recording

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashcamEventStoreTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("michidori-events").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun persistsMotionEvidenceAndKeepsLegacyRowsReadable() {
        val store = DashcamEventStore(root)
        store.append(
            DashcamEvent(
                id = "motion-1",
                type = "HARD_BRAKE",
                elapsedNs = 123L,
                epochMs = 456L,
                severity = "HIGH",
                confidence = 0.8f,
                source = "imu_linear",
                details = "longitudinalMps2=-6.000\tunsafe",
            ),
        )
        java.io.File(root, "events.tsv").appendText("manual-1\tMANUAL_SAVE\t9\t10\tNORMAL\t1.0\n")

        val events = store.list()

        assertEquals(2, events.size)
        assertEquals("imu_linear", events[0].source)
        assertEquals("longitudinalMps2=-6.000 unsafe", events[0].details)
        assertEquals("manual", events[1].source)
        assertTrue(events[1].details == null)
    }
}
