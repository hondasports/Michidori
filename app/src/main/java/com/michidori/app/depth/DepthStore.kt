package com.michidori.app.depth

import java.io.File
import java.nio.charset.StandardCharsets

class DepthStore(private val root: File) {
    private val file = File(root, "depth.ndjson")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun append(sample: DepthSample) {
        val distance = sample.distanceMeters?.toString() ?: "null"
        val reason = sample.reason?.escapeJson() ?: ""
        file.appendText(
            "{\"elapsedNs\":${sample.elapsedNs},\"distanceMeters\":$distance," +
                "\"valid\":${sample.valid},\"confidence\":${sample.confidence}," +
                "\"source\":\"${sample.source.escapeJson()}\",\"reason\":\"$reason\"}\n",
            StandardCharsets.UTF_8,
        )
    }

    @Synchronized
    fun readLines(): List<String> = if (file.exists()) file.readLines(StandardCharsets.UTF_8) else emptyList()

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
}
