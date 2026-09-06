package com.michidori.app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
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
import com.michidori.app.ai.EventAnalysisStore
import com.michidori.app.ai.GeminiEventExplainer
import com.michidori.app.depth.ArCoreDepthProvider
import com.michidori.app.depth.DepthSample
import com.michidori.app.depth.DepthStatus
import com.michidori.app.events.MotionEventCandidate
import com.michidori.app.telemetry.TelemetryCollector
import com.michidori.app.telemetry.TelemetryStore
import com.michidori.app.vision.DetectedObjectObservation
import com.michidori.app.vision.LiteRtTrafficModelRunner
import com.michidori.app.vision.TtcEstimator
import com.michidori.app.vision.TtcEstimate
import com.michidori.app.vision.TtcObservation
import com.michidori.app.vision.TrafficStore
import com.michidori.app.vision.VisionAnalyzer
import com.michidori.app.vision.VisionFrameResult
import com.michidori.app.vision.VisionStatus
import com.michidori.app.vision.VisionStore
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
import kotlin.math.abs
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("UnsafeOptInUsageError")
@Suppress("DEPRECATION")
class RecordingService : LifecycleService() {
    private val binder = LocalBinder()
    private val _uiState = MutableStateFlow(RecordingUiState())
    private lateinit var segmentStore: SegmentStore
    private lateinit var eventStore: DashcamEventStore
    private lateinit var exportStore: SegmentExportStore
    private lateinit var eventAnalysisStore: EventAnalysisStore
    private lateinit var geminiEventExplainer: GeminiEventExplainer
    private lateinit var telemetryCollector: TelemetryCollector
    private lateinit var captureSettingsStore: CaptureSettingsStore
    private lateinit var visionStore: VisionStore
    private lateinit var depthStore: com.michidori.app.depth.DepthStore
    private lateinit var ttcStore: com.michidori.app.vision.TtcStore
    private lateinit var trafficStore: TrafficStore
    private lateinit var visionAnalyzer: VisionAnalyzer
    private lateinit var trafficModelRunner: LiteRtTrafficModelRunner
    private lateinit var depthProvider: ArCoreDepthProvider
    private val ttcEstimator = TtcEstimator()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var cameraInitializationJob: Job? = null
    private var rotationJob: Job? = null
    private var elapsedJob: Job? = null
    private var currentRecording: Recording? = null
    private var currentSegmentId: String? = null
    private var currentSegmentFile: File? = null
    private var currentSegmentSelection: CaptureSelection? = null
    private var sessionStartElapsedMs: Long = 0L
    private var sessionActive = false
    private var stopRequested = false
    private var pendingStart = false
    private var continueSessionAfterRebind = false
    private var rebindForNextSegment = false
    private var latestDepthSample: DepthSample? = null
    private var lastFrontApproachElapsedNs: Long? = null
    private var lastTrafficStatusElapsedNs: Long? = null
    private val pendingSaveElapsedNs = mutableListOf<Long>()

    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val recordingsRoot = File(filesDir, RECORDINGS_DIRECTORY)
        segmentStore = SegmentStore(recordingsRoot)
        eventStore = DashcamEventStore(recordingsRoot)
        exportStore = SegmentExportStore(recordingsRoot)
        eventAnalysisStore = EventAnalysisStore(recordingsRoot)
        geminiEventExplainer = GeminiEventExplainer()
        captureSettingsStore = CaptureSettingsStore(this)
        visionStore = VisionStore(recordingsRoot)
        depthStore = com.michidori.app.depth.DepthStore(recordingsRoot)
        ttcStore = com.michidori.app.vision.TtcStore(recordingsRoot)
        trafficStore = TrafficStore(recordingsRoot)
        visionAnalyzer = VisionAnalyzer(
            onResult = ::onVisionFrame,
            onStatus = ::onVisionStatus,
        )
        trafficModelRunner = LiteRtTrafficModelRunner(this)
        depthProvider = ArCoreDepthProvider(
            context = this,
            onSample = ::onDepthSample,
            onStatus = ::onDepthStatus,
            // CameraX owns the recorder camera; a SharedCamera + GL driver is
            // required before a live ARCore session can be enabled safely.
            allowLiveSession = false,
        )
        _uiState.update {
            it.copy(
                vision = it.vision.copy(
                    status = if (visionAnalyzer.initiallyAvailable) VisionStatus.READY else VisionStatus.UNAVAILABLE,
                    statusMessage = visionAnalyzer.initialStatusMessage,
                ),
                trafficModelStatus = trafficModelRunner.status.name,
                trafficModelMessage = trafficModelRunner.statusMessage,
            )
        }
        telemetryCollector = TelemetryCollector(
            context = this,
            store = TelemetryStore(File(filesDir, TELEMETRY_DIRECTORY)),
            onMotionEvent = ::onMotionEvent,
        )
        lifecycleScope.launch {
            telemetryCollector.uiState.collect { telemetry ->
                _uiState.update {
                    it.copy(
                        gpsAvailable = telemetry.gpsAvailable,
                        speedKmh = telemetry.speedKmh,
                        telemetrySampleCount = telemetry.sampleCount,
                        capture = it.capture.copy(
                            thermalStatus = telemetry.thermalStatus,
                            thermalLabel = telemetry.thermalLabel,
                            batteryPercent = telemetry.batteryPercent,
                            isCharging = telemetry.isCharging,
                            batteryTemperatureC = telemetry.batteryTemperatureC,
                        ),
                    )
                }
                applyThermalPolicyIfNeeded(telemetry.thermalStatus)
            }
        }
        _uiState.update {
            it.copy(
                capture = it.capture.copy(
                    selection = it.capture.selection.copy(
                        requestedQuality = captureSettingsStore.qualityProfile(),
                        requestedLens = captureSettingsStore.lensMode(),
                    ),
                ),
            )
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
        val thermalDecision = ThermalQualityPolicy.choose(
            captureSettingsStore.qualityProfile(),
            telemetryCollector.uiState.value.thermalStatus,
        )
        if (videoCapture == null) {
            pendingStart = true
            _uiState.update { it.copy(status = RecordingStatus.STARTING, lastError = null) }
            initializeCamera()
            return
        }
        if (_uiState.value.capture.selection.appliedQuality != thermalDecision.applied) {
            pendingStart = true
            _uiState.update { it.copy(status = RecordingStatus.STARTING, lastError = null) }
            rebindCamera()
            return
        }
        beginRecordingSession()
    }

