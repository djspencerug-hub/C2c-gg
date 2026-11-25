package com.ctoc.usblink

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("ctoc_usb_prefs", Context.MODE_PRIVATE)

    private val _autoReconnect = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    )
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
        _autoReconnect.value = enabled
    }

    companion object {
        private const val KEY_AUTO_RECONNECT = "auto_reconnect_enabled"
    }
}
