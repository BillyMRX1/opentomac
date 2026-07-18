package dev.opentomac.shared.mac

import dev.opentomac.shared.clipboard.ClipboardSync
import dev.opentomac.shared.contacts.ContactsCompanion
import dev.opentomac.shared.crypto.Identity
import dev.opentomac.shared.media.MediaCompanionBrowser
import dev.opentomac.shared.notifications.NotificationCompanion
import dev.opentomac.shared.notifications.NotificationPresenter
import dev.opentomac.shared.pairing.PairingManager
import dev.opentomac.shared.pairing.PairingPayload
import dev.opentomac.shared.pairing.PendingPairing
import dev.opentomac.shared.pairing.PersistentTrustStore
import dev.opentomac.shared.pairing.SystemClock
import dev.opentomac.shared.pairing.TrustedDevice
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.ClipboardItemMsg
import dev.opentomac.shared.protocol.DuplicatePolicy
import dev.opentomac.shared.protocol.FileMeta
import dev.opentomac.shared.protocol.MediaControl
import dev.opentomac.shared.protocol.MediaFetchRequest
import dev.opentomac.shared.protocol.MediaNowPlaying
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.MirrorRequest
import dev.opentomac.shared.protocol.MirrorStop
import dev.opentomac.shared.protocol.NotificationPosted
import dev.opentomac.shared.protocol.OpenUrl
import dev.opentomac.shared.protocol.RevokeDevice
import dev.opentomac.shared.protocol.ScreenshotTaken
import dev.opentomac.shared.protocol.VideoConfig
import dev.opentomac.shared.protocol.VideoFrame
import dev.opentomac.shared.session.ConnectionState
import dev.opentomac.shared.session.SessionLog
import dev.opentomac.shared.session.SessionManager
import dev.opentomac.shared.session.TransportFactory
import dev.opentomac.shared.transfer.OfferDecision
import dev.opentomac.shared.transfer.SourceFile
import dev.opentomac.shared.transfer.TransferDirection
import dev.opentomac.shared.transfer.TransferEngine
import dev.opentomac.shared.transport.TcpServer
import dev.opentomac.shared.transport.TcpTransportFactory
import dev.opentomac.shared.protocol.FrameTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSHost
import platform.Foundation.NSData
import platform.Foundation.create

/** Pairing progress surfaced to the Swift UI. */
data class MacPairingState(
    val phase: String,
    val code: String? = null,
    val message: String? = null,
)

/** One send or receive transfer, flattened for the Swift UI. */
data class MacTransfer(
    val id: String,
    val name: String,
    val isReceive: Boolean,
    val state: String,
    val percent: Int,
)

/** One browsable phone photo, for the Swift grid. */
data class MacPhoto(
    val id: String,
    val name: String,
)

/** One phone contact search result, flattened for the Swift UI. */
data class MacContact(
    val name: String,
    val phones: List<String>,
    val emails: List<String>,
)

