package com.michidori.app.recording

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SegmentExportStoreTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("michidori-export").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun exportsCopyAndMetadataWithoutChangingCanonicalVideo() {
        val source = java.io.File(root, "clip-1.mp4").apply { writeText("canonical-video") }
        val segment = RecordingSegment(
            id = "segment-1",
            fileName = source.name,
            startElapsedNs = 0L,
            endElapsedNs = 10L,
            qualityProfile = "high",
            codecMimeType = "video/hevc",
            lensMode = "main_1x",
        )
        val exported = SegmentExportStore(root).export(
            segment,
            listOf(
                DashcamEvent("event-1", "HARD_BRAKE", 5L, 6L, source = "imu_linear", details = "g=-6"),
            ),
        )!!

        assertEquals("canonical-video", source.readText())
        assertEquals("canonical-video", exported.video.readText())
        assertTrue(exported.metadata.readText().contains("metadata sidecar"))
        assertTrue(exported.metadata.readText().contains("HARD_BRAKE"))
    }
}
