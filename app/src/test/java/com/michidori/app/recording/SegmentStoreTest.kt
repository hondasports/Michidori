package com.michidori.app.recording

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SegmentStoreTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("michidori-segments").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun retainsTenUnprotectedSegmentsAndDeletesTheOldest() {
        val store = SegmentStore(root, maxRetainedSegments = 3)

        (1..4).forEach { index ->
            val id = "segment-$index"
            store.newSegmentFile(id).writeText("video-$index")
            store.addFinalizedSegment(segment(id, index))
        }

        assertEquals(listOf("segment-2", "segment-3", "segment-4"), store.listSegments().map { it.id })
        assertFalse(store.newSegmentFile("segment-1").exists())
        assertTrue(store.newSegmentFile("segment-4").exists())
    }

    @Test
    fun protectedSegmentIsNotEvictedByRetention() {
        val store = SegmentStore(root, maxRetainedSegments = 3)
        (1..3).forEach { index ->
            val id = "segment-$index"
            store.newSegmentFile(id).writeText("video-$index")
            store.addFinalizedSegment(segment(id, index))
        }
        store.protect(setOf("segment-1"))

        val id = "segment-4"
        store.newSegmentFile(id).writeText("video-4")
        store.addFinalizedSegment(segment(id, 4))

        assertEquals(
            listOf("segment-1", "segment-2", "segment-3", "segment-4"),
            store.listSegments().map { it.id },
        )
        assertTrue(store.listSegments().first { it.id == "segment-1" }.isProtected)
        assertTrue(store.newSegmentFile("segment-1").exists())
    }

    @Test
    fun eventWindowProtectionSurvivesReload() {
        val store = SegmentStore(root, maxRetainedSegments = 2)
        (1..3).forEach { index ->
            val id = "segment-$index"
            store.newSegmentFile(id).writeText("video-$index")
            store.addFinalizedSegment(segment(id, index))
        }

        store.protectAround(elapsedNs = 1_500L, windowNs = 0L)
        val reloaded = SegmentStore(root, maxRetainedSegments = 2)

        assertTrue(reloaded.listSegments().first { it.id == "segment-2" }.isProtected)
        assertTrue(reloaded.newSegmentFile("segment-2").exists())
    }

    @Test
    fun segmentCaptureMetadataSurvivesReload() {
        val store = SegmentStore(root, maxRetainedSegments = 3)
        val id = "metadata-segment"
        store.newSegmentFile(id).writeText("video")
        store.addFinalizedSegment(
            RecordingSegment(
                id = id,
                fileName = "clip_$id.mp4",
                startElapsedNs = 0L,
                endElapsedNs = 1_000L,
                qualityProfile = CaptureQualityProfile.HIGH.id,
                actualQuality = "HD",
                codecMimeType = CaptureQualityProfile.VIDEO_MIME_HEVC,
                lensMode = LensMode.ULTRA_WIDE_0_5X.id,
            ),
        )

        val reloaded = SegmentStore(root).listSegments().single()

        assertEquals(CaptureQualityProfile.HIGH.id, reloaded.qualityProfile)
        assertEquals("HD", reloaded.actualQuality)
        assertEquals(CaptureQualityProfile.VIDEO_MIME_HEVC, reloaded.codecMimeType)
        assertEquals(LensMode.ULTRA_WIDE_0_5X.id, reloaded.lensMode)
    }

    private fun segment(id: String, index: Int): RecordingSegment = RecordingSegment(
        id = id,
        fileName = "clip_$id.mp4",
        startElapsedNs = (index - 1) * 1_000L,
        endElapsedNs = index * 1_000L - 1L,
    )
}