/**
 * Kotlin orchestrator for the macOS app. It owns the identity, trust store, session,
 * and feature engines, keeping all coroutine, Flow, and suspend interaction in Kotlin
 * and exposing a plain callback API to SwiftUI. Callbacks may fire on a background
 * thread; the Swift layer routes UI state to main and video to its renderer queue.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class MacController(
    private val onState: (String) -> Unit,
    private val onPairing: (MacPairingState) -> Unit,
    private val onDevices: (List<TrustedDevice>) -> Unit,
    private val onNotification: (String, String, String, Int) -> Unit,
    private val onTransfers: (List<MacTransfer>) -> Unit,
    private val onPhotos: (List<MacPhoto>) -> Unit,
    private val onOpenUrl: (String) -> Unit,
    private val onNowPlaying: (String, String, String, Boolean, Boolean) -> Unit,
    private val onScreenshotTaken: (String, String) -> Unit,
    private val onVideoConfig: (Int, Int, NSData, NSData, Int) -> Unit,
    private val onVideoFrame: (NSData, Long, Boolean) -> Unit,
    private val onMirrorStopped: (String) -> Unit,
) {
    private val collectedJobs = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val port = 42_420

    private lateinit var identity: Identity
    private lateinit var trustStore: PersistentTrustStore
    private lateinit var pairingManager: PairingManager
    private lateinit var sessionManager: SessionManager
    private lateinit var clipboard: MacClipboard
    private lateinit var clipboardSync: ClipboardSync
    private lateinit var transferEngine: TransferEngine
    private lateinit var notifications: NotificationCompanion
    private lateinit var mediaBrowser: MediaCompanionBrowser
    private lateinit var contactsCompanion: ContactsCompanion
    private val transportFactory = ConfigurableTransportFactory()

    private var pendingPairing: PendingPairing? = null
    private var sessionServer: TcpServer? = null
    private var boundPort: Int = 0

    @Volatile
    private var hostingActive = false

    @Volatile
    private var mirrorLive = false

    fun start() {
        // Session-level diagnostics (dropped envelopes, handler exceptions) were
        // silently discarded before this sink existed; stdout shows in terminal runs.
        SessionLog.sink = { println("opentomac session: $it") }
        scope.launch {
            val kv = MacKeyValueStore.default()
            identity = loadIdentity(kv)
            trustStore = PersistentTrustStore(kv)
            pairingManager = PairingManager(identity, trustStore)
            sessionManager = SessionManager(
                identity = identity,
                pairingManager = pairingManager,
                trustStore = trustStore,
                transportFactory = transportFactory,
                clock = SystemClock,
                scope = scope,
            )
            clipboard = MacClipboard()
            clipboardSync = ClipboardSync(
                deviceId = identity.deviceId,
                local = clipboard,
                send = { safeSend(ChannelId.EVENT, it) },
                clock = SystemClock,
                historyLimit = 20,
            )
            transferEngine = TransferEngine(
                destinationDir = receiveDirectory(),
                destinationFileSystem = FileSystem.SYSTEM,
                outbound = { safeSend(ChannelId.BULK, it) },
                onOffer = { _, files, _ -> OfferDecision(true, files.map { DuplicatePolicy.KEEP_BOTH }) },
                scope = scope,
            )
            notifications = NotificationCompanion(
                presenter = object : NotificationPresenter {
                    override suspend fun present(posted: NotificationPosted) {
                        val replyIndex = posted.actions.firstOrNull { it.isRemoteInput }?.index ?: -1
                        val title = if (posted.title.isBlank()) posted.appName else "${posted.appName}: ${posted.title}"
                        onNotification(title, posted.body, posted.key, replyIndex)
                    }

                    override suspend fun withdraw(key: String) {}
                },
                send = { safeSend(ChannelId.EVENT, it) },
            )
            mediaBrowser = MediaCompanionBrowser(send = { safeSend(ChannelId.BULK, it) })
            contactsCompanion = ContactsCompanion(send = { safeSend(ChannelId.BULK, it) })

            sessionManager.registerHandler(ChannelId.EVENT) { envelope ->
                when (val message = envelope.payload) {
                    is ClipboardItemMsg -> clipboardSync.onRemoteItem(message)
                    is MediaNowPlaying -> onNowPlaying(
                        message.appName,
                        message.title,
                        message.artist,
                        message.isPlaying,
                        message.hasSession,
                    )
                    is OpenUrl -> normalizedWebUrl(message.url)?.let(onOpenUrl)
                    is ScreenshotTaken -> onScreenshotTaken(message.mediaId, message.name)
                    is MirrorStop -> {
                        println("opentomac mirror: phone stopped (${message.reason})")
                        mirrorLive = false
                        onMirrorStopped(message.reason)
                    }
                    else -> {
                        println("opentomac EVENT: ${message::class.simpleName}")
                        notifications.onMessage(message)
                    }
                }
            }
            sessionManager.registerHandler(ChannelId.CONTROL) { envelope ->
                notifications.onMessage(envelope.payload)
            }
            sessionManager.registerHandler(ChannelId.BULK) { envelope ->
                transferEngine.onMessage(envelope.payload)
                mediaBrowser.onMessage(envelope.payload)
                contactsCompanion.onMessage(envelope.payload)
            }
            sessionManager.registerHandler(ChannelId.VIDEO) { envelope ->
                when (val message = envelope.payload) {
                    is VideoConfig -> {
                        println(
                            "opentomac mirror: video ${message.width}x${message.height} " +
                                "at ${message.frameRate} fps",
                        )
                        mirrorLive = true
                        onVideoConfig(
                            message.width,
                            message.height,
                            message.csd0.toNSData(),
                            message.csd1.toNSData(),
                            message.frameRate,
                        )
                    }
                    is VideoFrame -> onVideoFrame(message.data.toNSData(), message.ptsUs, message.keyframe)
                    else -> println("opentomac VIDEO: ${message::class.simpleName}")
                }
            }

            clipboardSync.start(scope)
            refreshDevices()
            scope.launch {
                sessionManager.state.collect { state ->
                    // A restarted phone's sequence counter starts over; drop the old
                    // replay watermark or its items are silently discarded.
                    if (state is ConnectionState.Connected) clipboardSync.onSessionEstablished()
                    // A dropped session cannot deliver MirrorStop: end the viewer
                    // locally or it freezes on the last frame claiming to be live.
                    if (state !is ConnectionState.Connected && mirrorLive) {
                        mirrorLive = false
                        onMirrorStopped("disconnected")
                    }
                    onState(state.describe())
                }
            }
            observeTransfers()
            startAcceptLoop()
        }
    }

    private fun observeTransfers() {
        scope.launch {
            transferEngine.transfers.collect { list ->
                list.forEach { job ->
                    if (collectedJobs.add(job.jobId)) {
                        scope.launch { job.progress.collect { pushTransfers() } }
                    }
                }
                pushTransfers()
            }
        }
    }

    private fun pushTransfers() {
        onTransfers(
            transferEngine.transfers.value.map { job ->
                val p = job.progress.value
                val percent = if (p.totalBytes > 0) (p.completedBytes * 100 / p.totalBytes).toInt() else 0
                MacTransfer(
                    id = job.jobId,
                    name = job.files.firstOrNull()?.name ?: "files",
                    isReceive = job.direction == TransferDirection.RECEIVE,
                    state = p.state.name,
                    percent = percent,
                )
            },
        )
    }

    /** Absolute path of the folder where received files are saved. */
    fun receiveDirectoryPath(): String = "${NSHomeDirectory()}/Downloads/opentomac"

    /**
     * One persistent listener serves both roles: an armed pairing (via [startHosting])
     * claims the next inbound connection; every other connection is an incoming session
     * from an already-trusted peer and is handed to the session manager.
     */
    private fun startAcceptLoop() {
        scope.launch {
            val server = try {
                TcpServer(port).also { it.start() }
            } catch (cause: Throwable) {
                onState("Listen port $port unavailable: ${cause.message}")
                return@launch
            }
            sessionServer = server
            boundPort = server.boundPort
            while (true) {
                val transport = try {
                    server.accept()
                } catch (cause: Throwable) {
                    break
                }
                scope.launch { handleAccepted(transport) }
            }
        }
    }

    private suspend fun handleAccepted(transport: FrameTransport) {
        if (hostingActive) {
            hostingActive = false
            try {
                val pending = pairingManager.awaitPairing(transport)
                pendingPairing = pending
                onPairing(MacPairingState(phase = "verify", code = pending.verificationCode))
            } catch (cause: Throwable) {
                onPairing(MacPairingState(phase = "error", message = cause.message ?: "Pairing failed"))
            } finally {
                transport.close()
            }
        } else {
            try {
                sessionManager.listen(transport)
            } catch (cause: Throwable) {
                transport.close()
            }
        }
    }

    fun startHosting() {
        scope.launch {
            try {
                val listenPort = if (boundPort != 0) boundPort else port
                val addresses = localAddresses().map { "$it:$listenPort" }
                val payload = pairingManager.startHosting(addresses)
                hostingActive = true
                onPairing(MacPairingState(phase = "hosting", code = payload.toBase64()))
            } catch (cause: Throwable) {
                onPairing(MacPairingState(phase = "error", message = cause.message ?: "Cannot host"))
            }
        }
    }

    fun confirmPairing() {
        val pending = pendingPairing ?: return
        scope.launch {
            try {
                pending.confirm("Android device", "android")
                pendingPairing = null
                refreshDevices()
                onPairing(MacPairingState(phase = "done"))
            } catch (cause: Throwable) {
                onPairing(MacPairingState(phase = "error", message = cause.message ?: "Confirm failed"))
            }
        }
    }

    fun rejectPairing() {
        pendingPairing?.reject()
        pendingPairing = null
        onPairing(MacPairingState(phase = "idle"))
    }

    /** Cancels an armed or in-progress host pairing; the session listener keeps running. */
    fun cancelPairing() {
        scope.launch {
            hostingActive = false
            pendingPairing?.reject()
            pendingPairing = null
            pairingManager.cancelHosting()
            onPairing(MacPairingState(phase = "idle"))
        }
    }

    fun forget(deviceId: String) {
        scope.launch {
            safeSend(ChannelId.CONTROL, RevokeDevice(deviceId))
            pairingManager.revoke(deviceId)
            refreshDevices()
        }
    }

    fun sendFiles(paths: List<String>) {
        scope.launch {
            val sources = paths.mapNotNull { pathString ->
                val path = pathString.toPath()
                val meta = runCatching { FileSystem.SYSTEM.metadata(path) }.getOrNull() ?: return@mapNotNull null
                SourceFile(
                    path = path,
                    fileSystem = FileSystem.SYSTEM,
                    meta = FileMeta(
                        name = path.name,
                        sizeBytes = meta.size ?: 0L,
                        mimeType = "application/octet-stream",
                    ),
                )
            }
            if (sources.isNotEmpty() && sessionManager.state.value is ConnectionState.Connected) {
                transferEngine.offer(sources)
            }
        }
    }

    /** Opens an http/https URL on the connected Android phone. */
    fun openUrlOnPhone(url: String) {
        val normalized = normalizedWebUrl(url) ?: return
        scope.launch { safeSend(ChannelId.EVENT, OpenUrl(normalized)) }
    }

    fun mediaControl(command: String) {
        scope.launch { safeSend(ChannelId.EVENT, MediaControl(command)) }
    }

    /** Asks the connected Android device to begin a MediaProjection mirror session. */
    fun requestMirror() {
        println("opentomac mirror: requesting phone screen")
        scope.launch { safeSend(ChannelId.EVENT, MirrorRequest(SystemClock.nowMs())) }
    }

    /** Stops the current mirror session on the connected Android device. */
    fun stopMirror() {
        println("opentomac mirror: stopping at Mac request")
        scope.launch { safeSend(ChannelId.EVENT, MirrorStop("mac stopped")) }
    }

    /** Sends an inline reply back to a mirrored phone notification. */
    fun replyToNotification(key: String, actionIndex: Int, text: String) {
        scope.launch { notifications.sendAction(key, actionIndex, text) }
    }

    /** Requests the first page of phone photos; results arrive via the photos callback. */
    fun loadPhotos() {
        scope.launch {
            runCatching { mediaBrowser.requestPage("images", 0, 60) }
                .onSuccess { response -> onPhotos(response.items.map { MacPhoto(it.mediaId, it.name) }) }
        }
    }

    /** Requests one photo thumbnail; [onResult] receives base64 JPEG/PNG bytes or null. */
    fun requestThumbnail(id: String, onResult: (String?) -> Unit) {
        scope.launch {
            val bytes = runCatching { mediaBrowser.thumbnail(id) }.getOrNull()
            onResult(bytes?.takeIf { it.isNotEmpty() }?.let { Base64.encode(it) })
        }
    }

    /** Searches phone contacts; [onResult]'s Boolean reports phone permission. */
    fun searchContacts(query: String, onResult: (List<MacContact>, Boolean) -> Unit) {
        scope.launch {
            runCatching { contactsCompanion.search(query) }
                .onSuccess { response ->
                    onResult(
                        response.items.map { MacContact(it.name, it.phones, it.emails) },
                        response.granted,
                    )
                }
                .onFailure { cause ->
                    println("opentomac contacts search failed: ${cause.message}")
                    onResult(emptyList(), true)
                }
        }
    }

    /** Cancels an in-progress transfer on either direction; the peer is notified. */
    fun cancelTransfer(jobId: String) {
        scope.launch { transferEngine.cancel(jobId) }
    }

    /** Requests the original phone photo; it arrives through the normal transfer pipeline. */
    fun importPhoto(id: String) {
        scope.launch { safeSend(ChannelId.BULK, MediaFetchRequest(id)) }
    }

    fun diagnostics(): String {
        val state = if (::sessionManager.isInitialized) sessionManager.state.value.describe() else "starting"
        val deviceId = if (::identity.isInitialized) identity.deviceId else "unknown"
        return "State: $state\nDevice: $deviceId\nListen port: $port"
    }

    private suspend fun loadIdentity(kv: MacKeyValueStore): Identity {
        val pub = kv.get("identity/public")
        val sec = kv.get("identity/secret")
        if (pub != null && sec != null) return Identity.fromKeys(pub, sec)
        val fresh = Identity.generate()
        kv.put("identity/public", fresh.publicKey)
        kv.put("identity/secret", fresh.secretKey)
        return fresh
    }

    private suspend fun refreshDevices() {
        onDevices(trustStore.list())
    }

    private suspend fun safeSend(channel: ChannelId, message: Message) {
        if (sessionManager.state.value !is ConnectionState.Connected) return
        runCatching { sessionManager.send(channel, message) }
    }

    private fun normalizedWebUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val delimiter = trimmed.indexOf("://")
        if (delimiter <= 0) return null
        val scheme = trimmed.substring(0, delimiter)
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return null
        }
        val authority = trimmed.substring(delimiter + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return trimmed.takeIf { authority.isNotBlank() }
    }

    private fun receiveDirectory() =
        "${NSHomeDirectory()}/Downloads/opentomac".toPath()

    private fun ByteArray.toNSData(): NSData {
        if (isEmpty()) return NSData()
        return usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

    private fun localAddresses(): List<String> {
        val addresses = NSHost.currentHost().addresses()
        return addresses
            .filterIsInstance<String>()
            .filter { it.count { ch -> ch == '.' } == 3 && !it.startsWith("127.") }
            .distinct()
            .ifEmpty { listOf("127.0.0.1") }
    }

    private fun ConnectionState.describe(): String = when (this) {
        ConnectionState.Unpaired -> "Not paired"
        ConnectionState.Idle -> "Ready"
        is ConnectionState.Connecting -> "Connecting (attempt $attempt)"
        is ConnectionState.Connected -> "Connected to ${peer.displayName}"
        is ConnectionState.Degraded -> "Limited: $reason"
    }

    private class ConfigurableTransportFactory : TransportFactory {
        override suspend fun connect(): FrameTransport =
            throw IllegalStateException("macOS hosts pairing and does not dial out in the MVP")
    }
}
