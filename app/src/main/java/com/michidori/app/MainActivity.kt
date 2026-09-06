package com.michidori.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michidori.app.recording.RecordingService
import com.michidori.app.recording.RecordingSegment
import com.michidori.app.recording.RecordingStatus
import com.michidori.app.recording.RecordingUiState
import com.michidori.app.recording.ExportedSegment
import com.michidori.app.playback.PlaybackActivity
import com.michidori.app.ui.MichidoriTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var boundService: RecordingService? by mutableStateOf(null)
    private var cameraPermissionGranted by mutableStateOf(false)
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? RecordingService.LocalBinder ?: return
            boundService = localBinder.service()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            boundService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        cameraPermissionGranted = hasCameraPermission()
        if (cameraPermissionGranted) {
            startAndBindRecordingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionGranted = hasCameraPermission()
        if (cameraPermissionGranted) startAndBindRecordingService()

        setContent {
            MichidoriTheme {
                MichidoriApp(
                    service = boundService,
                    cameraPermissionGranted = cameraPermissionGranted,
                    onRequestPermissions = ::requestPermissions,
                    onStartRecording = { boundService?.startRecording() },
                    onStopRecording = { boundService?.stopRecording() },
                    onSaveEvent = { boundService?.saveManualEvent() },
                    onSetQualityProfile = { boundService?.setQualityProfile(it) },
                    onSetLensMode = { boundService?.setLensMode(it) },
                    onExplainEvent = { boundService?.explainEvent(it) },
                    onPlaySegment = ::openPlayback,
                    onShareSegment = { segment ->
                        boundService?.exportSegment(segment.id)?.let(::shareExport)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) startAndBindRecordingService()
    }

    override fun onStop() {
        boundService?.let { service ->
            if (!service.uiState.value.isRecording) service.detachPreview()
        }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
        super.onStop()
    }

    private fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(permissions)
    }

    private fun startAndBindRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!isBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun openPlayback(segment: RecordingSegment) {
        startActivity(
            Intent(this, PlaybackActivity::class.java)
                .putExtra(PlaybackActivity.EXTRA_FILE_NAME, segment.fileName),
        )
    }

    private fun shareExport(exported: ExportedSegment) {
        val videoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", exported.video)
        val metadataUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", exported.metadata)
        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>(videoUri, metadataUri))
            putExtra(Intent.EXTRA_TEXT, "元動画とmetadata sidecar。推定値は確定事実やないで。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Michidoriの記録を共有"))
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MichidoriApp(
    service: RecordingService?,
    cameraPermissionGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveEvent: () -> Unit,
    onSetQualityProfile: (com.michidori.app.recording.CaptureQualityProfile) -> Unit,
    onSetLensMode: (com.michidori.app.recording.LensMode) -> Unit,
    onExplainEvent: (String) -> Unit,
    onPlaySegment: (RecordingSegment) -> Unit,
    onShareSegment: (RecordingSegment) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Michidori", fontWeight = FontWeight.SemiBold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MichidoriColors.background,
                    titleContentColor = MichidoriColors.textPrimary,
                ),
            )
        },
        containerColor = MichidoriColors.background,
    ) { paddingValues ->
        if (!cameraPermissionGranted) {
            PermissionGate(
                modifier = Modifier.padding(paddingValues),
                onRequestPermissions = onRequestPermissions,
            )
        } else if (service == null) {
            LoadingState(modifier = Modifier.padding(paddingValues))
        } else {
            val state by service.uiState.collectAsStateWithLifecycle()
            RecordingDashboard(
                modifier = Modifier.padding(paddingValues),
                service = service,
                state = state,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onSaveEvent = onSaveEvent,
                onSetQualityProfile = onSetQualityProfile,
                onSetLensMode = onSetLensMode,
                onExplainEvent = onExplainEvent,
                onPlaySegment = onPlaySegment,
                onShareSegment = onShareSegment,
            )
        }
    }
}

@Composable
private fun PermissionGate(
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "走行を記録する準備をしよか",
            color = MichidoriColors.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "カメラ権限は必須やで。位置情報は速度・GPS状態の表示に使うけど、許可されなくても録画は続けられるようにしてあるわ。",
            color = MichidoriColors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermissions) {
            Text("権限を設定する")
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("カメラを準備中やで…", color = MichidoriColors.textSecondary)
    }
}

