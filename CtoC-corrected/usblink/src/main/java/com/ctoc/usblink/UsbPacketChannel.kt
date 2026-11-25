package com.ctoc.usblink

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object UsbPacketTypes {
    const val VIDEO_H264: Byte = 0x01
    const val BATTERY_STATUS: Byte = 0x02
    const val FRAME_ACK: Byte = 0x03
    const val LATENCY_REPORT: Byte = 0x04
}

data class VideoFrameHeader(
    val flags: Int,
    val txTimeNs: Long,
    val payloadLength: Int
)

class UsbBulkInputStream(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val isActive: () -> Boolean
) : InputStream() {
    private val singleByte = ByteArray(1)

    override fun read(): Int {
        val n = read(singleByte, 0, 1)
        return if (n <= 0) -1 else (singleByte[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (isActive()) {
            val chunkLen = minOf(len, 16384)
            val transferred = connection.bulkTransfer(endpointIn, b, off, chunkLen, 2000)
            if (transferred > 0) return transferred
            if (transferred == 0) continue
            // If still active and device hasn't detached, -1 can be a 2s read timeout; verify via control or keep polling only while active.
            if (!isActive()) return -1
        }
        return -1
    }
}

class UsbBulkOutputStream(
    private val connection: UsbDeviceConnection,
    private val endpointOut: UsbEndpoint
) : OutputStream() {
    private val singleByte = ByteArray(1)

    override fun write(b: Int) {
        singleByte[0] = b.toByte()
        write(singleByte, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var written = 0
        while (written < len) {
            val chunk = minOf(len - written, 16384)
            val transferred = connection.bulkTransfer(endpointOut, b, off + written, chunk, 2000)
            if (transferred <= 0) {
                throw IOException("USB bulk OUT transfer failed (result=$transferred)")
            }
            written += transferred
        }
    }
}

class UsbPacketChannel(
    rawInput: InputStream,
    rawOutput: OutputStream
) {
    private val input = DataInputStream(BufferedInputStream(rawInput, 65536))
    private val output = DataOutputStream(BufferedOutputStream(rawOutput, 65536))
    private val writeLock = Any()
    private var txScratch = ByteArray(128 * 1024)
    private var rxScratch = ByteArray(256 * 1024)

    fun sendVideoFrame(buffer: ByteBuffer, size: Int, flags: Int, txTimeNs: Long) {
        if (size <= 0) return
        synchronized(writeLock) {
            if (txScratch.size < size) {
                txScratch = ByteArray(maxOf(size, txScratch.size * 2))
            }
            buffer.get(txScratch, 0, size)
            output.writeByte(UsbPacketTypes.VIDEO_H264.toInt())
            output.writeInt(size + 12)
            output.writeInt(flags)
            output.writeLong(txTimeNs)
            output.write(txScratch, 0, size)
            output.flush()
        }
    }

    fun sendBatteryStatus(percentage: Int, isCharging: Boolean) {
        synchronized(writeLock) {
            output.writeByte(UsbPacketTypes.BATTERY_STATUS.toInt())
            output.writeInt(2)
            output.writeByte(percentage.coerceIn(0, 100))
            output.writeByte(if (isCharging) 1 else 0)
            output.flush()
        }
    }

    fun sendFrameAck(txTimeNs: Long) {
        synchronized(writeLock) {
            output.writeByte(UsbPacketTypes.FRAME_ACK.toInt())
            output.writeInt(8)
            output.writeLong(txTimeNs)
            output.flush()
        }
    }

    fun sendLatencyReport(oneWayMs: Float) {
        synchronized(writeLock) {
            output.writeByte(UsbPacketTypes.LATENCY_REPORT.toInt())
            output.writeInt(4)
            output.writeFloat(oneWayMs)
            output.flush()
        }
    }

    fun readNextPacket(
        onVideoFrame: (flags: Int, txTimeNs: Long, data: ByteArray, length: Int) -> Unit = { _, _, _, _ -> },
        onBatteryStatus: (BatteryTelemetry) -> Unit = {},
        onFrameAck: (txTimeNs: Long) -> Unit = {},
        onLatencyReport: (oneWayMs: Float) -> Unit = {}
    ) {
        val type = input.readByte()
        val length = input.readInt()
        if (length < 0 || length > MAX_PACKET_BYTES) {
            throw IOException("Invalid USB packet length: $length")
        }

        when (type) {
            UsbPacketTypes.VIDEO_H264 -> {
                if (length < 12) throw IOException("Corrupt video header length: $length")
                val flags = input.readInt()
                val txTimeNs = input.readLong()
                val payloadLen = length - 12
                ensureRxCapacity(payloadLen)
                input.readFully(rxScratch, 0, payloadLen)
                onVideoFrame(flags, txTimeNs, rxScratch, payloadLen)
            }
            UsbPacketTypes.BATTERY_STATUS -> {
                ensureRxCapacity(length)
                input.readFully(rxScratch, 0, length)
                if (length >= 2) {
                    val pct = rxScratch[0].toInt() and 0xFF
                    val charging = rxScratch[1].toInt() != 0
                    onBatteryStatus(BatteryTelemetry(pct, charging))
                }
            }
            UsbPacketTypes.FRAME_ACK -> {
                if (length >= 8) {
                    val txTimeNs = input.readLong()
                    if (length > 8) skipFully(length - 8)
                    onFrameAck(txTimeNs)
                } else {
                    skipFully(length)
                }
            }
            UsbPacketTypes.LATENCY_REPORT -> {
                if (length >= 4) {
                    val oneWayMs = input.readFloat()
                    if (length > 4) skipFully(length - 4)
                    onLatencyReport(oneWayMs)
                } else {
                    skipFully(length)
                }
            }
            else -> skipFully(length)
        }
    }

    private fun ensureRxCapacity(needed: Int) {
        if (rxScratch.size < needed) {
            rxScratch = ByteArray(maxOf(needed, rxScratch.size * 2))
        }
    }

    private fun skipFully(bytes: Int) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) throw EOFException("Unexpected EOF skipping $remaining bytes")
            remaining -= skipped
        }
    }

    companion object {
        private const val MAX_PACKET_BYTES = 8 * 1024 * 1024
    }
}
