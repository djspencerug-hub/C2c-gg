package com.ctoc.usblink

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LatencyStats(
    val frameIntervalMs: Float = 0f,
    val estimatedOneWayMs: Float = 0f
)

class LatencyTracker {
    private val _stats = MutableStateFlow(LatencyStats())
    val stats: StateFlow<LatencyStats> = _stats.asStateFlow()

    @Volatile
    private var lastFrameNs = 0L

    fun reset() {
        lastFrameNs = 0L
        _stats.value = LatencyStats()
    }

    fun recordFrameEvent() {
        val now = SystemClock.elapsedRealtimeNanos()
        val prevNs = lastFrameNs
        lastFrameNs = now
        if (prevNs != 0L) {
            val deltaMs = (now - prevNs) / 1_000_000f
            val prevAvg = _stats.value.frameIntervalMs
            val smoothed = if (prevAvg == 0f) deltaMs else (prevAvg * 0.8f + deltaMs * 0.2f)
            _stats.value = _stats.value.copy(frameIntervalMs = smoothed)
        }
    }

    fun onEchoAckReceived(originalSenderTxNs: Long): Float {
        val rttMs = ((SystemClock.elapsedRealtimeNanos() - originalSenderTxNs) / 1_000_000f)
            .coerceAtLeast(0f)
        val oneWay = rttMs / 2f
        val prevAvg = _stats.value.estimatedOneWayMs
        val smoothed = if (prevAvg == 0f) oneWay else (prevAvg * 0.8f + oneWay * 0.2f)
        _stats.value = _stats.value.copy(estimatedOneWayMs = smoothed)
        return smoothed
    }

    fun updateRemoteEstimate(oneWayMs: Float) {
        _stats.value = _stats.value.copy(estimatedOneWayMs = oneWayMs)
    }
}