@Composable
private fun RecordingDashboard(
    modifier: Modifier,
    service: RecordingService,
    state: RecordingUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveEvent: () -> Unit,
    onSetQualityProfile: (com.michidori.app.recording.CaptureQualityProfile) -> Unit,
    onSetLensMode: (com.michidori.app.recording.LensMode) -> Unit,
    onExplainEvent: (String) -> Unit,
    onPlaySegment: (RecordingSegment) -> Unit,
    onShareSegment: (RecordingSegment) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CameraPreview(
            service = service,
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        CaptureSettingsRow(
            state = state,
            onSetQualityProfile = onSetQualityProfile,
            onSetLensMode = onSetLensMode,
        )
        StatusSummary(state)
        ControlRow(
            state = state,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onSaveEvent = onSaveEvent,
        )
        EventHistory(
            state = state,
            onExplainEvent = onExplainEvent,
            onPlaySegment = onPlaySegment,
            onShareSegment = onShareSegment,
        )
        Text(
            text = "映像は端末内に保存。GPS・センサー値は monotonic timestamp で別ログに記録するで。推定値は安全制御や法的証拠には使わんといてな。",
            color = MichidoriColors.textMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun CameraPreview(
    service: RecordingService,
    state: RecordingUiState,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black, RoundedCornerShape(18.dp)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                androidx.camera.view.PreviewView(context).apply {
                    scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                    implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                    service.attachPreview(this)
                }
            },
            update = { service.attachPreview(it) },
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatusPill(
                    label = recordingLabel(state.status),
                    color = if (state.status == RecordingStatus.RECORDING) {
                        MichidoriColors.recording
                    } else {
                        MichidoriColors.textSecondary
                    },
                )
                StatusPill(
                    label = if (state.gpsAvailable) "GPS OK" else "GPS --",
                    color = if (state.gpsAvailable) MichidoriColors.gps else MichidoriColors.warning,
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    label = "AI ${state.vision.status.name}",
                    color = if (state.vision.status == com.michidori.app.vision.VisionStatus.READY) {
                        MichidoriColors.gps
                    } else {
                        MichidoriColors.warning
                    },
                )
                StatusPill(
                    label = "DEPTH ${state.depth.status.name}",
                    color = if (state.depth.status == com.michidori.app.depth.DepthStatus.READY) {
                        MichidoriColors.gps
                    } else {
                        MichidoriColors.warning
                    },
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                text = formatElapsed(state.elapsedMs),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${state.speedKmh?.let { String.format(Locale.US, "%.0f", it) } ?: "--"} km/h",
                color = Color.White,
                fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatusSummary(state: RecordingUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MichidoriColors.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryItem("SEGMENT", "${state.segmentCount}")
            SummaryItem("PROTECTED", "${state.protectedSegmentCount}")
            SummaryItem("TELEMETRY", "${state.telemetrySampleCount}")
        }
        Text(
            text = "${state.capture.selection.appliedQuality.displayName} · ${state.capture.selection.appliedLens.displayName} · ${codecLabel(state.capture.selection.codecMimeType)}",
            color = MichidoriColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
        )
        Text(
            text = deviceStatusLabel(state),
            color = MichidoriColors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
        )
        Text(
            text = "AI ${state.vision.statusMessage} · objects ${state.vision.objectCount} · ${state.vision.lastInferenceMs?.let { "${it}ms" } ?: "--"}",
            color = MichidoriColors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
        )
        Text(
            text = "DEPTH ${state.depth.statusMessage} · ${state.depth.lastDistanceMeters?.let { String.format(Locale.US, "%.1fm", it) } ?: "--"} · LiteRT ${state.trafficModelStatus}",
            color = MichidoriColors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
        )
        state.lastEventType?.let { eventType ->
            Text(
                text = "EVENT $eventType",
                color = MichidoriColors.warning,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun CaptureSettingsRow(
    state: RecordingUiState,
    onSetQualityProfile: (com.michidori.app.recording.CaptureQualityProfile) -> Unit,
    onSetLensMode: (com.michidori.app.recording.LensMode) -> Unit,
) {
    val selection = state.capture.selection
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = { onSetQualityProfile(nextQuality(selection.requestedQuality)) },
            enabled = !state.isRecording,
            modifier = Modifier.weight(1f),
        ) {
            Text("画質\n${selection.requestedQuality.displayName}", textAlign = TextAlign.Center, lineHeight = 16.sp)
        }
        OutlinedButton(
            onClick = { onSetLensMode(nextLens(selection.requestedLens)) },
            enabled = !state.isRecording,
            modifier = Modifier.weight(0.6f),
        ) {
            Text("レンズ\n${selection.requestedLens.displayName}", textAlign = TextAlign.Center, lineHeight = 16.sp)
        }
    }
    selection.fallbackReason?.let { reason ->
        Text(
            text = reason,
            color = MichidoriColors.warning,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MichidoriColors.textMuted, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = MichidoriColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ControlRow(
    state: RecordingUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveEvent: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isRecording) {
            OutlinedButton(
                onClick = onStopRecording,
                enabled = state.status != RecordingStatus.STOPPING,
                modifier = Modifier.weight(1f),
            ) {
                Text("停止")
            }
        } else {
            Button(
                onClick = onStartRecording,
                enabled = state.status != RecordingStatus.STARTING,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.status == RecordingStatus.ERROR) "再試行" else "録画開始")
            }
        }
        Button(
            onClick = onSaveEvent,
            enabled = state.status == RecordingStatus.RECORDING,
            modifier = Modifier.weight(1.25f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MichidoriColors.save,
                contentColor = Color.White,
            ),
        ) {
            Text("SAVE")
        }
    }
    state.lastError?.let { error ->
        Text(error, color = MichidoriColors.warning, fontSize = 12.sp)
    }
}

@Composable
private fun EventHistory(
    state: RecordingUiState,
    onExplainEvent: (String) -> Unit,
    onPlaySegment: (RecordingSegment) -> Unit,
    onShareSegment: (RecordingSegment) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp),
        colors = CardDefaults.cardColors(containerColor = MichidoriColors.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("イベント履歴", color = MichidoriColors.textPrimary, fontWeight = FontWeight.SemiBold)
            if (state.events.isEmpty()) {
                Text("まだイベント候補は無いで", color = MichidoriColors.textMuted, fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.events.takeLast(MAX_VISIBLE_EVENTS).asReversed(), key = { it.id }) { event ->
                        val segment = state.segments.lastOrNull { it.overlaps(event.elapsedNs, event.elapsedNs) }
                        val explanation = state.eventExplanations[event.id]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${event.type} · ${event.severity} · ${(event.confidence * 100).toInt()}%",
                                    color = MichidoriColors.textSecondary,
                                    fontSize = 12.sp,
                                )
                                event.details?.takeIf(String::isNotBlank)?.let { details ->
                                    Text(details, color = MichidoriColors.textMuted, fontSize = 10.sp, maxLines = 1)
                                }
                                explanation?.let { memo ->
                                    Text(
                                        text = "${memo.source.name}: ${memo.text}",
                                        color = MichidoriColors.textMuted,
                                        fontSize = 10.sp,
                                        maxLines = 2,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { onExplainEvent(event.id) },
                                enabled = state.explainingEventId == null,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text(if (state.explainingEventId == event.id) "…" else "説明", fontSize = 10.sp)
                            }
                            OutlinedButton(
                                onClick = { segment?.let(onPlaySegment) },
                                enabled = segment != null,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("再生", fontSize = 10.sp)
                            }
                            OutlinedButton(
                                onClick = { segment?.let(onShareSegment) },
                                enabled = segment != null,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("共有", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun recordingLabel(status: RecordingStatus): String = when (status) {
    RecordingStatus.RECORDING -> "REC"
    RecordingStatus.STARTING -> "STARTING"
    RecordingStatus.STOPPING -> "STOPPING"
    RecordingStatus.ERROR -> "ERROR"
    RecordingStatus.IDLE -> "READY"
}

private const val MAX_VISIBLE_EVENTS = 8

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

private fun deviceStatusLabel(state: RecordingUiState): String {
    val battery = state.capture.batteryPercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"
    val charging = if (state.capture.isCharging) " ⚡" else ""
    val temperature = state.capture.batteryTemperatureC?.let { String.format(Locale.US, " · %.1f°C", it) } ?: ""
    return "THERMAL ${state.capture.thermalLabel} · BATTERY $battery$charging$temperature"
}

private fun nextQuality(current: com.michidori.app.recording.CaptureQualityProfile): com.michidori.app.recording.CaptureQualityProfile {
    val values = com.michidori.app.recording.CaptureQualityProfile.entries
    return values[(values.indexOf(current) + 1) % values.size]
}

private fun nextLens(current: com.michidori.app.recording.LensMode): com.michidori.app.recording.LensMode {
    val values = com.michidori.app.recording.LensMode.entries
    return values[(values.indexOf(current) + 1) % values.size]
}

private fun codecLabel(mimeType: String): String = when (mimeType) {
    com.michidori.app.recording.CaptureQualityProfile.VIDEO_MIME_HEVC -> "H.265"
    com.michidori.app.recording.CaptureQualityProfile.VIDEO_MIME_AVC -> "H.264"
    else -> mimeType.substringAfterLast('/').uppercase(Locale.US)
}

private object MichidoriColors {
    val background = Color(0xFF071018)
    val surface = Color(0xFF10202B)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFFB8C7D1)
    val textMuted = Color(0xFF7F96A3)
    val recording = Color(0xFFFF5B68)
    val gps = Color(0xFF62D6A7)
    val warning = Color(0xFFFFC857)
    val save = Color(0xFF2563EB)
}
