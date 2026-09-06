package com.michidori.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.michidori.app.MainActivity
import com.michidori.app.R
import com.michidori.app.telemetry.TelemetryCollector
import com.michidori.app.telemetry.TelemetryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RecordingService : LifecycleService() {
    private val binder = LocalBinder()
    private val _uiState = MutableStateFlow(RecordingUiState())
    private lateinit var segmentStore: SegmentStore
    private lateinit var eventStore: DashcamEventStore
    private lateinit var telemetryCollector: TelemetryCollector

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var cameraInitializationJob: Job? = null
    private var rotationJob: Job? = null
    private var elapsedJob: Job? = null
    private var currentRecording: Recording? = null
    private var currentSegmentId: String? = null
    private var currentSegmentFile: File? = null
    private var currentSegmentStartNs: Long = 0L
    private var sessionStartElapsedMs: Long = 0L
    private var sessionActive = false
    private var stopRequested = false
    private var pendingStart = false
    private val pendingSaveElapsedNs = mutableListOf<Long>()

    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val recordingsRoot = File(filesDir, RECORDINGS_DIRECTORY)
        segmentStore = SegmentStore(recordingsRoot)
        eventStore = DashcamEventStore(recordingsRoot)
        telemetryCollector = TelemetryCollector(
            context = this,
            store = TelemetryStore(File(filesDir, TELEMETRY_DIRECTORY)),
        )
        lifecycleScope.launch {
            telemetryCollector.uiState.collect { telemetry ->
                _uiState.update {
                    it.copy(
                        gpsAvailable = telemetry.gpsAvailable,
                        speedKmh = telemetry.speedKmh,
                        telemetrySampleCount = telemetry.sampleCount,
                    )
                }
            }
        }
        refreshSegmentState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundWithNotification()
        initializeCamera()
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun attachPreview(previewView: PreviewView) {
        previewSurfaceProvider = previewView.surfaceProvider
        if (videoCapture == null) initializeCamera()
        previewUseCase?.setSurfaceProvider(previewSurfaceProvider)
    }

    fun detachPreview() {
        previewSurfaceProvider = null
        if (!sessionActive) releaseCamera()
    }

    fun startRecording() {
        if (sessionActive || _uiState.value.status == RecordingStatus.STARTING) return
        stopRequested = false
        if (videoCapture == null) {
            pendingStart = true
            _uiState.update { it.copy(status = RecordingStatus.STARTING, lastError = null) }
            initializeCamera()
            return
        }
        beginRecordingSession()
    }

    fun stopRecording() {
        if (!sessionActive) return
        pendingStart = false
        stopRequested = true
        rotationJob?.cancel()
        _uiState.update { it.copy(status = RecordingStatus.STOPPING) }
        currentRecording?.stop()
            ?: finishRecordingSession()
        updateNotification()
    }

    fun saveManualEvent() {
        if (!sessionActive) return
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        val event = DashcamEvent(
            id = UUID.randomUUID().toString(),
            type = MANUAL_SAVE_EVENT,
            elapsedNs = elapsedNs,
            epochMs = System.currentTimeMillis(),
        )
        eventStore.append(event)
        pendingSaveElapsedNs += elapsedNs
        segmentStore.protectAround(elapsedNs, SAVE_WINDOW_NS)
        _uiState.update { it.copy(lastEventType = event.type) }
        refreshSegmentState()
        updateNotification()
    }

    private fun beginRecordingSession() {
        if (videoCapture == null || sessionActive) return
        sessionActive = true
        stopRequested = false
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        pendingSaveElapsedNs.clear()
        telemetryCollector.start()
        _uiState.update { it.copy(status = RecordingStatus.RECORDING, elapsedMs = 0L, lastError = null) }
        elapsedJob?.cancel()
        elapsedJob = lifecycleScope.launch {
            while (isActive && sessionActive) {
                _uiState.update {
                    it.copy(elapsedMs = SystemClock.elapsedRealtime() - sessionStartElapsedMs)
                }
                delay(250L)
            }
        }
        openNextSegment()
    }

    private fun openNextSegment() {
        val capture = videoCapture ?: run {
            failRecording("カメラの録画パイプラインが準備できてへん")
            return
        }
        if (!sessionActive || stopRequested) {
            finishRecordingSession()
            return
        }

        val segmentId = UUID.randomUUID().toString()
        val file = segmentStore.newSegmentFile(segmentId)
        currentSegmentId = segmentId
        currentSegmentFile = file
        currentSegmentStartNs = SystemClock.elapsedRealtimeNanos()

        val outputOptions = FileOutputOptions.Builder(file).build()
        currentRecording = capture.output
            .prepareRecording(applicationContext, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                onVideoRecordEvent(segmentId, file, currentSegmentStartNs, event)
            }

        rotationJob?.cancel()
        rotationJob = lifecycleScope.launch {
            delay(SEGMENT_DURATION_MS)
            if (sessionActive && !stopRequested && currentSegmentId == segmentId) {
                currentRecording?.stop()
            }
        }
    }

    private fun onVideoRecordEvent(
        segmentId: String,
        file: File,
        startNs: Long,
        event: VideoRecordEvent,
    ) {
        when (event) {
            is VideoRecordEvent.Start -> {
                _uiState.update { it.copy(status = RecordingStatus.RECORDING) }
                updateNotification()
            }

            is VideoRecordEvent.Finalize -> {
                if (currentSegmentId != segmentId) return
                rotationJob?.cancel()
                currentRecording = null
                currentSegmentId = null
                currentSegmentFile = null

                if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    val cause = event.cause?.message?.takeIf { it.isNotBlank() }
                    failRecording(cause ?: "動画セグメントの確定に失敗したで")
                    return
                }
                if (!file.exists() || file.length() == 0L) {
                    failRecording("空の動画セグメントが生成されたで")
                    return
                }

                segmentStore.addFinalizedSegment(
                    RecordingSegment(
                        id = segmentId,
                        fileName = file.name,
                        startElapsedNs = startNs,
                        endElapsedNs = SystemClock.elapsedRealtimeNanos(),
                    ),
                )
                pendingSaveElapsedNs.forEach { saveElapsedNs ->
                    segmentStore.protectAround(saveElapsedNs, SAVE_WINDOW_NS)
                }
                refreshSegmentState()

                if (sessionActive && !stopRequested) {
                    openNextSegment()
                } else {
                    finishRecordingSession()
                }
            }
        }
    }

    private fun initializeCamera() {
        if (videoCapture != null || cameraInitializationJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            failRecording("カメラ権限が必要やで")
            return
        }
        cameraInitializationJob = lifecycleScope.launch {
            try {
                val provider = ProcessCameraProvider.getInstance(this@RecordingService).await()
                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                val preview = Preview.Builder().build()
                val capture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(
                    this@RecordingService,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                cameraProvider = provider
                previewUseCase = preview
                videoCapture = capture
                previewSurfaceProvider?.let(preview::setSurfaceProvider)
                _uiState.update { it.copy(cameraReady = true, lastError = null) }
                updateNotification()
                if (pendingStart) {
                    pendingStart = false
                    beginRecordingSession()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failRecording(failure.message ?: "カメラの初期化に失敗したで")
            }
        }
    }

    private fun finishRecordingSession() {
        sessionActive = false
        stopRequested = false
        pendingStart = false
        rotationJob?.cancel()
        elapsedJob?.cancel()
        telemetryCollector.stop()
        _uiState.update { it.copy(status = RecordingStatus.IDLE, elapsedMs = 0L) }
        refreshSegmentState()
        updateNotification()
    }

    private fun releaseCamera() {
        cameraInitializationJob?.cancel()
        cameraProvider?.unbindAll()
        cameraProvider = null
        previewUseCase = null
        videoCapture = null
        _uiState.update { it.copy(cameraReady = false) }
    }

    private fun failRecording(message: String) {
        sessionActive = false
        stopRequested = true
        rotationJob?.cancel()
        elapsedJob?.cancel()
        telemetryCollector.stop()
        _uiState.update { it.copy(status = RecordingStatus.ERROR, lastError = message) }
        updateNotification()
    }

    private fun refreshSegmentState() {
        val segments = if (::segmentStore.isInitialized) segmentStore.listSegments() else emptyList()
        _uiState.update {
            it.copy(
                segmentCount = segments.size,
                protectedSegmentCount = segments.count(RecordingSegment::isProtected),
            )
        }
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cameraType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            val locationType = if (hasLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(NOTIFICATION_ID, notification, cameraType or locationType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!::segmentStore.isInitialized) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = _uiState.value
        val text = when (state.status) {
            RecordingStatus.RECORDING, RecordingStatus.STOPPING ->
                getString(R.string.recording_notification_text)
            RecordingStatus.ERROR -> state.lastError ?: "録画エラー"
            else -> "カメラを準備しています"
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(state.isRecording)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        telemetryCollector.stop()
        currentRecording?.stop()
        cameraProvider?.unbindAll()
        cameraInitializationJob?.cancel()
        super.onDestroy()
    }

    inner class LocalBinder : android.os.Binder() {
        fun service(): RecordingService = this@RecordingService
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            },
            ContextCompat.getMainExecutor(this@RecordingService),
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "michidori_recording"
        private const val NOTIFICATION_ID = 1001
        private const val RECORDINGS_DIRECTORY = "recordings"
        private const val TELEMETRY_DIRECTORY = "telemetry"
        private const val SEGMENT_DURATION_MS = 60_000L
        private const val SAVE_WINDOW_NS = 60_000_000_000L
        private const val MANUAL_SAVE_EVENT = "MANUAL_SAVE"
    }
}
