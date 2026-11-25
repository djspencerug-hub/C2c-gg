package com.ctoc.receiver

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ctoc.usblink.BatteryTelemetry
import com.ctoc.usblink.LatencyTracker
import com.ctoc.usblink.LocalBatteryMonitor
import com.ctoc.usblink.OtgHelpContent
import com.ctoc.usblink.UsbBulkInputStream
import com.ctoc.usblink.UsbBulkOutputStream
import com.ctoc.usblink.UsbConnectionState
import com.ctoc.usblink.UsbConnectionTracker
import com.ctoc.usblink.UsbHostManager
import com.ctoc.usblink.UsbHostSession
import com.ctoc.usblink.UsbLinkLogger
import com.ctoc.usblink.UsbPacketChannel
import com.ctoc.usblink.UsbReconnectController
import com.ctoc.usblink.UsbSettingsStore
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val logger = UsbLinkLogger()
    private val tracker = UsbConnectionTracker()
    private val latencyTracker = LatencyTracker()
    private val remoteBattery = MutableStateFlow<BatteryTelemetry?>(null)

    private lateinit var settingsStore: UsbSettingsStore
    private lateinit var batteryMonitor: LocalBatteryMonitor
    private lateinit var reconnectController: UsbReconnectController
    private lateinit var usbHostManager: UsbHostManager
    private lateinit var decoder: H264SurfaceDecoder

    private lateinit var statusDot: View
    private lateinit var tvStatusLabel: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvRemoteBattery: TextView
    private lateinit var tvLatencyOverlay: TextView
    private lateinit var tvLogs: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var videoContainer: FrameLayout

    private var activeSession: UsbHostSession? = null
    private var readerJob: Job? = null
    private var batteryJob: Job? = null
    @Volatile
    private var sessionRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        tvStatusLabel = findViewById(R.id.tvStatusLabel)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvRemoteBattery = findViewById(R.id.tvRemoteBattery)
        tvLatencyOverlay = findViewById(R.id.tvLatencyOverlay)
        tvLogs = findViewById(R.id.tvLogs)
        tvLogs.movementMethod = ScrollingMovementMethod()
        surfaceView = findViewById(R.id.surfaceView)
        videoContainer = findViewById(R.id.videoContainer)

        surfaceView.holder.addCallback(this)

        settingsStore = UsbSettingsStore(this)
        batteryMonitor = LocalBatteryMonitor(this).apply { start() }
        reconnectController = UsbReconnectController(lifecycleScope, settingsStore, tracker, logger)

        decoder = H264SurfaceDecoder(
            logger = logger,
            onVideoSizeChanged = { w, h -> runOnUiThread { adjustAspectRatio(w, h) } },
            onFrameRendered = { latencyTracker.recordFrameEvent() }
        )

        usbHostManager = UsbHostManager(
            context = this,
            logger = logger,
            tracker = tracker,
            onSessionReady = { session -> startUsbSession(session) },
            onDetached = { reason ->
                closeUsbSession()
                reconnectController.onUnexpectedDisconnect(reason) {
                    usbHostManager.scanConnectedDevices()
                }
            },
            onPermissionDenied = {
                reconnectController.cancelReconnect()
            }
        ).apply { register() }

        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            reconnectController.resetManualStop()
            closeUsbSession()
            usbHostManager.scanConnectedDevices()
        }
        findViewById<Button>(R.id.btnLogs).setOnClickListener {
            tvLogs.visibility = if (tvLogs.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btnHelp).setOnClickListener { showOtgHelpDialog() }

        observeState()

        usbHostManager.handleAttachIntent(intent)
        if (activeSession == null) {
            usbHostManager.scanConnectedDevices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        usbHostManager.handleAttachIntent(intent)
    }

    override fun onDestroy() {
        reconnectController.markManualStop()
        closeUsbSession()
        decoder.detachSurface()
        usbHostManager.unregister()
        batteryMonitor.stop()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        decoder.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        decoder.detachSurface()
    }

    private fun startUsbSession(session: UsbHostSession) {
        closeUsbSession()
        activeSession = session
        sessionRunning = true
        reconnectController.resetManualStop()

        val channel = UsbPacketChannel(
            UsbBulkInputStream(session.connection, session.bulkIn) { sessionRunning },
            UsbBulkOutputStream(session.connection, session.bulkOut)
        )

        batteryJob = lifecycleScope.launch(Dispatchers.IO) {
            batteryMonitor.battery.collect { telemetry ->
                runCatching { channel.sendBatteryStatus(telemetry.percentage, telemetry.isCharging) }
            }
        }

        readerJob = lifecycleScope.launch(Dispatchers.IO) {
            var lastAckNs = 0L
            try {
                while (isActive && sessionRunning) {
                    channel.readNextPacket(
                        onVideoFrame = { flags, txTimeNs, data, length ->
                            decoder.decodeFrame(data, length, flags)
                            val now = SystemClock.elapsedRealtimeNanos()
                            if (txTimeNs > 0L && now - lastAckNs >= 100_000_000L) {
                                lastAckNs = now
                                runCatching { channel.sendFrameAck(txTimeNs) }
                            }
                        },
                        onBatteryStatus = { remote -> remoteBattery.value = remote },
                        onLatencyReport = { oneWayMs -> latencyTracker.updateRemoteEstimate(oneWayMs) }
                    )
                }
            } catch (e: IOException) {
                if (isActive && sessionRunning) {
                    logger.warn("ReceiverUSB", "USB stream interrupted: ${e.message}")
                    closeUsbSession()
                    reconnectController.onUnexpectedDisconnect("USB read error") {
                        usbHostManager.scanConnectedDevices()
                    }
                }
            }
        }
    }

    private fun closeUsbSession() {
        sessionRunning = false
        readerJob?.cancel()
        batteryJob?.cancel()
        activeSession?.let { s ->
            runCatching { s.connection.releaseInterface(s.usbInterface) }
            runCatching { s.connection.close() }
        }
        activeSession = null
        remoteBattery.value = null
        decoder.reset()
        latencyTracker.reset()
    }

    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        val cw = videoContainer.width
        val ch = videoContainer.height
        if (cw <= 0 || ch <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val videoRatio = videoWidth.toFloat() / videoHeight
        val containerRatio = cw.toFloat() / ch
        val lp = surfaceView.layoutParams
        if (videoRatio > containerRatio) {
            lp.width = cw
            lp.height = (cw / videoRatio).toInt()
        } else {
            lp.height = ch
            lp.width = (ch * videoRatio).toInt()
        }
        surfaceView.layoutParams = lp
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    tracker.state.collect { state ->
                        tvStatusLabel.text = state.label
                        val (color, detail) = when (state) {
                            is UsbConnectionState.Connected -> 0xFF22C55E.toInt() to state.detail
                            is UsbConnectionState.Searching -> 0xFFFBBF24.toInt() to state.detail
                            is UsbConnectionState.Error -> 0xFFEF4444.toInt() to state.detail
                        }
                        tvStatusLabel.setTextColor(color)
                        statusDot.backgroundTintList = ColorStateList.valueOf(color)
                        tvStatusDetail.text = detail
                    }
                }
                launch {
                    remoteBattery.collect { remote ->
                        tvRemoteBattery.text = when {
                            remote == null || remote.percentage < 0 -> "Sender: --%"
                            remote.isCharging -> "Sender: ${remote.percentage}% (Chg)"
                            else -> "Sender: ${remote.percentage}%"
                        }
                    }
                }
                launch {
                    latencyTracker.stats.collect { stats ->
                        val fps = if (stats.frameIntervalMs > 0f) 1000f / stats.frameIntervalMs else 0f
                        tvLatencyOverlay.text = if (stats.estimatedOneWayMs > 0f) {
                            String.format(
                                Locale.US,
                                "Frame: %.1f ms (%.0f FPS) | USB: ~%.1f ms",
                                stats.frameIntervalMs,
                                fps,
                                stats.estimatedOneWayMs
                            )
                        } else {
                            String.format(Locale.US, "Frame: %.1f ms (%.0f FPS)", stats.frameIntervalMs, fps)
                        }
                    }
                }
                launch {
                    logger.logs.collect { entries ->
                        tvLogs.text = entries.takeLast(40).joinToString("\n") { it.format() }
                    }
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val switchView = MaterialSwitch(this).apply {
            text = "Auto-reconnect on USB disconnect"
            isChecked = settingsStore.autoReconnect.value
            setPadding(48, 32, 48, 16)
            setOnCheckedChangeListener { _, checked ->
                settingsStore.setAutoReconnect(checked)
                if (!checked) reconnectController.cancelReconnect()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Receiver Settings")
            .setView(switchView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showOtgHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(OtgHelpContent.TITLE)
            .setMessage(OtgHelpContent.BODY)
            .setPositiveButton("Got it", null)
            .show()
    }
}
