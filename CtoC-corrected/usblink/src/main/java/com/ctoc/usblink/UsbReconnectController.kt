package com.ctoc.usblink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UsbReconnectController(
    private val scope: CoroutineScope,
    private val settings: UsbSettingsStore,
    private val tracker: UsbConnectionTracker,
    private val logger: UsbLinkLogger
) {
    private var reconnectJob: Job? = null
    @Volatile
    private var userStoppedManually = false

    fun markManualStop() {
        userStoppedManually = true
        cancelReconnect()
    }

    fun resetManualStop() {
        userStoppedManually = false
    }

    fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun onUnexpectedDisconnect(
        reason: String,
        attemptConnect: suspend () -> Boolean
    ) {
        if (userStoppedManually) return

        if (!settings.autoReconnect.value) {
            logger.warn("UsbReconnect", "$reason (auto-reconnect disabled)")
            tracker.onError("$reason. Auto-reconnect is off — tap Reconnect.")
            return
        }

        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            val delaysMs = longArrayOf(1000L, 2000L, 3000L, 5000L)
            var attempt = 0
            while (isActive && settings.autoReconnect.value && !userStoppedManually) {
                attempt++
                tracker.onSearching("Disconnected ($reason). Reconnecting #$attempt...")
                logger.info("UsbReconnect", "Auto-reconnect attempt #$attempt...")

                val ok = runCatching { attemptConnect() }.getOrDefault(false)
                if (ok) {
                    logger.info("UsbReconnect", "Reconnected on attempt #$attempt.")
                    return@launch
                }

                val waitMs = delaysMs[(attempt - 1).coerceAtMost(delaysMs.lastIndex)]
                delay(waitMs)
            }

            if (!settings.autoReconnect.value && !userStoppedManually) {
                tracker.onError("Auto-reconnect disabled. Tap Reconnect.")
            }
        }
    }
}
