package com.michidori.app.recording

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ExportedSegment(
    val video: File,
    val metadata: File,
)

/** Creates an explicit, shareable copy plus a metadata sidecar; the canonical clip is untouched. */
class SegmentExportStore(private val recordingsRoot: File) {
    private val exportRoot = File(recordingsRoot, "exports")

    @Synchronized
    fun export(segment: RecordingSegment, events: List<DashcamEvent>): ExportedSegment? {
        val source = File(recordingsRoot, segment.fileName).canonicalFile
        val root = recordingsRoot.canonicalFile
        if (!source.exists() || !source.path.startsWith(root.path + File.separator)) return null
        exportRoot.mkdirs()
        val outputVideo = File(exportRoot, "${segment.id}.mp4")
        val outputMetadata = File(exportRoot, "${segment.id}.json")
        Files.copy(source.toPath(), outputVideo.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val relatedEvents = events.filter { segment.overlaps(it.elapsedNs, it.elapsedNs) }
        outputMetadata.writeText(
            "{" +
                "\"segmentId\":\"${segment.id.escapeJson()}\"," +
                "\"videoFile\":\"${outputVideo.name.escapeJson()}\"," +
                "\"startElapsedNs\":${segment.startElapsedNs}," +
                "\"endElapsedNs\":${segment.endElapsedNs}," +
                "\"qualityProfile\":\"${segment.qualityProfile.orEmpty().escapeJson()}\"," +
                "\"actualQuality\":\"${segment.actualQuality.orEmpty().escapeJson()}\"," +
                "\"codecMimeType\":\"${segment.codecMimeType.orEmpty().escapeJson()}\"," +
                "\"lensMode\":\"${segment.lensMode.orEmpty().escapeJson()}\"," +
                "\"overlay\":\"metadata sidecar（元動画には焼き込まへん）\"," +
                "\"events\":[${relatedEvents.joinToString(",") { it.toJson() }}]" +
                "}\n",
            StandardCharsets.UTF_8,
        )
        return ExportedSegment(outputVideo, outputMetadata)
    }

    private fun DashcamEvent.toJson(): String =
        "{\"id\":\"${id.escapeJson()}\",\"type\":\"${type.escapeJson()}\",\"elapsedNs\":$elapsedNs," +
            "\"severity\":\"${severity.escapeJson()}\",\"confidence\":$confidence," +
            "\"source\":\"${source.escapeJson()}\",\"details\":\"${details.orEmpty().escapeJson()}\"}"

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
}
