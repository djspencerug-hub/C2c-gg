package com.ctoc.usblink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class UsbConnectionState(val label: String) {
    data class Searching(val detail: String = "Searching for USB device...") : UsbConnectionState("Searching...")
    data class Connected(val detail: String) : UsbConnectionState("Connected")
    data class Error(val detail: String) : UsbConnectionState("Error")
}

class UsbConnectionTracker {
    private val _state = MutableStateFlow<UsbConnectionState>(
        UsbConnectionState.Searching("Waiting for USB-C connection...")
    )
    val state: StateFlow<UsbConnectionState> = _state.asStateFlow()

    fun onSearching(detail: String) {
        _state.value = UsbConnectionState.Searching(detail)
    }

    fun onConnected(detail: String) {
        _state.value = UsbConnectionState.Connected(detail)
    }

    fun onError(detail: String) {
        _state.value = UsbConnectionState.Error(detail)
    }
}
