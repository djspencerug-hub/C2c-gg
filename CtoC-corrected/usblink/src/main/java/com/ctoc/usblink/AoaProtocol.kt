package com.ctoc.usblink

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

object AoaProtocol {
    const val AOA_VENDOR_ID = 0x18D1
    val AOA_PRODUCT_IDS = setOf(0x2D00, 0x2D01, 0x2D04, 0x2D05)

    const val MANUFACTURER = "CtoC"
    const val MODEL = "CtoC-Mirror"
    const val DESCRIPTION = "USB-C Low-Latency Screen Mirroring"
    const val VERSION = "1.0"
    const val URI = "https://ctoc.local"
    const val SERIAL = "CTOC-0001"

    private const val REQ_GET_PROTOCOL = 51
    private const val REQ_SEND_STRING = 52
    private const val REQ_START_ACCESSORY = 53

    fun isAoaDevice(device: UsbDevice): Boolean =
        device.vendorId == AOA_VENDOR_ID && device.productId in AOA_PRODUCT_IDS

    fun getProtocolVersion(connection: UsbDeviceConnection): Int {
        val buf = ByteArray(2)
        val res = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
            REQ_GET_PROTOCOL,
            0,
            0,
            buf,
            2,
            1500
        )
        if (res < 2) return 0
        return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
    }

    fun switchToAccessoryMode(connection: UsbDeviceConnection, logger: UsbLinkLogger): Boolean {
        val version = getProtocolVersion(connection)
        logger.info("AoaProtocol", "AOA protocol version supported by device: $version")
        if (version <= 0) return false

        val strings = listOf(
            0 to MANUFACTURER,
            1 to MODEL,
            2 to DESCRIPTION,
            3 to VERSION,
            4 to URI,
            5 to SERIAL
        )
        for ((index, value) in strings) {
            val bytes = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val sent = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                REQ_SEND_STRING,
                0,
                index,
                bytes,
                bytes.size,
                1500
            )
            if (sent < 0) {
                logger.error("AoaProtocol", "AOA string index $index transfer failed ($sent).")
                return false
            }
        }

        val started = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            REQ_START_ACCESSORY,
            0,
            0,
            null,
            0,
            1500
        )
        if (started < 0) {
            logger.error("AoaProtocol", "AOA start accessory request (53) failed ($started).")
            return false
        }
        logger.info("AoaProtocol", "Sent AOA switch command; waiting for device re-enumeration...")
        return true
    }

    data class BulkEndpoints(
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint
    )

    fun findBulkEndpoints(device: UsbDevice): BulkEndpoints? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
                }
            }
            if (bulkIn != null && bulkOut != null) {
                return BulkEndpoints(iface, bulkIn, bulkOut)
            }
        }
        return null
    }
}
