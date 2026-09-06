package com.michidori.app.telemetry

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Collects real device telemetry without inventing values when a source is absent. */
class TelemetryCollector(
    context: Context,
    private val store: TelemetryStore,
) : SensorEventListener, LocationListener {
    private val appContext = context.applicationContext
    private val deviceStateCollector = DeviceStateCollector(appContext)
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val timestampNormalizer = MonotonicTimestampNormalizer()
    private val _uiState = MutableStateFlow(TelemetryUiState())
    private var running = false
    private var lastPersistedElapsedNs: Long? = null
    private var sampleCount = 0L
    private var lastLocation: Location? = null
    private var accelerometer: FloatArray? = null
    private var gyroscope: FloatArray? = null
    private var rotation: FloatArray? = null

    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    init {
        sampleCount = store.sampleCount()
        _uiState.value = _uiState.value.copy(sampleCount = sampleCount)
    }

    fun start() {
        if (running) return
        running = true
        lastLocation = null
        _uiState.value = _uiState.value.copy(gpsAvailable = false, speedKmh = null)
        deviceStateCollector.start()
        updateDeviceStateUi()
        startSensors()
        startLocation()
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
        runCatching { locationManager?.removeUpdates(this) }
        deviceStateCollector.stop()
        lastLocation = null
        _uiState.value = _uiState.value.copy(gpsAvailable = false, speedKmh = null)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerometer = event.values.copyOf(3)
            Sensor.TYPE_GYROSCOPE -> gyroscope = event.values.copyOf(3)
            Sensor.TYPE_ROTATION_VECTOR -> rotation = event.values.copyOf(3)
            else -> return
        }
        persistSample(event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (!running) return
        lastLocation = location
        _uiState.value = _uiState.value.copy(
            gpsAvailable = true,
            speedKmh = location.speed.takeIf { location.hasSpeed() }?.times(MPS_TO_KMH),
        )
        updateDeviceStateUi()
        persistSample(location.elapsedRealtimeNanos.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos(), location.time)
    }

    override fun onProviderDisabled(provider: String) {
        if (!running) return
        if (provider == LocationManager.GPS_PROVIDER) {
            lastLocation = null
            _uiState.value = _uiState.value.copy(gpsAvailable = false, speedKmh = null)
        }
    }

    private fun startSensors() {
        val sensors = listOfNotNull(
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
        )
        val registered = sensors.count { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) == true }
        _uiState.value = _uiState.value.copy(sensorsAvailable = registered > 0)
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) {
            lastLocation = null
            _uiState.value = _uiState.value.copy(gpsAvailable = false, speedKmh = null)
            return
        }
        val manager = locationManager ?: return
        val providerEnabled = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        if (!providerEnabled) {
            lastLocation = null
            _uiState.value = _uiState.value.copy(gpsAvailable = false, speedKmh = null)
            return
        }
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                LOCATION_MIN_DISTANCE_METERS,
                this,
                Looper.getMainLooper(),
            )
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
        }.onFailure {
            lastLocation = null
            _uiState.value = _uiState.value.copy(gpsAvailable = false, speedKmh = null)
        }
    }

    private fun persistSample(rawElapsedNs: Long, epochMs: Long = System.currentTimeMillis()) {
        val elapsedNs = timestampNormalizer.normalize(rawElapsedNs)
        val previous = lastPersistedElapsedNs
        if (previous != null && elapsedNs - previous < MIN_SAMPLE_INTERVAL_NS) return
        lastPersistedElapsedNs = elapsedNs

        val location = lastLocation
        val deviceState = deviceStateCollector.state.value
        store.append(
            TelemetrySample(
                elapsedNs = elapsedNs,
                epochMs = epochMs,
                latitude = location?.latitude,
                longitude = location?.longitude,
                speedMps = location?.speed?.takeIf { location.hasSpeed() },
                bearingDegrees = location?.bearing?.takeIf { location.hasBearing() },
                locationAccuracyMeters = location?.accuracy?.takeIf { location.hasAccuracy() },
                accelerometerX = accelerometer?.getOrNull(0),
                accelerometerY = accelerometer?.getOrNull(1),
                accelerometerZ = accelerometer?.getOrNull(2),
                gyroscopeX = gyroscope?.getOrNull(0),
                gyroscopeY = gyroscope?.getOrNull(1),
                gyroscopeZ = gyroscope?.getOrNull(2),
                rotationX = rotation?.getOrNull(0),
                rotationY = rotation?.getOrNull(1),
                rotationZ = rotation?.getOrNull(2),
                batteryPercent = deviceState.batteryPercent,
                isCharging = deviceState.isCharging,
                batteryTemperatureC = deviceState.batteryTemperatureC,
                thermalStatus = deviceState.thermalStatus,
                thermalLabel = deviceState.thermalLabel,
            ),
        )
        sampleCount += 1L
        _uiState.value = _uiState.value.copy(
            sampleCount = sampleCount,
            batteryPercent = deviceState.batteryPercent,
            isCharging = deviceState.isCharging,
            batteryTemperatureC = deviceState.batteryTemperatureC,
            thermalStatus = deviceState.thermalStatus,
            thermalLabel = deviceState.thermalLabel,
        )
    }

    private fun updateDeviceStateUi() {
        val deviceState = deviceStateCollector.state.value
        _uiState.value = _uiState.value.copy(
            batteryPercent = deviceState.batteryPercent,
            isCharging = deviceState.isCharging,
            batteryTemperatureC = deviceState.batteryTemperatureC,
            thermalStatus = deviceState.thermalStatus,
            thermalLabel = deviceState.thermalLabel,
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 0f
        private const val MIN_SAMPLE_INTERVAL_NS = 100_000_000L
        private const val MPS_TO_KMH = 3.6f
    }
}
