package com.ctoc.usblink

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

data class UsbHostSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint
)

class UsbHostManager(
    private val context: Context,
    private val logger: UsbLinkLogger,
    private val tracker: UsbConnectionTracker,
    private val onSessionReady: (UsbHostSession) -> Unit,
    private val onDetached: (String) -> Unit,
    private val onPermissionDenied: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var registered = false
    private var pendingPermissionDeviceName: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> handlePermissionResponse(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = extractDevice(intent)
                    logger.info(TAG, "USB_DEVICE_ATTACHED: ${describeDevice(device)}")
                    device?.let { inspectAndConnect(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = extractDevice(intent)
                    pendingPermissionDeviceName = null
                    val desc = describeDevice(device)
                    logger.warn(TAG, "USB_DEVICE_DETACHED: $desc")
                    onDetached("USB device detached ($desc)")
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
        logger.info(TAG, "USB Host BroadcastReceiver registered.")
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
        pendingPermissionDeviceName = null
    }

    fun scanConnectedDevices(): Boolean {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            val msg = "No USB devices detected. Check USB-C cable or use an OTG adapter on the Receiver."
            logger.warn(TAG, msg)
            tracker.onError(msg)
            return false
        }

        val preferred = devices.firstOrNull { AoaProtocol.isAoaDevice(it) } ?: devices.first()
        logger.info(TAG, "Found ${devices.size} USB device(s); inspecting ${describeDevice(preferred)}")
        inspectAndConnect(preferred)
        return true
    }

    fun handleAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = extractDevice(intent)
            if (device != null) {
                logger.info(TAG, "Activity received USB_DEVICE_ATTACHED for ${describeDevice(device)}")
                inspectAndConnect(device)
            }
        }
    }

    private fun inspectAndConnect(device: UsbDevice) {
        val desc = describeDevice(device)
        if (!usbManager.hasPermission(device)) {
            if (pendingPermissionDeviceName == device.deviceName) {
                logger.warn(TAG, "Permission request already pending for $desc.")
                return
            }
            pendingPermissionDeviceName = device.deviceName
            tracker.onSearching("Requesting USB permission for $desc...")
            logger.info(TAG, "Requesting USB Host permission for $desc...")

            val explicitIntent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(appContext, device.deviceId, explicitIntent, flags)
            usbManager.requestPermission(device, pi)
            return
        }

        openAndHandshake(device)
    }

    private fun handlePermissionResponse(intent: Intent) {
        pendingPermissionDeviceName = null
        val device = extractDevice(intent)
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

        if (device == null) {
            val msg = "USB permission response missing EXTRA_DEVICE."
            logger.error(TAG, msg)
            tracker.onError(msg)
            onPermissionDenied(msg)
            return
        }

        if (!granted) {
            val msg = "USB Host permission denied by user for ${describeDevice(device)}."
            logger.warn(TAG, msg)
            tracker.onError(msg)
            onPermissionDenied(msg)
            return
        }

        logger.info(TAG, "USB Host permission granted for ${describeDevice(device)}.")
        openAndHandshake(device)
    }

    private fun openAndHandshake(device: UsbDevice) {
        val desc = describeDevice(device)
        val connection = try {
            usbManager.openDevice(device)
        } catch (se: SecurityException) {
            val msg = "SecurityException opening $desc: ${se.message}"
            logger.error(TAG, msg)
            tracker.onError(msg)
            return
        }

        if (connection == null) {
            val msg = "openDevice() returned null for $desc — device busy, detached, or resetting."
            logger.error(TAG, msg)
            tracker.onError(msg)
            return
        }

        if (!AoaProtocol.isAoaDevice(device)) {
            tracker.onSearching("Negotiating AOA mode with $desc...")
            val switched = AoaProtocol.switchToAccessoryMode(connection, logger)
            connection.close()
            if (switched) {
                tracker.onSearching("Waiting for Galaxy Note 20 to reconnect in AOA mode...")
            } else {
                val msg = "$desc does not support AOA or is in charging-only/incompatible USB role. Try OTG adapter."
                logger.error(TAG, msg)
                tracker.onError(msg)
            }
            return
        }

        val endpoints = AoaProtocol.findBulkEndpoints(device)
        if (endpoints == null) {
            connection.close()
            val msg = "No bulk IN/OUT endpoints found on AOA device $desc."
            logger.error(TAG, msg)
            tracker.onError(msg)
            return
        }

        if (!connection.claimInterface(endpoints.usbInterface, false)) {
            logger.warn(
                TAG,
                "Interface ${endpoints.usbInterface.id} busy on $desc; retrying with force=true..."
            )
            if (!connection.claimInterface(endpoints.usbInterface, true)) {
                connection.close()
                val msg = "Device busy: could not claim USB interface ${endpoints.usbInterface.id} on $desc."
                logger.error(TAG, msg)
                tracker.onError(msg)
                return
            }
            logger.info(TAG, "Force-claimed busy interface ${endpoints.usbInterface.id} on $desc.")
        } else {
            logger.info(TAG, "Claimed interface ${endpoints.usbInterface.id} on $desc.")
        }

        tracker.onConnected("AOA USB link active ($desc)")
        onSessionReady(
            UsbHostSession(
                device = device,
                connection = connection,
                usbInterface = endpoints.usbInterface,
                bulkIn = endpoints.bulkIn,
                bulkOut = endpoints.bulkOut
            )
        )
    }

    private fun extractDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun describeDevice(device: UsbDevice?): String =
        if (device == null) "unknown"
        else "${device.productName ?: "USB"} (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})"

    companion object {
        private const val TAG = "UsbHostManager"
        private const val ACTION_USB_PERMISSION = "com.ctoc.usblink.USB_HOST_PERMISSION"
    }
}
