package com.michidori.app.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.michidori.app.recording.ThermalQualityPolicy

data class DeviceState(
    val batteryPercent: Float? = null,
    val isCharging: Boolean = false,
    val batteryTemperatureC: Float? = null,
    val thermalStatus: Int = ThermalQualityPolicy.THERMAL_NONE,
    val thermalLabel: String = ThermalQualityPolicy.label(ThermalQualityPolicy.THERMAL_NONE),
)

class DeviceStateCollector(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(DeviceState())
    private var running = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::updateFromBatteryIntent)
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _state.value = _state.value.copy(
            thermalStatus = status,
            thermalLabel = ThermalQualityPolicy.label(status),
        )
    }

    val state: StateFlow<DeviceState> = _state.asStateFlow()

    fun start() {
        if (running) return
        running = true
        ContextCompat.registerReceiver(
            appContext,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::updateFromBatteryIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.addThermalStatusListener(appContext.mainExecutor, thermalListener)
            powerManager?.currentThermalStatus?.let { status ->
                _state.value = _state.value.copy(
                    thermalStatus = status,
                    thermalLabel = ThermalQualityPolicy.label(status),
                )
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.removeThermalStatusListener(thermalListener)
        }
    }

    private fun updateFromBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        _state.value = _state.value.copy(
            batteryPercent = if (level >= 0 && scale > 0) level * 100f / scale else null,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTemperatureC = temperature.takeUnless { it == Int.MIN_VALUE }?.div(10f),
        )
    }
}