    fun setQualityProfile(profile: CaptureQualityProfile) {
        captureSettingsStore.setQualityProfile(profile)
        _uiState.update {
            it.copy(
                capture = it.capture.copy(
                    selection = it.capture.selection.copy(requestedQuality = profile),
                ),
            )
        }
        if (!sessionActive) {
            rebindCamera()
        } else {
            applyThermalPolicyIfNeeded(telemetryCollector.uiState.value.thermalStatus)
        }
    }

    fun setLensMode(mode: LensMode) {
        captureSettingsStore.setLensMode(mode)
        _uiState.update {
            it.copy(
                capture = it.capture.copy(
                    selection = it.capture.selection.copy(requestedLens = mode),
                ),
            )
        }
        if (!sessionActive) {
            rebindCamera()
        } else if (currentRecording != null && !stopRequested && !rebindForNextSegment) {
            rebindForNextSegment = true
            rotationJob?.cancel()
            currentRecording?.stop()
        }
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

    /** Called only by the foreground Activity's event-details action. */
    fun explainEvent(eventId: String) {
        if (_uiState.value.explainingEventId != null) return
        val event = eventStore.list().firstOrNull { it.id == eventId } ?: return
        _uiState.update { it.copy(explainingEventId = eventId) }
        lifecycleScope.launch {
            val explanation = geminiEventExplainer.explain(event)
            eventAnalysisStore.append(explanation)
            _uiState.update {
                it.copy(
                    explainingEventId = null,
                    eventExplanations = it.eventExplanations + (eventId to explanation),
                )
            }
        }
    }

    /** Export is an explicit foreground action; the original clip is never rewritten. */
    fun exportSegment(segmentId: String): ExportedSegment? {
        val segment = segmentStore.listSegments().firstOrNull { it.id == segmentId } ?: return null
        return exportStore.export(segment, eventStore.list())
    }

    private fun onMotionEvent(candidate: MotionEventCandidate) {
        if (!sessionActive) return
        val event = DashcamEvent(
            id = UUID.randomUUID().toString(),
            type = candidate.type.id,
            elapsedNs = candidate.elapsedNs,
            epochMs = System.currentTimeMillis(),
            severity = candidate.severity,
            confidence = candidate.confidence,
            source = candidate.source,
            details = candidate.details,
        )
        eventStore.append(event)
        pendingSaveElapsedNs += candidate.elapsedNs
        segmentStore.protectAround(candidate.elapsedNs, SAVE_WINDOW_NS)
        _uiState.update { it.copy(lastEventType = event.type) }
        refreshSegmentState()
    }

    private fun onVisionStatus(status: VisionStatus, message: String) {
        _uiState.update {
            it.copy(
                vision = it.vision.copy(
                    status = status,
                    statusMessage = message,
                ),
            )
        }
    }

    private fun onDepthStatus(status: DepthStatus, message: String) {
        _uiState.update {
            it.copy(
                depth = it.depth.copy(
                    status = status,
                    statusMessage = message,
                ),
            )
        }
    }

    private fun onDepthSample(sample: DepthSample) {
        if (!sessionActive) return
        latestDepthSample = sample
        depthStore.append(sample)
        _uiState.update {
            it.copy(
                depth = it.depth.copy(
                    lastDistanceMeters = sample.distanceMeters,
                    lastValid = sample.valid,
                    lastConfidence = sample.confidence,
                    lastElapsedNs = sample.elapsedNs,
                ),
            )
        }
    }

    private fun onVisionFrame(frame: VisionFrameResult) {
        if (!sessionActive) return
        visionStore.append(frame)
        _uiState.update {
            it.copy(
                vision = it.vision.copy(
                    objectCount = frame.objects.size,
                    lastInferenceMs = frame.inferenceMs,
                    lastElapsedNs = frame.elapsedNs,
                ),
            )
        }

        val features = frame.objects.flatMap { objectValue ->
            listOf(
                objectValue.confidence,
                normalizedCenterX(objectValue),
                normalizedCenterY(objectValue),
                normalizedArea(objectValue),
            )
        }.toFloatArray()
        if (features.isNotEmpty()) {
            val predictions = trafficModelRunner.infer(features, frame.elapsedNs)
            _uiState.update {
                it.copy(
                    trafficModelStatus = trafficModelRunner.status.name,
                    trafficModelMessage = trafficModelRunner.statusMessage,
                )
            }
            val previousStatusNs = lastTrafficStatusElapsedNs
            if (predictions.isNotEmpty() ||
                previousStatusNs == null ||
                frame.elapsedNs - previousStatusNs >= TRAFFIC_STATUS_LOG_INTERVAL_NS
            ) {
                trafficStore.append(
                    elapsedNs = frame.elapsedNs,
                    status = trafficModelRunner.status,
                    statusMessage = trafficModelRunner.statusMessage,
                    predictions = predictions,
                )
                lastTrafficStatusElapsedNs = frame.elapsedNs
            }
        }

        frame.objects.forEach { objectValue ->
            val depth = latestDepthSample?.takeIf { sample ->
                sample.valid &&
                    abs(frame.elapsedNs - sample.elapsedNs) <= DEPTH_ASSOCIATION_WINDOW_NS &&
                    isNearFrameCenter(objectValue)
            }
            val estimate = ttcEstimator.estimate(
                TtcObservation(
                    trackingId = objectValue.trackingId,
                    label = objectValue.label,
                    objectConfidence = objectValue.confidence,
                    elapsedNs = frame.elapsedNs,
                    distanceMeters = depth?.distanceMeters,
                    depthValid = depth?.valid == true,
                    depthConfidence = depth?.confidence ?: 0f,
                ),
            )
            ttcStore.append(estimate)
            maybeRecordFrontApproach(estimate)
        }
    }

    private fun maybeRecordFrontApproach(estimate: TtcEstimate) {
        if (!estimate.valid || estimate.ttcSeconds == null || estimate.ttcSeconds > FRONT_APPROACH_TTC_SECONDS) return
        val previous = lastFrontApproachElapsedNs
        if (previous != null && estimate.elapsedNs - previous < FRONT_APPROACH_COOLDOWN_NS) return
        lastFrontApproachElapsedNs = estimate.elapsedNs
        onMotionEvent(
            MotionEventCandidate(
                type = com.michidori.app.events.MotionEventType.FRONT_APPROACH,
                elapsedNs = estimate.elapsedNs,
                severity = "MEDIUM",
                confidence = estimate.confidence,
                source = "vision_depth_ttc",
                details = "FRONT_APPROACH ttcSeconds=${estimate.ttcSeconds} closingSpeedMps=${estimate.closingSpeedMps}",
            ),
        )
    }

    private fun normalizedCenterX(objectValue: DetectedObjectObservation): Float =
        (((objectValue.left + objectValue.right) / 2f) / objectValue.frameWidth.coerceAtLeast(1)).coerceIn(0f, 1f)

    private fun normalizedCenterY(objectValue: DetectedObjectObservation): Float =
        (((objectValue.top + objectValue.bottom) / 2f) / objectValue.frameHeight.coerceAtLeast(1)).coerceIn(0f, 1f)

    private fun normalizedArea(objectValue: DetectedObjectObservation): Float =
        (((objectValue.right - objectValue.left).coerceAtLeast(0f) *
            (objectValue.bottom - objectValue.top).coerceAtLeast(0f)) /
            (objectValue.frameWidth.coerceAtLeast(1) * objectValue.frameHeight.coerceAtLeast(1))).coerceIn(0f, 1f)

    private fun isNearFrameCenter(objectValue: DetectedObjectObservation): Boolean =
        abs(normalizedCenterX(objectValue) - 0.5f) <= 0.35f &&
            abs(normalizedCenterY(objectValue) - 0.5f) <= 0.4f

    private fun beginRecordingSession() {
        if (videoCapture == null || sessionActive) return
        sessionActive = true
        stopRequested = false
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        pendingSaveElapsedNs.clear()
        latestDepthSample = null
        lastFrontApproachElapsedNs = null
        lastTrafficStatusElapsedNs = null
        ttcEstimator.reset()
        telemetryCollector.start()
        visionAnalyzer.setEnabled(true)
        depthProvider.start()
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
        val segmentStartNs = SystemClock.elapsedRealtimeNanos()
        currentSegmentId = segmentId
        currentSegmentFile = file
        currentSegmentSelection = _uiState.value.capture.selection

        val outputOptions = FileOutputOptions.Builder(file).build()
        currentRecording = capture.output
            .prepareRecording(applicationContext, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                onVideoRecordEvent(segmentId, file, segmentStartNs, event)
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
                val segmentSelection = currentSegmentSelection
                currentSegmentSelection = null

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
                        qualityProfile = segmentSelection?.appliedQuality?.id,
                        actualQuality = segmentSelection?.actualCameraQuality?.toString(),
                        codecMimeType = segmentSelection?.codecMimeType,
                        lensMode = segmentSelection?.appliedLens?.id,
                    ),
                )
                pendingSaveElapsedNs.forEach { saveElapsedNs ->
                    segmentStore.protectAround(saveElapsedNs, SAVE_WINDOW_NS)
                }
                refreshSegmentState()

                if (sessionActive && !stopRequested) {
                    if (rebindForNextSegment) {
                        rebindForNextSegment = false
                        rebindCamera(continueSession = true)
                    } else {
                        openNextSegment()
                    }
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
                val requestedQuality = captureSettingsStore.qualityProfile()
                val requestedLens = captureSettingsStore.lensMode()
                val thermalDecision = ThermalQualityPolicy.choose(
                    requestedQuality,
                    telemetryCollector.uiState.value.thermalStatus,
                )
                val supportedMimeTypes = runCatching {
                    Recorder.getSupportedVideoMimeTypes().toSet()
                }.getOrDefault(emptySet())
                val binding = bindWithFallbacks(
                    provider = provider,
                    requestedQuality = requestedQuality,
                    initialQuality = thermalDecision.applied,
                    requestedLens = requestedLens,
                    supportedMimeTypes = supportedMimeTypes,
                    thermalReason = thermalDecision.reason,
                )
                cameraProvider = provider
                camera = binding.camera
                previewUseCase = binding.preview
                videoCapture = binding.capture
                imageAnalysis = binding.analysis
                previewSurfaceProvider?.let(binding.preview::setSurfaceProvider)
                _uiState.update {
                    it.copy(
                        cameraReady = true,
                        lastError = null,
                        capture = it.capture.copy(
                            selection = binding.selection,
                            lastQualityFallback = binding.selection.fallbackReason,
                        ),
                    )
                }
                updateNotification()
                if (pendingStart) {
                    pendingStart = false
                    beginRecordingSession()
                } else if (continueSessionAfterRebind && sessionActive && !stopRequested) {
                    continueSessionAfterRebind = false
                    openNextSegment()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failRecording(failure.message ?: "カメラの初期化に失敗したで")
            }
        }
    }

