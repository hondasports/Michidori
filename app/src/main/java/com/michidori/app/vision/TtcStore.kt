package com.michidori.app.vision

import java.io.File
import java.nio.charset.StandardCharsets

class TtcStore(private val root: File) {
    private val file = File(root, "ttc.ndjson")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun append(estimate: TtcEstimate) {
        val ttc = estimate.ttcSeconds?.toString() ?: "null"
        val closingSpeed = estimate.closingSpeedMps?.toString() ?: "null"
        val reason = estimate.reason?.escapeJson() ?: ""
        file.appendText(
            "{\"elapsedNs\":${estimate.elapsedNs},\"trackingId\":${estimate.trackingId ?: "null"}," +
                "\"label\":\"${estimate.label.escapeJson()}\",\"ttcSeconds\":$ttc," +
                "\"closingSpeedMps\":$closingSpeed,\"valid\":${estimate.valid}," +
                "\"confidence\":${estimate.confidence},\"reason\":\"$reason\"}\n",
            StandardCharsets.UTF_8,
        )
    }

    @Synchronized
    fun readLines(): List<String> = if (file.exists()) file.readLines(StandardCharsets.UTF_8) else emptyList()

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
}
