package com.michidori.app.telemetry

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryStoreTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("michidori-telemetry").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun appendsSharedTimestampAndKeepsUnavailableSourcesNull() {
        val store = TelemetryStore(root)
        store.append(TelemetrySample(elapsedNs = 100L, epochMs = 1_700_000_000_000L))
        store.append(
            TelemetrySample(
                elapsedNs = 200L,
                epochMs = 1_700_000_000_100L,
                latitude = 35.0,
                longitude = 135.0,
                speedMps = 10f,
            ),
        )

        val lines = store.readLines()
        assertEquals(2, store.sampleCount())
        assertTrue(lines[0].contains("\"elapsedNs\":100"))
        assertTrue(lines[0].contains("\"latitude\":null"))
        assertTrue(lines[1].contains("\"speedMps\":10.000000"))
    }
}
