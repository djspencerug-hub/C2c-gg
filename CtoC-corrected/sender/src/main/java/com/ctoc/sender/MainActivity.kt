package com.ctoc.sender

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ctoc.usblink.OtgHelpContent
import com.ctoc.usblink.UsbConnectionState
import com.ctoc.usblink.UsbSettingsStore
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var settingsStore: UsbSettingsStore

    private lateinit var statusDot: View
    private lateinit var tvStatusLabel: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvRemoteBattery: TextView
    private lateinit var tvLatencyOverlay: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnResolution: Button
    private lateinit var btnStartMirroring: Button
    private lateinit var btnReconnect: Button
    private lateinit var btnSettings: Button
    private lateinit var btnHelp: Button

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_START_MIRROR
                putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MirrorService.EXTRA_DATA, result.data)
            }
            startForegroundService(svcIntent)
        } else {
            SenderHub.logger.warn("SenderUI", "Screen capture permission cancelled by user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsStore = UsbSettingsStore(this)

        statusDot = findViewById(R.id.statusDot)
        tvStatusLabel = findViewById(R.id.tvStatusLabel)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvRemoteBattery = findViewById(R.id.tvRemoteBattery)
        tvLatencyOverlay = findViewById(R.id.tvLatencyOverlay)
        tvLogs = findViewById(R.id.tvLogs)
        tvLogs.movementMethod = ScrollingMovementMethod()
        btnResolution = findViewById(R.id.btnResolution)
        btnStartMirroring = findViewById(R.id.btnStartMirroring)
        btnReconnect = findViewById(R.id.btnReconnect)
        btnSettings = findViewById(R.id.btnSettings)
        btnHelp = findViewById(R.id.btnHelp)

        // Ensure service is active to listen for AOA USB accessory events
        startService(Intent(this, MirrorService::class.java))

        btnStartMirroring.setOnClickListener {
            if (SenderHub.isMirroring.value) {
                startService(Intent(this, MirrorService::class.java).apply {
                    action = MirrorService.ACTION_STOP_MIRROR
                })
            } else {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        btnReconnect.setOnClickListener {
            startService(Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_RECONNECT_USB
            })
        }

        btnResolution.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                StreamResolution.entries.forEachIndexed { idx, preset ->
                    menu.add(0, idx, idx, preset.menuLabel)
                }
                setOnMenuItemClickListener { item ->
                    val preset = StreamResolution.entries[item.itemId]
                    startService(Intent(this@MainActivity, MirrorService::class.java).apply {
                        action = MirrorService.ACTION_SET_RESOLUTION
                        putExtra(MirrorService.EXTRA_RESOLUTION_ORDINAL, preset.ordinal)
                    })
                    true
                }
                show()
            }
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnHelp.setOnClickListener { showOtgHelpDialog() }

        observeHub()
    }

    private fun observeHub() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SenderHub.tracker.state.collect { state ->
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
                    SenderHub.isMirroring.collect { active ->
                        btnStartMirroring.text = if (active) "Stop Mirroring" else "Start Mirroring"
                    }
                }
                launch {
                    SenderHub.resolution.collect { preset ->
                        btnResolution.text = "Resolution: ${preset.shortLabel}"
                    }
                }
                launch {
                    SenderHub.remoteBattery.collect { remote ->
                        tvRemoteBattery.text = when {
                            remote == null || remote.percentage < 0 -> "Connected Receiver Battery: --%"
                            remote.isCharging -> "Connected Receiver Battery: ${remote.percentage}% (Charging)"
                            else -> "Connected Receiver Battery: ${remote.percentage}%"
                        }
                    }
                }
                launch {
                    SenderHub.latencyTracker.stats.collect { stats ->
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
                    SenderHub.logger.logs.collect { entries ->
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
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Sender Settings")
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