    private fun bindWithFallbacks(
        provider: ProcessCameraProvider,
        requestedQuality: CaptureQualityProfile,
        initialQuality: CaptureQualityProfile,
        requestedLens: LensMode,
        supportedMimeTypes: Set<String>,
        thermalReason: String?,
    ): CameraBinding {
        val qualityCandidates = qualityCandidates(initialQuality)
        val lensCandidates = listOf(requestedLens, LensMode.MAIN_1X).distinct()
        val mimeCandidates = if (CaptureQualityProfile.VIDEO_MIME_HEVC in supportedMimeTypes) {
            listOf(CaptureQualityProfile.VIDEO_MIME_HEVC, CaptureQualityProfile.VIDEO_MIME_AVC)
        } else {
            listOf(CaptureQualityProfile.VIDEO_MIME_AVC)
        }
        var binding: CameraBinding? = null
        var lastFailure: Exception? = null

        qualityCandidates.forEach { quality ->
            lensCandidates.forEach { lens ->
                mimeCandidates.forEach { mime ->
                    if (binding == null) {
                        runCatching {
                            bindCamera(
                                provider = provider,
                                requestedQuality = requestedQuality,
                                appliedQuality = quality,
                                requestedLens = requestedLens,
                                appliedLens = lens,
                                mimeType = mime,
                                supportedMimeTypes = supportedMimeTypes,
                                thermalReason = thermalReason,
                            )
                        }.onSuccess { candidate ->
                            binding = candidate
                        }.onFailure { failure ->
                            lastFailure = failure as? Exception ?: Exception(failure)
                            provider.unbindAll()
                        }
                    }
                }
            }
        }
        binding?.let { return it }
        throw lastFailure ?: IllegalStateException("カメラの利用可能な組み合わせが見つからへん")
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        requestedQuality: CaptureQualityProfile,
        appliedQuality: CaptureQualityProfile,
        requestedLens: LensMode,
        appliedLens: LensMode,
        mimeType: String,
        supportedMimeTypes: Set<String>,
        thermalReason: String?,
    ): CameraBinding {
        val selector = cameraSelectorFor(provider, appliedLens)
        check(provider.hasCamera(selector)) { "背面カメラが利用できへん" }
        val cameraInfo = provider.getCameraInfo(selector)
        val capabilities = Recorder.getVideoCapabilities(cameraInfo, mimeType)
            ?: Recorder.getVideoCapabilities(cameraInfo)
        val supportedQualities = capabilities.getSupportedQualities(DynamicRange.SDR).toSet()
        val qualitySelector = QualitySelector.fromOrderedList(
            qualityCandidates(appliedQuality).map(::qualityForProfile),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )
        val recorder = Recorder.Builder()
            .setVideoCapabilitiesSource(Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
            .setVideoMimeType(mimeType)
            .setQualitySelector(qualitySelector)
            .build()
        val captureBuilder = VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(appliedQuality.targetFps, appliedQuality.targetFps))
        val physicalCameraId = selector.physicalCameraId
        physicalCameraId?.let { Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(it) }
        val capture = captureBuilder.build()
        val previewBuilder = Preview.Builder()
        physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
        val preview = previewBuilder.build()
        provider.unbindAll()
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        physicalCameraId?.let { Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(it) }
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(visionAnalyzer.analysisExecutor, visionAnalyzer)
        var boundCamera: Camera
        var attachedAnalysis: ImageAnalysis?
        var analysisFallbackReason: String?
        try {
            boundCamera = provider.bindToLifecycle(
                this@RecordingService,
                selector,
                preview,
                capture,
                analysis,
            )
            attachedAnalysis = analysis
            analysisFallbackReason = null
        } catch (_: Exception) {
            analysis.clearAnalyzer()
            provider.unbindAll()
            visionAnalyzer.reportExternalDegraded("ImageAnalysisが録画組み合わせに入らへん")
            boundCamera = provider.bindToLifecycle(
                this@RecordingService,
                selector,
                preview,
                capture,
            )
            attachedAnalysis = null
            analysisFallbackReason = "ImageAnalysis未接続（録画を優先）"
        }
        val actualLens = when (appliedLens) {
            LensMode.MAIN_1X -> {
                boundCamera.cameraControl.setZoomRatio(1f)
                LensMode.MAIN_1X
            }
            LensMode.ULTRA_WIDE_0_5X -> {
                check(selector.physicalCameraId != null) {
                    "0.5x用の物理超広角カメラが選択できへん"
                }
                // The selected physical sensor is already the wide lens. Its
                // native 1x is the requested 0.5x view relative to the main lens.
                boundCamera.cameraControl.setZoomRatio(1f)
                LensMode.ULTRA_WIDE_0_5X
            }
        }
        val actualQuality = capture.selectedQuality
        val fallbackReasons = buildList {
            thermalReason?.let(::add)
            if (appliedQuality != requestedQuality) {
                add("${requestedQuality.displayName} は未対応/thermal fallbackのため ${appliedQuality.displayName}")
            }
            if (actualLens != requestedLens) {
                add("${requestedLens.displayName} が未対応のため ${actualLens.displayName} を使用")
            }
            if (mimeType != CaptureQualityProfile.VIDEO_MIME_HEVC) {
                add("H.265が未対応のためH.264へfallback")
            }
            analysisFallbackReason?.let(::add)
            if (actualQuality != null && actualQuality != qualityForProfile(appliedQuality)) {
                add("CameraXが $actualQuality を選択")
            }
        }.distinct().joinToString("; ").ifBlank { null }
        return CameraBinding(
            camera = boundCamera,
            preview = preview,
            capture = capture,
            analysis = attachedAnalysis,
            selection = CaptureSelection(
                requestedQuality = requestedQuality,
                appliedQuality = appliedQuality,
                requestedLens = requestedLens,
                appliedLens = actualLens,
                codecMimeType = mimeType,
                fallbackReason = fallbackReasons,
                supportedQualities = supportedQualities,
                supportedMimeTypes = supportedMimeTypes,
                actualCameraQuality = actualQuality,
            ),
        )
    }

