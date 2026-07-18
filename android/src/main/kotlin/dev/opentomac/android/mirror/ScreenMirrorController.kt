package dev.opentomac.android.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.VideoConfig
import dev.opentomac.shared.protocol.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Owns one MediaProjection -> H.264 encoder stream. */
class ScreenMirrorController(
    private val context: Context,
    private val projection: MediaProjection,
    private val send: suspend (ChannelId, Message) -> Unit,
    private val onProjectionStopped: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opentomac-mirror-encoder")
    }
    private val drainDispatcher = drainExecutor.asCoroutineDispatcher()
    private val frameQueue = Channel<VideoFrame>(
        capacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val config = CompletableDeferred<VideoConfig>()
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onProjectionStopped()
        }
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainJob: Job? = null

    fun start() {
        check(codec == null) { "Screen mirror encoder already started" }
        val (width, height) = outputSize()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = encoder
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface().also { inputSurface = it }
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            encoder.start()
            virtualDisplay = projection.createVirtualDisplay(
                "opentomac-screen-mirror",
                width,
                height,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
            senderScope.launch {
                send(ChannelId.VIDEO, config.await())
                for (frame in frameQueue) send(ChannelId.VIDEO, frame)
            }
            drainJob = senderScope.launch(drainDispatcher) { drain(encoder, width, height) }
            Log.w("opentomac", "screen mirror encoder started at ${width}x$height")
        } catch (cause: Throwable) {
            releaseEncoder()
            throw cause
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        drainJob?.cancelAndJoin()
        drainJob = null
        frameQueue.close()
        senderScope.cancel()
        releaseEncoder()
        runCatching { projection.stop() }
        drainDispatcher.close()
        drainExecutor.shutdown()
        Log.w("opentomac", "screen mirror encoder stopped")
    }

    private fun drain(encoder: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed.get()) {
                when (val index = encoder.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = encoder.outputFormat
                        val csd0 = outputFormat.getByteBuffer("csd-0")?.copyBytes()
                            ?: error("H.264 encoder did not provide csd-0")
                        val csd1 = outputFormat.getByteBuffer("csd-1")?.copyBytes()
                            ?: error("H.264 encoder did not provide csd-1")
                        config.complete(VideoConfig(width, height, csd0, csd1, FRAME_RATE))
                    }
                    else -> if (index >= 0) {
                        try {
                            val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!codecConfig && info.size > 0) {
                                if (info.size > MAX_FRAME_BYTES) {
                                    Log.w("opentomac", "dropping oversized mirror frame (${info.size} bytes)")
                                    requestKeyframe(encoder)
                                } else {
                                    val output = requireNotNull(encoder.getOutputBuffer(index)).duplicate().apply {
                                        position(info.offset)
                                        limit(info.offset + info.size)
                                    }
                                    val bytes = ByteArray(info.size)
                                    output.get(bytes)
                                    frameQueue.trySend(
                                        VideoFrame(
                                            ptsUs = info.presentationTimeUs,
                                            keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                                            data = bytes,
                                        ),
                                    )
                                }
                            }
                        } finally {
                            encoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (cause: Throwable) {
            if (!closed.get()) onFailure(cause)
        }
    }

    private fun requestKeyframe(encoder: MediaCodec) {
        runCatching {
            encoder.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        }.onFailure { Log.w("opentomac", "could not request mirror keyframe", it) }
    }

    private fun outputSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val (sourceWidth, sourceHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
            size.x to size.y
        }
        val scale = minOf(1f, MAX_LONG_EDGE.toFloat() / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).roundToInt() and -2).coerceAtLeast(2)
        val height = ((sourceHeight * scale).roundToInt() and -2).coerceAtLeast(2)
        return width to height
    }

    private fun releaseEncoder() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val copy = duplicate()
        return ByteArray(copy.remaining()).also(copy::get)
    }

    companion object {
        private const val MAX_LONG_EDGE = 1280
        private const val BIT_RATE = 6_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val MAX_FRAME_BYTES = 3 * 1024 * 1024
        private const val OUTPUT_TIMEOUT_US = 10_000L
    }
}
