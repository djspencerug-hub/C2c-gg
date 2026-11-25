package com.ctoc.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.ctoc.usblink.UsbLinkLogger
import java.nio.ByteBuffer

class H264SurfaceDecoder(
    private val logger: UsbLinkLogger,
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onFrameRendered: () -> Unit
) {
    private var codec: MediaCodec? = null
    private var outputSurface: Surface? = null
    private val lock = Any()
    private val bufferInfo = MediaCodec.BufferInfo()
    private var hasStartedWithConfig = false

    fun attachSurface(surface: Surface) {
        synchronized(lock) {
            outputSurface = surface
        }
    }

    fun detachSurface() {
        synchronized(lock) {
            releaseInternal()
            outputSurface = null
        }
    }

    fun decodeFrame(data: ByteArray, length: Int, flags: Int) {
        synchronized(lock) {
            val surface = outputSurface ?: return
            val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

            if (isConfig) {
                if (!hasStartedWithConfig || codec == null) {
                    startDecoder(surface, data, length)
                } else {
                    // Stream resolution/orientation changed; restart decoder cleanly with new SPS/PPS
                    releaseInternal()
                    startDecoder(surface, data, length)
                }
                return
            }

            val decoder = codec ?: return
            val inIdx = try {
                decoder.dequeueInputBuffer(10_000)
            } catch (e: Exception) {
                logger.warn(TAG, "dequeueInputBuffer failed: ${e.message}")
                return
            }
            if (inIdx >= 0) {
                val inputBuf = decoder.getInputBuffer(inIdx)
                if (inputBuf != null) {
                    inputBuf.clear()
                    val bytesCopied = minOf(length, inputBuf.remaining())
                    inputBuf.put(data, 0, bytesCopied)
                    decoder.queueInputBuffer(inIdx, 0, bytesCopied, 0L, flags)
                }
            }

            drainOutputs(decoder)
        }
    }

    private fun startDecoder(surface: Surface, csdData: ByteArray, csdLen: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csdData, 0, csdLen))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        try {
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            codec = decoder
            hasStartedWithConfig = true
            logger.info(TAG, "H.264 MediaCodec Surface decoder started (csd=$csdLen bytes).")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to start H.264 decoder: ${e.message}")
            releaseInternal()
        }
    }

    private fun drainOutputs(decoder: MediaCodec) {
        while (true) {
            val outIdx = try {
                decoder.dequeueOutputBuffer(bufferInfo, 0)
            } catch (_: Exception) {
                break
            }
            when {
                outIdx >= 0 -> {
                    val render = bufferInfo.size > 0
                    decoder.releaseOutputBuffer(outIdx, render)
                    if (render) onFrameRendered()
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = decoder.outputFormat
                    val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    logger.info(TAG, "Decoder output format: ${w}x${h}")
                    onVideoSizeChanged(w, h)
                }
                else -> break
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        hasStartedWithConfig = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    companion object {
        private const val TAG = "H264Decoder"
    }
}
