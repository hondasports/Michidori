package com.michidori.app.recording

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Small, recoverable metadata store for the local recording ring.
 *
 * Video files remain the source of truth. The tab-separated index only describes
 * finalized segments, so an interrupted write cannot make a partial clip look valid.
 */
class SegmentStore(
    private val root: File,
    private val maxRetainedSegments: Int = DEFAULT_MAX_RETAINED_SEGMENTS,
) {
    private val metadataFile = File(root, METADATA_FILE_NAME)

    init {
        require(maxRetainedSegments > 0) { "maxRetainedSegments must be positive" }
        root.mkdirs()
    }

    @Synchronized
    fun listSegments(): List<RecordingSegment> = load().sortedBy { it.startElapsedNs }

    fun newSegmentFile(id: String): File {
        require(id.isNotBlank()) { "segment id must not be blank" }
        return File(root, "clip_$id.mp4")
    }

    /** Adds a finalized segment, then removes only the oldest unprotected clips. */
    @Synchronized
    fun addFinalizedSegment(segment: RecordingSegment): List<RecordingSegment> {
        require(segment.endElapsedNs >= segment.startElapsedNs) {
            "segment end must not precede start"
        }
        val merged = load()
            .filterNot { it.id == segment.id }
            .plus(segment)
            .sortedBy { it.startElapsedNs }
        return retain(merged)
    }

    /** Protects completed clips around an event and reapplies the retention policy. */
    @Synchronized
    fun protectAround(elapsedNs: Long, windowNs: Long): List<RecordingSegment> {
        require(windowNs >= 0) { "windowNs must not be negative" }
        val rangeStart = (elapsedNs - windowNs).coerceAtLeast(0L)
        val rangeEnd = if (Long.MAX_VALUE - elapsedNs < windowNs) {
            Long.MAX_VALUE
        } else {
            elapsedNs + windowNs
        }
        val protected = load().map { segment ->
            if (segment.overlaps(rangeStart, rangeEnd)) {
                segment.copy(isProtected = true)
            } else {
                segment
            }
        }
        return retain(protected)
    }

    @Synchronized
    fun protect(segmentIds: Set<String>): List<RecordingSegment> {
        if (segmentIds.isEmpty()) return listSegments()
        return retain(load().map { segment ->
            if (segment.id in segmentIds) segment.copy(isProtected = true) else segment
        })
    }

    private fun retain(segments: List<RecordingSegment>): List<RecordingSegment> {
        val sorted = segments.sortedBy { it.startElapsedNs }
        val unprotected = sorted.filterNot { it.isProtected }
        val removeCount = (unprotected.size - maxRetainedSegments).coerceAtLeast(0)
        val removeIds = unprotected.take(removeCount).map { it.id }.toSet()
        val kept = sorted.filterNot { it.id in removeIds }

        removeIds.forEach { id ->
            val segment = sorted.first { it.id == id }
            File(root, segment.fileName).delete()
        }
        persist(kept)
        return kept
    }

    private fun load(): List<RecordingSegment> {
        if (!metadataFile.exists()) return emptyList()
        return metadataFile.useLines(StandardCharsets.UTF_8) { lines ->
            lines.mapNotNull(::decode).toList()
        }
    }

    private fun persist(segments: List<RecordingSegment>) {
        val temporary = File(root, "$METADATA_FILE_NAME.tmp")
        temporary.writeText(
            segments.sortedBy { it.startElapsedNs }
                .joinToString(separator = "\n", postfix = if (segments.isEmpty()) "" else "\n") {
                    encode(it)
                },
            StandardCharsets.UTF_8,
        )
        try {
            Files.move(
                temporary.toPath(),
                metadataFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                metadataFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun encode(segment: RecordingSegment): String = listOf(
        segment.id,
        segment.fileName,
        segment.startElapsedNs.toString(),
        segment.endElapsedNs.toString(),
        segment.isProtected.toString(),
        segment.qualityProfile.orEmpty(),
        segment.codecMimeType.orEmpty(),
        segment.lensMode.orEmpty(),
        segment.actualQuality.orEmpty(),
    ).joinToString("\t")

    private fun decode(line: String): RecordingSegment? {
        val fields = line.split('\t')
        if (fields.size != 5 && fields.size != 8 && fields.size != 9) return null
        return runCatching {
            RecordingSegment(
                id = fields[0],
                fileName = fields[1],
                startElapsedNs = fields[2].toLong(),
                endElapsedNs = fields[3].toLong(),
                isProtected = fields[4].toBooleanStrict(),
                qualityProfile = fields.getOrNull(5)?.takeIf { it.isNotBlank() },
                codecMimeType = fields.getOrNull(6)?.takeIf { it.isNotBlank() },
                lensMode = fields.getOrNull(7)?.takeIf { it.isNotBlank() },
                actualQuality = fields.getOrNull(8)?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_MAX_RETAINED_SEGMENTS = 10
        private const val METADATA_FILE_NAME = "segments.tsv"
    }
}

class DashcamEventStore(private val root: File) {
    private val eventFile = File(root, "events.tsv")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun append(event: DashcamEvent) {
        eventFile.appendText(
            listOf(
                event.id.safeTsvField(),
                event.type.safeTsvField(),
                event.elapsedNs,
                event.epochMs,
                event.severity.safeTsvField(),
                event.confidence,
                event.source.safeTsvField(),
                event.details.orEmpty().safeTsvField(),
            ).joinToString("\t") + "\n",
            StandardCharsets.UTF_8,
        )
    }

    @Synchronized
    fun list(): List<DashcamEvent> {
        if (!eventFile.exists()) return emptyList()
        return eventFile.useLines(StandardCharsets.UTF_8) { lines ->
            lines.mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 6 && fields.size != 8) return@mapNotNull null
                runCatching {
                    DashcamEvent(
                        id = fields[0],
                        type = fields[1],
                        elapsedNs = fields[2].toLong(),
                        epochMs = fields[3].toLong(),
                        severity = fields[4],
                        confidence = fields[5].toFloat(),
                        source = fields.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "manual",
                        details = fields.getOrNull(7)?.takeIf { it.isNotBlank() },
                    )
                }.getOrNull()
            }.toList()
        }
    }

    private fun String.safeTsvField(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
}
