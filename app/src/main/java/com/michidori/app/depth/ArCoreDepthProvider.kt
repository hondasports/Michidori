package com.michidori.app.depth

import android.content.Context
import android.os.SystemClock
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.exceptions.MissingGlContextException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.Session
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Best-effort ARCore depth probe. Camera/recording failure always wins over depth. */
class ArCoreDepthProvider(
    context: Context,
    private val onSample: (DepthSample) -> Unit,
    private val onStatus: (DepthStatus, String) -> Unit,
    /**
     * A live ARCore session needs a GL owner and a camera shared with ARCore.
     * CameraX owns the camera in the current recorder, so this remains false
     * until a SharedCamera/GL driver is supplied.
     */
    private val allowLiveSession: Boolean = false,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "michidori-depth").apply { isDaemon = true }
    }
    @Volatile
    private var running = false
    private var session: Session? = null
    private var lastStatusMessage: String? = null

    fun start() {
        if (running) return
        lastStatusMessage = null
        running = true
        emitStatus(DepthStatus.CHECKING, "ARCore Depthを確認中")
        executor.execute(::runDepthLoop)
    }

    private fun runDepthLoop() {
        val availability = runCatching { ArCoreApk.getInstance().checkAvailability(appContext) }.getOrNull()
        if (availability == null || !availability.isSupported) {
            emitStatus(DepthStatus.UNSUPPORTED, "この端末ではARCoreが利用できへん")
            emitInvalidSample("ARCore unsupported")
            running = false
            return
        }

        // Session.update() performs off-screen GL work and a normal Session
        // takes exclusive camera ownership. Starting it beside CameraX would
        // either fail or, on some ARCore builds, abort during native cleanup.
        // Keep this provider explicitly degraded until the recorder supplies
        // a real SharedCamera + GL lifecycle.
        if (!allowLiveSession) {
            emitStatus(DepthStatus.DEGRADED, "CameraX録画とARCoreのshared camera/GL接続がなくdegraded")
            emitInvalidSample("CameraX owns camera; SharedCamera/GL driver unavailable")
            running = false
            return
        }

        val activeSession = runCatching { Session(appContext) }.getOrElse {
            emitStatus(DepthStatus.ERROR, "ARCore Sessionを作れへん")
            emitInvalidSample("ARCore Session creation failed")
            running = false
            return
        }
        session = activeSession
        var missingGlContext = false
        try {
            if (!activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                emitStatus(DepthStatus.UNSUPPORTED, "Depth APIが未対応")
                return
            }
            activeSession.configure(
                Config(activeSession).apply {
                    depthMode = Config.DepthMode.AUTOMATIC
                },
            )
            activeSession.resume()
            emitStatus(DepthStatus.READY, "ARCore Depth（中央距離）")
            while (running) {
                captureOne(activeSession)
                Thread.sleep(SAMPLE_INTERVAL_MS)
            }
        } catch (_: MissingGlContextException) {
            missingGlContext = true
            emitStatus(DepthStatus.DEGRADED, "ARCoreにGL contextがなくdegraded")
            emitInvalidSample("ARCore GL context unavailable")
        } catch (failure: Exception) {
            if (running) emitStatus(DepthStatus.DEGRADED, "ARCore cameraが録画と競合したためdegraded")
            emitInvalidSample(failure.message ?: "ARCore depth session failed")
        } finally {
            // A MissingGlContextException can make native close unsafe. The
            // worker owns the session, and stop() only flips the flag so that
            // cleanup cannot race this block from the service main thread.
            if (!missingGlContext) {
                runCatching { activeSession.pause() }
                runCatching { activeSession.close() }
            }
            session = null
            if (running) emitStatus(DepthStatus.DEGRADED, "ARCore Depthを停止したで")
        }
        running = false
    }

    private fun captureOne(activeSession: Session) {
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        try {
            val frame = activeSession.update()
            frame.acquireDepthImage16Bits().use { image ->
                val plane = image.planes.firstOrNull()
                val distanceMm = plane?.let { readCenterMillimeters(image.width, image.height, it) }
                val valid = distanceMm != null && distanceMm in MIN_DISTANCE_MM..MAX_DISTANCE_MM
                val distanceMeters = distanceMm?.div(MILLIMETERS_PER_METER)
                onSample(
                    DepthSample(
                        elapsedNs = elapsedNs,
                        distanceMeters = distanceMeters,
                        valid = valid,
                        confidence = if (valid) DEPTH_CONFIDENCE else 0f,
                        source = "arcore_center_depth",
                        reason = if (valid) null else "depth pixel invalid",
                    ),
                )
            }
        } catch (_: NotYetAvailableException) {
            onSample(
                DepthSample(
                    elapsedNs = elapsedNs,
                    distanceMeters = null,
                    valid = false,
                    confidence = 0f,
                    source = "arcore_center_depth",
                    reason = "depth frame未取得",
                ),
            )
        } catch (failure: MissingGlContextException) {
            throw failure
        } catch (_: IllegalStateException) {
            emitStatus(DepthStatus.DEGRADED, "ARCore trackingが安定してへん")
        }
    }

    private fun emitInvalidSample(reason: String) {
        onSample(
            DepthSample(
                elapsedNs = SystemClock.elapsedRealtimeNanos(),
                distanceMeters = null,
                valid = false,
                confidence = 0f,
                source = "arcore_center_depth",
                reason = reason,
            ),
        )
    }

    private fun readCenterMillimeters(
        width: Int,
        height: Int,
        plane: android.media.Image.Plane,
    ): Int {
        val x = width / 2
        val y = height / 2
        val offset = y * plane.rowStride + x * plane.pixelStride
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (offset < 0 || offset + 1 >= buffer.limit()) return 0
        return buffer.getShort(offset).toInt() and 0xFFFF
    }

    private fun emitStatus(status: DepthStatus, message: String) {
        if (lastStatusMessage == message) return
        lastStatusMessage = message
        onStatus(status, message)
    }

    fun stop() {
        running = false
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 200L
        private const val MILLIMETERS_PER_METER = 1_000f
        private const val MIN_DISTANCE_MM = 100
        private const val MAX_DISTANCE_MM = 100_000
        private const val DEPTH_CONFIDENCE = 0.5f
    }
}