    private fun cameraSelectorFor(
        provider: ProcessCameraProvider,
        lensMode: LensMode,
    ): CameraSelector {
        val mainSelector = CameraSelector.DEFAULT_BACK_CAMERA
        if (lensMode == LensMode.MAIN_1X) return mainSelector

        val logicalInfo = provider.getCameraInfo(mainSelector)
        val mainFocalLengthMm = focalLengthMm(logicalInfo)
        val physicalCandidates = logicalInfo.getPhysicalCameraInfos().mapNotNull { info ->
            val cameraId = runCatching { Camera2Interop.getCameraId(info) }.getOrNull()
            val focalLengthMm = focalLengthMm(info)
            if (cameraId == null || focalLengthMm == null || info.lensFacing != CameraSelector.LENS_FACING_BACK) {
                null
            } else {
                PhysicalLensCandidate(cameraId, focalLengthMm)
            }
        }
        val ultraWide = CameraLensSelector.chooseUltraWide(mainFocalLengthMm, physicalCandidates)
            ?: throw IllegalStateException("0.5x用の物理超広角カメラが利用できへん")
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .setPhysicalCameraId(ultraWide.cameraId)
            .build()
    }

    private fun focalLengthMm(cameraInfo: CameraInfo): Float? = runCatching {
        Camera2Interop.getCameraCharacteristics(cameraInfo)
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
    }.getOrNull()

