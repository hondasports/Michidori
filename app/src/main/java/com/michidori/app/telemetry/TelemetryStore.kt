package com.michidori.app.telemetry

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Append-only local telemetry log. The video file and this log share elapsedNs. */
class TelemetryStore(private val root: File) {
    private val telemetryFile = File(root, "telemetry.ndjson")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun append(sample: TelemetrySample) {
        telemetryFile.appendText(sample.toJsonLine() + "\n", StandardCharsets.UTF_8)
    }

    @Synchronized
    fun sampleCount(): Long = if (!telemetryFile.exists()) 0L else telemetryFile.useLines { it.count().toLong() }

    @Synchronized
    fun readLines(): List<String> = if (!telemetryFile.exists()) emptyList() else {
        telemetryFile.readLines(StandardCharsets.UTF_8)
    }
}

private fun TelemetrySample.toJsonLine(): String {
    fun Double?.json(): String = this?.let { String.format(Locale.US, "%.9f", it) } ?: "null"
    fun Float?.json(): String = this?.let { String.format(Locale.US, "%.6f", it) } ?: "null"

    return buildString {
        append('{')
        append("\"elapsedNs\":").append(elapsedNs)
        append(",\"epochMs\":").append(epochMs)
        append(",\"latitude\":").append(latitude.json())
        append(",\"longitude\":").append(longitude.json())
        append(",\"speedMps\":").append(speedMps.json())
        append(",\"bearingDegrees\":").append(bearingDegrees.json())
        append(",\"locationAccuracyMeters\":").append(locationAccuracyMeters.json())
        append(",\"accelerometer\":[")
        append(accelerometerX.json()).append(',').append(accelerometerY.json()).append(',').append(accelerometerZ.json())
        append(']')
        append(",\"gyroscope\":[")
        append(gyroscopeX.json()).append(',').append(gyroscopeY.json()).append(',').append(gyroscopeZ.json())
        append(']')
        append(",\"rotation\":[")
        append(rotationX.json()).append(',').append(rotationY.json()).append(',').append(rotationZ.json())
        append(']')
        append('}')
    }
}
