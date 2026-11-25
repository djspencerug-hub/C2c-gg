package com.ctoc.usblink

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UsbLogLevel { INFO, WARN, ERROR }

data class UsbLogEntry(
    val timestampMs: Long,
    val level: UsbLogLevel,
    val tag: String,
    val message: String
) {
    fun format(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        return "[$time] [${level.name}] [$tag] $message"
    }
}

class UsbLinkLogger(private val maxEntries: Int = 250) {
    private val _logs = MutableStateFlow<List<UsbLogEntry>>(emptyList())
    val logs: StateFlow<List<UsbLogEntry>> = _logs.asStateFlow()

    fun info(tag: String, message: String) = append(UsbLogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = append(UsbLogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = append(UsbLogLevel.ERROR, tag, message)

    fun clear() {
        _logs.value = emptyList()
    }

    private fun append(level: UsbLogLevel, tag: String, message: String) {
        when (level) {
            UsbLogLevel.INFO -> Log.i(tag, message)
            UsbLogLevel.WARN -> Log.w(tag, message)
            UsbLogLevel.ERROR -> Log.e(tag, message)
        }
        val entry = UsbLogEntry(System.currentTimeMillis(), level, tag, message)
        _logs.update { current ->
            val next = current + entry
            if (next.size > maxEntries) next.drop(next.size - maxEntries) else next
        }
    }
}