    private fun qualityCandidates(profile: CaptureQualityProfile): List<CaptureQualityProfile> = when (profile) {
        CaptureQualityProfile.HIGH -> listOf(
            CaptureQualityProfile.HIGH,
            CaptureQualityProfile.BALANCED,
            CaptureQualityProfile.ECO,
        )
        CaptureQualityProfile.BALANCED -> listOf(
            CaptureQualityProfile.BALANCED,
            CaptureQualityProfile.ECO,
        )
        CaptureQualityProfile.ECO -> listOf(CaptureQualityProfile.ECO)
    }

    private fun qualityForProfile(profile: CaptureQualityProfile): Quality = when (profile) {
        CaptureQualityProfile.HIGH -> Quality.UHD
        CaptureQualityProfile.BALANCED,
        CaptureQualityProfile.ECO,
        -> Quality.FHD
    }

    private fun applyThermalPolicyIfNeeded(thermalStatus: Int) {
        val requestedQuality = captureSettingsStore.qualityProfile()
        val decision = ThermalQualityPolicy.choose(requestedQuality, thermalStatus)
        val currentSelection = _uiState.value.capture.selection
        if (currentSelection.appliedQuality == decision.applied &&
            currentSelection.requestedQuality == requestedQuality
        ) return

        _uiState.update {
            it.copy(
                capture = it.capture.copy(
                    selection = it.capture.selection.copy(
                        requestedQuality = requestedQuality,
                        appliedQuality = decision.applied,
                    ),
                    lastQualityFallback = decision.reason,
                ),
            )
        }
        if (sessionActive && currentRecording != null && !stopRequested && !rebindForNextSegment) {
            rebindForNextSegment = true
            rotationJob?.cancel()
            currentRecording?.stop()
        } else if (!sessionActive && videoCapture != null) {
            rebindCamera()
        }
    }

