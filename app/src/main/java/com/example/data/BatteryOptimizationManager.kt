package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.AssistantLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BatteryModePreference {
    AUTOMATIC,
    ALWAYS_ON,
    OFF
}

object BatteryOptimizationManager {
    private const val TAG = "BatteryManager"
    private const val PREFS_NAME = "mj_battery_prefs"
    private const val KEY_MODE = "battery_mode_preference"
    private const val LOW_BATTERY_THRESHOLD = 20

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _isPowerSaveMode = MutableStateFlow(false)
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    private val _isLowBatteryActive = MutableStateFlow(false)
    val isLowBatteryActive: StateFlow<Boolean> = _isLowBatteryActive.asStateFlow()

    private val _modePreference = MutableStateFlow(BatteryModePreference.AUTOMATIC)
    val modePreference: StateFlow<BatteryModePreference> = _modePreference.asStateFlow()

    private var isInitialized = false
    private var appContext: Context? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                   status == BatteryManager.BATTERY_STATUS_FULL

                    _batteryLevel.value = pct
                    _isCharging.value = charging
                    updateLowBatteryState()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    checkPowerSaveMode()
                    updateLowBatteryState()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    _isCharging.value = true
                    updateLowBatteryState()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    _isCharging.value = false
                    updateLowBatteryState()
                }
            }
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        appContext = context.applicationContext

        val prefs = appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = prefs.getString(KEY_MODE, BatteryModePreference.AUTOMATIC.name) ?: BatteryModePreference.AUTOMATIC.name
        _modePreference.value = try {
            BatteryModePreference.valueOf(savedMode)
        } catch (e: Exception) {
            BatteryModePreference.AUTOMATIC
        }

        checkPowerSaveMode()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        try {
            appContext!!.registerReceiver(batteryReceiver, filter)
            AssistantLogger.i(TAG, "Battery and Power Save monitor initialized successfully.")
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "Could not register battery receiver: ${e.message}")
        }
    }

    fun setModePreference(mode: BatteryModePreference) {
        _modePreference.value = mode
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_MODE, mode.name)
            ?.apply()

        AssistantLogger.i(TAG, "Battery optimization mode changed to: $mode")
        updateLowBatteryState()
    }

    private fun checkPowerSaveMode() {
        val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            _isPowerSaveMode.value = pm.isPowerSaveMode
        }
    }

    private fun updateLowBatteryState() {
        val shouldOptimize = when (_modePreference.value) {
            BatteryModePreference.OFF -> false
            BatteryModePreference.ALWAYS_ON -> true
            BatteryModePreference.AUTOMATIC -> {
                val isLow = _batteryLevel.value <= LOW_BATTERY_THRESHOLD && !_isCharging.value
                val isSystemSaving = _isPowerSaveMode.value
                isLow || isSystemSaving
            }
        }

        if (_isLowBatteryActive.value != shouldOptimize) {
            _isLowBatteryActive.value = shouldOptimize
            AssistantLogger.i(TAG, "Low Battery Mode Active: $shouldOptimize (Level: ${_batteryLevel.value}%, Charging: ${_isCharging.value})")
        }
    }

    fun getBatteryStatusSummary(): String {
        val level = _batteryLevel.value
        val charging = if (_isCharging.value) "Charging" else "Discharging"
        val active = if (_isLowBatteryActive.value) "Low Battery Mode Active" else "Normal Power"
        return "Battery is at $level% ($charging). $active."
    }
}
