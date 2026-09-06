package com.michidori.app.ai

import java.io.File
import java.nio.charset.StandardCharsets

class EventAnalysisStore(private val root: File) {
    private val file = File(root, "event_analysis.tsv")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun append(explanation: EventExplanation) {
        file.appendText(
            listOf(
                explanation.eventId,
                explanation.source.name,
                explanation.statusMessage,
                explanation.text,
            ).joinToString("\t") { it.safeTsvField() } + "\n",
            StandardCharsets.UTF_8,
        )
    }

    @Synchronized
    fun latestByEventId(): Map<String, EventExplanation> {
        if (!file.exists()) return emptyMap()
        return file.useLines(StandardCharsets.UTF_8) { lines ->
            lines.mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 4) return@mapNotNull null
                val source = runCatching { ExplanationSource.valueOf(fields[1]) }.getOrNull()
                    ?: return@mapNotNull null
                fields[0] to EventExplanation(fields[0], fields[3], source, fields[2])
            }.toMap()
        }
    }

    private fun String.safeTsvField(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
}
