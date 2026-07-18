package dev.opentomac.android.mirror

import android.content.Context
import android.graphics.Point
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
import android.view.Display
import android.view.Surface
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.VideoConfig
import dev.opentomac.shared.protocol.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one MediaProjection -> H.264 encoder stream. */
class ScreenMirrorController(
    private val context: Context,
    private val projection: MediaProjection,
    private val maxLongEdge: Int,
    private val bitrateBps: Int,
    private val send: suspend (ChannelId, Message) -> Unit,
    private val onProjectionStopped: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opentomac-mirror-encoder")
    }
    private val drainDispatcher = drainExecutor.asCoroutineDispatcher()
    private val restartMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onProjectionStopped()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                scheduleEncoderRestart(width, height)
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val geometry = captureGeometry()
            if (geometry != lastDisplayGeometry) {
                lastDisplayGeometry = geometry
                scheduleEncoderRestart(geometry.width, geometry.height)
            }
        }
    }
    private val restartRunnable = Runnable {
        val sourceSize = pendingSourceSize ?: return@Runnable
        senderScope.launch {
            runCatching { restartEncoder(sourceSize.first, sourceSize.second) }
                .onFailure { cause -> if (!closed.get()) onFailure(cause) }
        }
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainJob: Job? = null
    private var senderJob: Job? = null
    private var frameQueue: Channel<VideoFrame>? = null
    private var activeOutputSize: Pair<Int, Int>? = null
    private var pendingSourceSize: Pair<Int, Int>? = null
    private var lastDisplayGeometry = captureGeometry()

    init {
        require(maxLongEdge > 0) { "Mirror long edge must be positive" }
        require(bitrateBps > 0) { "Mirror bitrate must be positive" }
    }

    fun start() {
        check(started.compareAndSet(false, true)) { "Screen mirror encoder already started" }
        projection.registerCallback(projectionCallback, mainHandler)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            displayManager.registerDisplayListener(displayListener, mainHandler)
        }
        val geometry = captureGeometry()
        lastDisplayGeometry = geometry
        senderScope.launch {
            runCatching { restartEncoder(geometry.width, geometry.height) }
                .onFailure { cause -> if (!closed.get()) onFailure(cause) }
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(restartRunnable)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
        }
        runCatching { projection.unregisterCallback(projectionCallback) }
        restartMutex.withLock { stopEncoder(releaseVirtualDisplay = true) }
        senderScope.cancel()
        runCatching { projection.stop() }
        drainDispatcher.close()
        drainExecutor.shutdown()
        Log.w("opentomac", "screen mirror encoder stopped")
    }

    private fun scheduleEncoderRestart(sourceWidth: Int, sourceHeight: Int) {
        if (closed.get() || sourceWidth <= 0 || sourceHeight <= 0) return
        pendingSourceSize = sourceWidth to sourceHeight
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, RESIZE_DEBOUNCE_MS)
    }

    private suspend fun restartEncoder(sourceWidth: Int, sourceHeight: Int) {
        restartMutex.withLock {
            if (closed.get()) return
            val (width, height) = outputSize(sourceWidth, sourceHeight)
            if (activeOutputSize == width to height) return

            stopEncoder(releaseVirtualDisplay = false)
            val reusingVirtualDisplay = virtualDisplay != null
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = encoder.createInputSurface().also { inputSurface = it }
                encoder.start()

                val display = virtualDisplay
                if (display == null) {
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
                } else {
                    // Android 14 MediaProjection tokens allow createVirtualDisplay only
                    // once. Rebind the rebuilt encoder surface and resize that display.
                    display.resize(width, height, context.resources.displayMetrics.densityDpi)
                    display.setSurface(surface)
                }

                val config = CompletableDeferred<VideoConfig>()
                val frames = Channel<VideoFrame>(
                    capacity = 2,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                frameQueue = frames
                activeOutputSize = width to height
                senderJob = senderScope.launch {
                    send(ChannelId.VIDEO, config.await())
                    for (frame in frames) send(ChannelId.VIDEO, frame)
                }
                drainJob = senderScope.launch(drainDispatcher) {
                    drain(encoder, width, height, config, frames)
                }
                Log.w(
                    "opentomac",
                    "screen mirror encoder started at ${width}x$height ($bitrateBps bps)",
                )
            } catch (cause: Throwable) {
                stopEncoder(releaseVirtualDisplay = !reusingVirtualDisplay)
                throw cause
            }
        }
    }

    private suspend fun stopEncoder(releaseVirtualDisplay: Boolean) {
        runCatching { virtualDisplay?.setSurface(null) }
        drainJob?.cancelAndJoin()
        drainJob = null
        senderJob?.cancelAndJoin()
        senderJob = null
        frameQueue?.close()
        frameQueue = null
        activeOutputSize = null
        releaseCodec()
        if (releaseVirtualDisplay) {
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
        }
    }

    private suspend fun drain(
        encoder: MediaCodec,
        width: Int,
        height: Int,
        config: CompletableDeferred<VideoConfig>,
        frames: Channel<VideoFrame>,
    ) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed.get() && coroutineContext.isActive) {
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
                                    frames.trySend(
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
            if (!closed.get() && coroutineContext.isActive) onFailure(cause)
        }
    }

    private fun requestKeyframe(encoder: MediaCodec) {
        runCatching {
            encoder.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        }.onFailure { Log.w("opentomac", "could not request mirror keyframe", it) }
    }

    @Suppress("DEPRECATION")
    private fun captureGeometry(): CaptureGeometry {
        val display = requireNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
        val size = Point().also(display::getRealSize)
        return CaptureGeometry(display.rotation, size.x, size.y)
    }

    private fun outputSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxLongEdge.toFloat() / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).roundToInt() and -2).coerceAtLeast(2)
        val height = ((sourceHeight * scale).roundToInt() and -2).coerceAtLeast(2)
        return width to height
    }

    private fun releaseCodec() {
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

    private data class CaptureGeometry(
        val rotation: Int,
        val width: Int,
        val height: Int,
    )

    companion object {
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val MAX_FRAME_BYTES = 3 * 1024 * 1024
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val RESIZE_DEBOUNCE_MS = 300L
    }
}
