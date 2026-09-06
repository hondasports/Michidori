package com.michidori.app.vision

import java.io.File
import java.nio.charset.StandardCharsets

class VisionStore(private val root: File) {
    private val file = File(root, "vision.ndjson")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun append(frame: VisionFrameResult) {
        val objects = frame.objects.joinToString(",") { objectValue ->
            "{" +
                "\"trackingId\":${objectValue.trackingId ?: "null"}," +
                "\"label\":\"${objectValue.label.escapeJson()}\"," +
                "\"confidence\":${"%.5f".format(java.util.Locale.US, objectValue.confidence)}," +
                "\"left\":${objectValue.left},\"top\":${objectValue.top}," +
                "\"right\":${objectValue.right},\"bottom\":${objectValue.bottom}," +
                "\"frameWidth\":${objectValue.frameWidth},\"frameHeight\":${objectValue.frameHeight}" +
                "}"
        }
        val line = "{" +
            "\"elapsedNs\":${frame.elapsedNs}," +
            "\"inferenceMs\":${frame.inferenceMs}," +
            "\"status\":\"${frame.status}\"," +
            "\"objects\":[$objects]" +
            "}\n"
        file.appendText(line, StandardCharsets.UTF_8)
    }

    @Synchronized
    fun readLines(): List<String> = if (file.exists()) file.readLines(StandardCharsets.UTF_8) else emptyList()

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
}