    private fun rebindCamera(continueSession: Boolean = false) {
        continueSessionAfterRebind = continueSession
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        camera = null
        previewUseCase = null
        videoCapture = null
        _uiState.update { it.copy(cameraReady = false) }
        initializeCamera()
    }

    private data class CameraBinding(
        val camera: Camera,
        val preview: Preview,
        val capture: VideoCapture<Recorder>,
        val analysis: ImageAnalysis?,
        val selection: CaptureSelection,
    )

    private fun finishRecordingSession() {
        sessionActive = false
        stopRequested = false
        pendingStart = false
        continueSessionAfterRebind = false
        rebindForNextSegment = false
        rotationJob?.cancel()
        elapsedJob?.cancel()
        telemetryCollector.stop()
        visionAnalyzer.setEnabled(false)
        depthProvider.stop()
        ttcEstimator.reset()
        latestDepthSample = null
        _uiState.update { it.copy(status = RecordingStatus.IDLE, elapsedMs = 0L) }
        refreshSegmentState()
        updateNotification()
    }

    private fun releaseCamera() {
        cameraInitializationJob?.cancel()
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        cameraProvider = null
        camera = null
        previewUseCase = null
        videoCapture = null
        imageAnalysis = null
        continueSessionAfterRebind = false
        rebindForNextSegment = false
        _uiState.update { it.copy(cameraReady = false) }
    }

