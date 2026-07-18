package dev.opentomac.android.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.opentomac.shared.protocol.AudioFrame
import dev.opentomac.shared.protocol.CameraConfig
import dev.opentomac.shared.protocol.CameraFrame
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.Message
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one CameraX camera -> MediaCodec H.264 stream and, optionally, one
 * AudioRecord -> MediaCodec AAC-LC stream. CameraX is deliberately used only
 * for camera selection/lifecycle and to feed the encoder input [Surface]; using
 * MediaCodec directly keeps the wire format identical to screen mirroring.
 */
class CameraStreamController(
    private val context: Context,
    private val facing: String,
    private val withAudio: Boolean,
    private val send: suspend (ChannelId, Message) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : LifecycleOwner {
    private val closed = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val awaitingRecoveryKeyframe = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opentomac-camera-x")
    }
    private val codecExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "opentomac-camera-codec")
    }
    private val codecDispatcher = codecExecutor.asCoroutineDispatcher()

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCodec: MediaCodec? = null
    private var videoSurface: Surface? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoDrainJob: Job? = null
    private var audioDrainJob: Job? = null
    private var videoSenderJob: Job? = null
    private var audioSenderJob: Job? = null
    private var videoFrames: Channel<CameraFrame>? = null
    private var audioFrames: Channel<AudioFrame>? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        require(facing == FACING_FRONT || facing == FACING_BACK) { "Camera facing must be front or back" }
    }

    fun start() {
        check(started.compareAndSet(false, true)) { "Camera stream already started" }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission not granted"
        }
        if (withAudio) {
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Microphone permission not granted" }
        }

        try {
            val config = CompletableDeferred<CameraConfig>()
            val frames = Channel<CameraFrame>(capacity = 2)
            videoFrames = frames

            ContextCompat.getMainExecutor(context).execute {
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    {
                        runCatching {
                            if (closed.get()) return@runCatching
                            val provider = future.get().also { cameraProvider = it }
                            bindCamera(provider, config, frames)
                        }.onFailure(::fail)
                    },
                    ContextCompat.getMainExecutor(context),
                )
            }
        } catch (cause: Throwable) {
            fail(cause)
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Kill microphone capture before waiting on CameraX/main-thread teardown.
        // A busy main looper must never leave AudioRecord hot.
        runCatching { audioRecord?.stop() }
        val cameraUnbound = CompletableDeferred<Unit>()
        runCatching {
            ContextCompat.getMainExecutor(context).execute {
                runCatching { cameraProvider?.unbindAll() }
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
                cameraUnbound.complete(Unit)
            }
        }.onFailure {
            Log.w("opentomac", "could not schedule CameraX teardown", it)
            cameraUnbound.complete(Unit)
        }
        if (withTimeoutOrNull(CAMERA_UNBIND_TIMEOUT_MS) { cameraUnbound.await() } == null) {
            Log.w("opentomac", "CameraX teardown timed out; releasing encoder surface")
        }
        videoDrainJob?.cancel()
        audioDrainJob?.cancel()
        val drainsStopped = withTimeoutOrNull(CODEC_DRAIN_STOP_TIMEOUT_MS) {
            videoDrainJob?.join()
            audioDrainJob?.join()
            true
        } ?: false
        if (!drainsStopped) {
            Log.w("opentomac", "camera codec drain teardown timed out")
        }
        videoSenderJob?.cancel()
        audioSenderJob?.cancel()
        val sendersStopped = withTimeoutOrNull(SENDER_STOP_TIMEOUT_MS) {
            videoSenderJob?.join()
            audioSenderJob?.join()
            true
        } ?: false
        if (!sendersStopped) {
            Log.w("opentomac", "camera sender teardown timed out")
        }
        videoFrames?.close()
        audioFrames?.close()
        releaseCodecs()
        scope.cancel()
        codecDispatcher.close()
        codecExecutor.shutdown()
        cameraExecutor.shutdown()
        Log.w("opentomac", "camera stream stopped")
    }

    private fun configureVideoEncoder(
        width: Int,
        height: Int,
        config: CompletableDeferred<CameraConfig>,
        frames: Channel<CameraFrame>,
    ) {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec = encoder
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE_BPS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoSurface = encoder.createInputSurface()
        encoder.start()
        videoSenderJob = scope.launch {
            send(ChannelId.VIDEO, config.await())
            for (frame in frames) send(ChannelId.VIDEO, frame)
        }
        videoDrainJob = scope.launch(codecDispatcher) {
            drainVideo(encoder, width, height, config, frames)
        }
    }

    private fun bindCamera(
        provider: ProcessCameraProvider,
        config: CompletableDeferred<CameraConfig>,
        frames: Channel<CameraFrame>,
    ) {
        val targetRotation = currentDisplayRotation()
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(PREFERRED_WIDTH, PREFERRED_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .setResolutionSelector(resolutionSelector)
            .build()
        val selector = if (facing == FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview)

        // CameraX chooses the Preview buffer size only while binding. Configure
        // MediaCodec from that selected sensor-coordinate resolution, then ask
        // CameraX for its SurfaceRequest. This keeps the camera buffer, encoder
        // surface, H.264 macroblock layout, and CameraConfig dimensions equal.
        val resolutionInfo = checkNotNull(preview.resolutionInfo) {
            "CameraX did not resolve a Preview output size after binding"
        }
        val resolution = resolutionInfo.resolution
        configureVideoEncoder(resolution.width, resolution.height, config, frames)
        if (withAudio) configureAudioEncoder()
        val surface = checkNotNull(videoSurface)
        preview.setSurfaceProvider(cameraExecutor) { request ->
            if (request.resolution != resolution) {
                request.willNotProvideSurface()
                fail(
                    IllegalStateException(
                        "CameraX SurfaceRequest ${request.resolution.width}x${request.resolution.height} " +
                            "does not match encoder ${resolution.width}x${resolution.height}",
                    ),
                )
                return@setSurfaceProvider
            }
            request.provideSurface(surface, cameraExecutor) { result ->
                if (!closed.get() && result.resultCode != androidx.camera.core.SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                    Log.w("opentomac", "CameraX released encoder surface: ${result.resultCode}")
                }
            }
        }
        Log.w(
            "opentomac",
            "camera stream started: $facing ${resolution.width}x${resolution.height} " +
                "bufferRotation=${resolutionInfo.rotationDegrees} " +
                "targetRotation=${targetRotation.toRotationDegrees()}" +
                if (withAudio) " with mic" else "",
        )
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: Surface.ROTATION_0

    private fun Int.toRotationDegrees(): Int = when (this) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private suspend fun drainVideo(
        encoder: MediaCodec,
        width: Int,
        height: Int,
        config: CompletableDeferred<CameraConfig>,
        frames: Channel<CameraFrame>,
    ) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!closed.get() && coroutineContext.isActive) {
                when (val index = encoder.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = encoder.outputFormat
                        val csd0 = output.getByteBuffer("csd-0")?.copyBytes()
                            ?: error("H.264 camera encoder did not provide csd-0")
                        val csd1 = output.getByteBuffer("csd-1")?.copyBytes()
                            ?: error("H.264 camera encoder did not provide csd-1")
                        config.complete(CameraConfig(width, height, csd0, csd1, FRAME_RATE))
                    }
                    else -> if (index >= 0) {
                        try {
                            val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!codecConfig && info.size > 0) {
                                if (info.size > MAX_VIDEO_FRAME_BYTES) {
                                    Log.w("opentomac", "dropping oversized camera frame (${info.size} bytes)")
                                    enterVideoRecovery(encoder, frames)
                                } else {
                                    val output = checkNotNull(encoder.getOutputBuffer(index)).slice(info)
                                    enqueueVideoFrame(
                                        encoder,
                                        frames,
                                        CameraFrame(
                                            ptsUs = info.presentationTimeUs,
                                            keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                                            data = ByteArray(info.size).also(output::get),
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
            if (!closed.get() && coroutineContext.isActive) fail(cause)
        }
    }

    private fun enqueueVideoFrame(
        encoder: MediaCodec,
        frames: Channel<CameraFrame>,
        frame: CameraFrame,
    ) {
        if (awaitingRecoveryKeyframe.get()) {
            if (!frame.keyframe) return
            drainQueuedVideoFrames(frames)
            awaitingRecoveryKeyframe.set(false)
            if (frames.trySend(frame).isFailure) enterVideoRecovery(encoder, frames)
            return
        }

        val result = frames.trySend(frame)
        if (result.isSuccess || result.isClosed) return

        // The sender is backpressured and an arbitrary P-frame would otherwise
        // be lost. Empty the pending GOP, request an IDR immediately, and send
        // nothing dependent until that keyframe arrives.
        enterVideoRecovery(encoder, frames)
        if (frame.keyframe) {
            awaitingRecoveryKeyframe.set(false)
            if (frames.trySend(frame).isFailure) enterVideoRecovery(encoder, frames)
        }
    }

    private fun enterVideoRecovery(encoder: MediaCodec, frames: Channel<CameraFrame>) {
        if (closed.get()) return
        awaitingRecoveryKeyframe.set(true)
        drainQueuedVideoFrames(frames)
        requestKeyframe(encoder)
    }

    private fun drainQueuedVideoFrames(frames: Channel<CameraFrame>) {
        while (frames.tryReceive().isSuccess) Unit
    }

    private fun requestKeyframe(encoder: MediaCodec) {
        runCatching {
            encoder.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        }.onFailure { Log.w("opentomac", "could not request camera keyframe", it) }
    }

    @SuppressLint("MissingPermission")
    private fun configureAudioEncoder() {
        val minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "No supported microphone input format" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, AUDIO_READ_BYTES * 2),
        ).also { audioRecord = it }
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec = encoder
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_READ_BYTES)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val frames = Channel<AudioFrame>(2, BufferOverflow.DROP_OLDEST)
        audioFrames = frames
        audioSenderJob = scope.launch {
            for (frame in frames) send(ChannelId.VIDEO, frame)
        }
        audioDrainJob = scope.launch(codecDispatcher) { encodeAudio(record, encoder, frames) }
    }

    private suspend fun encodeAudio(
        record: AudioRecord,
        encoder: MediaCodec,
        frames: Channel<AudioFrame>,
    ) {
        val info = MediaCodec.BufferInfo()
        var samplesRead = 0L
        record.startRecording()
        try {
            while (!closed.get() && coroutineContext.isActive) {
                val inputIndex = encoder.dequeueInputBuffer(OUTPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = checkNotNull(encoder.getInputBuffer(inputIndex)).apply { clear() }
                    val requested = minOf(input.remaining(), AUDIO_READ_BYTES)
                    val count = record.read(input, requested, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        val ptsUs = samplesRead * 1_000_000L / AUDIO_SAMPLE_RATE
                        samplesRead += count / BYTES_PER_AUDIO_SAMPLE
                        encoder.queueInputBuffer(inputIndex, 0, count, ptsUs, 0)
                    } else {
                        encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
                drainAudioOutput(encoder, info, frames)
            }
        } catch (cause: Throwable) {
            if (!closed.get() && coroutineContext.isActive) fail(cause)
        }
    }

    private fun drainAudioOutput(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        frames: Channel<AudioFrame>,
    ) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            try {
                val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!codecConfig && info.size > 0) {
                    val output = checkNotNull(encoder.getOutputBuffer(index)).slice(info)
                    val accessUnit = ByteArray(info.size).also(output::get)
                    frames.trySend(AudioFrame(info.presentationTimeUs, withAdtsHeader(accessUnit)))
                }
            } finally {
                encoder.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun withAdtsHeader(accessUnit: ByteArray): ByteArray {
        val length = accessUnit.size + ADTS_HEADER_BYTES
        return ByteArray(length).also { output ->
            // MPEG-4, no CRC, AAC-LC, 44.1 kHz (index 4), mono.
            output[0] = 0xFF.toByte()
            output[1] = 0xF1.toByte()
            output[2] = 0x50
            output[3] = (0x40 or (length shr 11)).toByte()
            output[4] = (length shr 3).toByte()
            output[5] = (((length and 7) shl 5) or 0x1F).toByte()
            output[6] = 0xFC.toByte()
            accessUnit.copyInto(output, ADTS_HEADER_BYTES)
        }
    }

    private fun fail(cause: Throwable) {
        if (!closed.get() && failureReported.compareAndSet(false, true)) onFailure(cause)
    }

    private fun releaseCodecs() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        runCatching { videoSurface?.release() }
        videoSurface = null
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val copy = duplicate()
        return ByteArray(copy.remaining()).also(copy::get)
    }

    private fun ByteBuffer.slice(info: MediaCodec.BufferInfo): ByteBuffer = duplicate().apply {
        position(info.offset)
        limit(info.offset + info.size)
    }

    companion object {
        const val FACING_FRONT = "front"
        const val FACING_BACK = "back"
        private const val PREFERRED_WIDTH = 1280
        private const val PREFERRED_HEIGHT = 720
        private const val FRAME_RATE = 30
        private const val VIDEO_BITRATE_BPS = 4_000_000
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val MAX_VIDEO_FRAME_BYTES = 3 * 1024 * 1024
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE_BPS = 96_000
        private const val AUDIO_READ_BYTES = 4096
        private const val BYTES_PER_AUDIO_SAMPLE = 2
        private const val ADTS_HEADER_BYTES = 7
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val CAMERA_UNBIND_TIMEOUT_MS = 1_000L
        private const val CODEC_DRAIN_STOP_TIMEOUT_MS = 1_000L
        private const val SENDER_STOP_TIMEOUT_MS = 1_000L
    }
}
