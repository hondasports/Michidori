package com.michidori.app.vision

import java.io.File
import java.nio.charset.StandardCharsets

class TrafficStore(private val root: File) {
    private val file = File(root, "traffic.ndjson")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun append(elapsedNs: Long, status: TrafficModelStatus, statusMessage: String, predictions: List<TrafficPrediction>) {
        val values = predictions.joinToString(",") {
            "{\"label\":\"${it.label.escapeJson()}\",\"confidence\":${it.confidence},\"elapsedNs\":${it.elapsedNs}}"
        }
        file.appendText(
            "{\"elapsedNs\":$elapsedNs,\"status\":\"$status\",\"message\":\"${statusMessage.escapeJson()}\",\"predictions\":[$values]}\n",
            StandardCharsets.UTF_8,
        )
    }

    @Synchronized
    fun readLines(): List<String> = if (file.exists()) file.readLines(StandardCharsets.UTF_8) else emptyList()

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
}