    private fun failRecording(message: String) {
        sessionActive = false
        stopRequested = true
        continueSessionAfterRebind = false
        rebindForNextSegment = false
        rotationJob?.cancel()
        elapsedJob?.cancel()
        telemetryCollector.stop()
        visionAnalyzer.setEnabled(false)
        depthProvider.stop()
        ttcEstimator.reset()
        latestDepthSample = null
        _uiState.update { it.copy(status = RecordingStatus.ERROR, lastError = message) }
        updateNotification()
    }

    private fun refreshSegmentState() {
        val segments = if (::segmentStore.isInitialized) segmentStore.listSegments() else emptyList()
        val events = if (::eventStore.isInitialized) eventStore.list() else emptyList()
        val explanations = if (::eventAnalysisStore.isInitialized) eventAnalysisStore.latestByEventId() else emptyMap()
        _uiState.update {
            it.copy(
                segmentCount = segments.size,
                protectedSegmentCount = segments.count(RecordingSegment::isProtected),
                segments = segments,
                events = events,
                eventExplanations = explanations,
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
        visionAnalyzer.setEnabled(false)
        depthProvider.stop()
        currentRecording?.stop()
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        cameraInitializationJob?.cancel()
        visionAnalyzer.close()
        trafficModelRunner.close()
        depthProvider.close()
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
        private const val TRAFFIC_STATUS_LOG_INTERVAL_NS = 5_000_000_000L
        private const val DEPTH_ASSOCIATION_WINDOW_NS = 1_000_000_000L
        private const val FRONT_APPROACH_TTC_SECONDS = 3f
        private const val FRONT_APPROACH_COOLDOWN_NS = 3_000_000_000L
    }
}
