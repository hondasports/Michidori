package com.michidori.app.ai

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EventAnalysisStoreTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("michidori-ai").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun keepsLatestForegroundExplanationWithSource() {
        val store = EventAnalysisStore(root)
        store.append(
            EventExplanation(
                eventId = "event-1",
                text = "候補メモ\nwith newline",
                source = ExplanationSource.LOCAL_FALLBACK,
                statusMessage = "unavailable",
            ),
        )

        val explanation = store.latestByEventId().getValue("event-1")

        assertEquals("候補メモ with newline", explanation.text)
        assertEquals(ExplanationSource.LOCAL_FALLBACK, explanation.source)
    }
}
