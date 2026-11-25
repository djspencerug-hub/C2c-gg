package com.ctoc.usblink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryTelemetry(
    val percentage: Int,
    val isCharging: Boolean
)

class LocalBatteryMonitor(private val context: Context) {
    private val appContext = context.applicationContext
    private val _battery = MutableStateFlow(readCurrentBattery())
    val battery: StateFlow<BatteryTelemetry> = _battery.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val next = parseIntent(intent)
                if (next != _battery.value) {
                    _battery.value = next
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val sticky = appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { _battery.value = parseIntent(it) }
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    private fun readCurrentBattery(): BatteryTelemetry {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return BatteryTelemetry(if (pct in 0..100) pct else -1, bm.isCharging)
    }

    private fun parseIntent(intent: Intent): BatteryTelemetry {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryTelemetry(pct, charging)
    }
}
