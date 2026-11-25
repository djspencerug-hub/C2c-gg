package com.ctoc.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.ctoc.usblink.BatteryTelemetry
import com.ctoc.usblink.LatencyTracker
import com.ctoc.usblink.LocalBatteryMonitor
import com.ctoc.usblink.UsbConnectionState
import com.ctoc.usblink.UsbConnectionTracker
import com.ctoc.usblink.UsbLinkLogger
import com.ctoc.usblink.UsbPacketChannel
import com.ctoc.usblink.UsbReconnectController
import com.ctoc.usblink.UsbSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object SenderHub {
    val logger = UsbLinkLogger()
    val tracker = UsbConnectionTracker()
    val latencyTracker = LatencyTracker()

    private val _isMirroring = MutableStateFlow(false)
    val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

    private val _resolution = MutableStateFlow(StreamResolution.HD_720P)
    val resolution: StateFlow<StreamResolution> = _resolution.asStateFlow()

    private val _remoteBattery = MutableStateFlow<BatteryTelemetry?>(null)
    val remoteBattery: StateFlow<BatteryTelemetry?> = _remoteBattery.asStateFlow()

    fun setMirroring(active: Boolean) {
        _isMirroring.value = active
    }

    fun setResolution(preset: StreamResolution) {
        _resolution.value = preset
    }

    fun setRemoteBattery(telemetry: BatteryTelemetry?) {
        _remoteBattery.value = telemetry
    }
}

class MirrorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var usbManager: UsbManager
    private lateinit var settingsStore: UsbSettingsStore
    private lateinit var batteryMonitor: LocalBatteryMonitor
    private lateinit var reconnectController: UsbReconnectController

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val codecLock = Any()

    private var accessoryPfd: ParcelFileDescriptor? = null
    private var packetChannel: UsbPacketChannel? = null
    private var readerJob: Job? = null
    private var encoderJob: Job? = null
    private var batteryJob: Job? = null
    private var pendingAccessoryKey: String? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_ACCESSORY_PERMISSION -> {
                    pendingAccessoryKey = null
                    val accessory = extractAccessory(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (accessory == null) {
                        SenderHub.logger.error(TAG, "Accessory permission response missing EXTRA_ACCESSORY.")
                        SenderHub.tracker.onError("USB permission response invalid.")
                        return
                    }
                    if (!granted) {
                        SenderHub.logger.warn(TAG, "USB Accessory permission denied by user.")
                        SenderHub.tracker.onError("USB Accessory permission denied.")
                        reconnectController.cancelReconnect()
                        return
                    }
                    openAccessory(accessory)
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val accessory = extractAccessory(intent)
                    SenderHub.logger.info(TAG, "USB_ACCESSORY_ATTACHED: ${accessory?.model}")
                    accessory?.let { connectAccessory(it) }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    SenderHub.logger.warn(TAG, "USB_ACCESSORY_DETACHED")
                    closeUsbTransport()
                    reconnectController.onUnexpectedDisconnect("USB cable detached") {
                        scanAndOpenAccessory()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        settingsStore = UsbSettingsStore(this)
        batteryMonitor = LocalBatteryMonitor(this).apply { start() }
        reconnectController = UsbReconnectController(
            scope = serviceScope,
            settings = settingsStore,
            tracker = SenderHub.tracker,
            logger = SenderHub.logger
        )
        registerUsbReceiver()
        scanAndOpenAccessory()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MIRROR -> {
                startForegroundCompat()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode != -1 && data != null) {
                    reconnectController.resetManualStop()
                    startProjection(resultCode, data)
                }
            }
            ACTION_STOP_MIRROR -> {
                reconnectController.markManualStop()
                stopProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RECONNECT_USB -> {
                reconnectController.resetManualStop()
                scanAndOpenAccessory()
            }
            ACTION_SET_RESOLUTION -> {
                val ordinal = intent.getIntExtra(EXTRA_RESOLUTION_ORDINAL, 0)
                val preset = StreamResolution.entries.getOrElse(ordinal) { StreamResolution.HD_720P }
                SenderHub.setResolution(preset)
                if (SenderHub.isMirroring.value) {
                    reconfigureEncoder()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (SenderHub.isMirroring.value) {
            SenderHub.logger.info(TAG, "Display configuration changed; adjusting capture orientation.")
            reconfigureEncoder()
        }
    }

    override fun onDestroy() {
        stopProjection()
        closeUsbTransport()
        batteryMonitor.stop()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val channelId = "ctoc_mirror_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Screen Mirroring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("C to C Mirroring Active")
            .setContentText("Streaming screen over USB-C")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notification)
        }
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_ACCESSORY_PERMISSION)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun scanAndOpenAccessory(): Boolean {
        val accessories = usbManager.accessoryList
        if (accessories.isNullOrEmpty()) {
            SenderHub.tracker.onSearching("Waiting for USB OTG Receiver (AOA accessory)...")
            SenderHub.logger.info(TAG, "No USB accessories enumerated yet.")
            return false
        }
        return connectAccessory(accessories[0])
    }

    private fun connectAccessory(accessory: UsbAccessory): Boolean {
        if (!usbManager.hasPermission(accessory)) {
            if (pendingAccessoryKey == accessory.serial) return false
            pendingAccessoryKey = accessory.serial ?: accessory.model
            SenderHub.tracker.onSearching("Requesting USB Accessory permission...")
            val intent = Intent(ACTION_USB_ACCESSORY_PERMISSION).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(this, 10, intent, flags)
            usbManager.requestPermission(accessory, pi)
            return false
        }
        return openAccessory(accessory)
    }

    private fun openAccessory(accessory: UsbAccessory): Boolean {
        closeUsbTransport()
        val pfd = try {
            usbManager.openAccessory(accessory)
        } catch (e: Exception) {
            SenderHub.logger.error(TAG, "openAccessory failed: ${e.message}")
            SenderHub.tracker.onError("Failed to open USB accessory: ${e.message}")
            return false
        }
        if (pfd == null) {
            SenderHub.logger.error(TAG, "openAccessory returned null (device busy or detached).")
            SenderHub.tracker.onError("USB accessory busy or disconnected.")
            return false
        }

        accessoryPfd = pfd
        val fd = pfd.fileDescriptor
        val channel = UsbPacketChannel(FileInputStream(fd), FileOutputStream(fd))
        packetChannel = channel
        SenderHub.tracker.onConnected("USB Accessory linked (${accessory.model})")
        SenderHub.logger.info(TAG, "Opened AOA accessory: ${accessory.manufacturer} / ${accessory.model}")

        startReaderAndBatteryLoops(channel)
        return true
    }

    private fun startReaderAndBatteryLoops(channel: UsbPacketChannel) {
        readerJob?.cancel()
        batteryJob?.cancel()

        batteryJob = serviceScope.launch {
            batteryMonitor.battery.collect { telemetry ->
                runCatching {
                    channel.sendBatteryStatus(telemetry.percentage, telemetry.isCharging)
                }
            }
        }

        readerJob = serviceScope.launch {
            try {
                while (isActive) {
                    channel.readNextPacket(
                        onBatteryStatus = { remote -> SenderHub.setRemoteBattery(remote) },
                        onFrameAck = { txTimeNs ->
                            val oneWay = SenderHub.latencyTracker.onEchoAckReceived(txTimeNs)
                            runCatching { channel.sendLatencyReport(oneWay) }
                        }
                    )
                }
            } catch (e: IOException) {
                if (isActive) {
                    SenderHub.logger.warn(TAG, "USB read loop ended: ${e.message}")
                    closeUsbTransport()
                    reconnectController.onUnexpectedDisconnect("USB stream closed") {
                        scanAndOpenAccessory()
                    }
                }
            }
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        stopProjection()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: run {
            SenderHub.logger.error(TAG, "getMediaProjection returned null.")
            return
        }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                SenderHub.logger.warn(TAG, "MediaProjection stopped by system.")
                stopProjection()
            }
        }, null)
        mediaProjection = projection
        SenderHub.setMirroring(true)
        reconfigureEncoder()
    }

    private fun reconfigureEncoder() {
        val projection = mediaProjection ?: return
        val (dispW, dispH, dpi) = currentDisplayMetrics()
        val preset = SenderHub.resolution.value
        val (targetW, targetH) = preset.computeAlignedSize(dispW, dispH)

        SenderHub.logger.info(TAG, "Configuring H.264 encoder: ${targetW}x${targetH} @ ${preset.bitrateBps / 1_000_000} Mbps")

        encoderJob?.cancel()
        synchronized(codecLock) {
            virtualDisplay?.surface = null
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, preset.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = codec.createInputSurface()
            codec.start()

            encoder = codec
            inputSurface = surface

            if (virtualDisplay == null) {
                virtualDisplay = projection.createVirtualDisplay(
                    "CtoC-Mirror",
                    targetW,
                    targetH,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            } else {
                virtualDisplay?.resize(targetW, targetH, dpi)
                virtualDisplay?.surface = surface
            }

            startEncoderDrainLoop(codec)
        }
    }

    private fun startEncoderDrainLoop(codec: MediaCodec) {
        encoderJob = serviceScope.launch {
            val info = MediaCodec.BufferInfo()
            while (isActive && SenderHub.isMirroring.value) {
                val index = try {
                    codec.dequeueOutputBuffer(info, 10_000)
                } catch (_: Exception) {
                    break
                }
                if (index >= 0) {
                    val outBuffer = codec.getOutputBuffer(index)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        SenderHub.latencyTracker.recordFrameEvent()
                        val channel = packetChannel
                        if (channel != null) {
                            try {
                                channel.sendVideoFrame(
                                    outBuffer,
                                    info.size,
                                    info.flags,
                                    SystemClock.elapsedRealtimeNanos()
                                )
                            } catch (e: IOException) {
                                SenderHub.logger.warn(TAG, "USB video write failed: ${e.message}")
                                closeUsbTransport()
                                reconnectController.onUnexpectedDisconnect("USB write error") {
                                    scanAndOpenAccessory()
                                }
                            }
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    private fun stopProjection() {
        SenderHub.setMirroring(false)
        encoderJob?.cancel()
        synchronized(codecLock) {
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { mediaProjection?.stop() }
            mediaProjection = null
        }
        SenderHub.latencyTracker.reset()
    }

    private fun closeUsbTransport() {
        readerJob?.cancel()
        batteryJob?.cancel()
        packetChannel = null
        runCatching { accessoryPfd?.close() }
        accessoryPfd = null
        SenderHub.setRemoteBattery(null)
    }

    private fun currentDisplayMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val dpi = resources.configuration.densityDpi
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun extractAccessory(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

    companion object {
        private const val TAG = "MirrorService"
        private const val ACTION_USB_ACCESSORY_PERMISSION = "com.ctoc.sender.USB_ACCESSORY_PERMISSION"

        const val ACTION_START_MIRROR = "com.ctoc.sender.START_MIRROR"
        const val ACTION_STOP_MIRROR = "com.ctoc.sender.STOP_MIRROR"
        const val ACTION_RECONNECT_USB = "com.ctoc.sender.RECONNECT_USB"
        const val ACTION_SET_RESOLUTION = "com.ctoc.sender.SET_RESOLUTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_RESOLUTION_ORDINAL = "resolution_ordinal"
    }
}
