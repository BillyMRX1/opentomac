package dev.opentomac.shared.mac

import dev.opentomac.shared.clipboard.ClipboardSync
import dev.opentomac.shared.crypto.Identity
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
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.NotificationPosted
import dev.opentomac.shared.protocol.RevokeDevice
import dev.opentomac.shared.session.ConnectionState
import dev.opentomac.shared.session.SessionManager
import dev.opentomac.shared.session.TransportFactory
import dev.opentomac.shared.transfer.OfferDecision
import dev.opentomac.shared.transfer.SourceFile
import dev.opentomac.shared.transfer.TransferEngine
import dev.opentomac.shared.transport.TcpServer
import dev.opentomac.shared.transport.TcpTransportFactory
import dev.opentomac.shared.protocol.FrameTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSHost

/** Pairing progress surfaced to the Swift UI. */
data class MacPairingState(
    val phase: String,
    val code: String? = null,
    val message: String? = null,
)

/**
 * Kotlin orchestrator for the macOS app. It owns the identity, trust store, session,
 * and feature engines, keeping all coroutine, Flow, and suspend interaction in Kotlin
 * and exposing a plain callback API to SwiftUI. Callbacks may fire on a background
 * thread; the Swift layer marshals them to the main queue.
 */
@OptIn(ExperimentalForeignApi::class)
class MacController(
    private val onState: (String) -> Unit,
    private val onPairing: (MacPairingState) -> Unit,
    private val onDevices: (List<TrustedDevice>) -> Unit,
    private val onNotification: (String, String, String) -> Unit,
) {
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
    private val transportFactory = ConfigurableTransportFactory()

    private var pendingPairing: PendingPairing? = null
    private var sessionServer: TcpServer? = null
    private var boundPort: Int = 0

    @Volatile
    private var hostingActive = false

    fun start() {
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
                        onNotification(posted.appName + ": " + posted.title, posted.body, posted.key)
                    }

                    override suspend fun withdraw(key: String) {}
                },
                send = { safeSend(ChannelId.EVENT, it) },
            )

            sessionManager.registerHandler(ChannelId.EVENT) { envelope ->
                when (val message = envelope.payload) {
                    is ClipboardItemMsg -> clipboardSync.onRemoteItem(message)
                    else -> notifications.onMessage(message)
                }
            }
            sessionManager.registerHandler(ChannelId.CONTROL) { envelope ->
                notifications.onMessage(envelope.payload)
            }
            sessionManager.registerHandler(ChannelId.BULK) { envelope ->
                transferEngine.onMessage(envelope.payload)
            }

            clipboardSync.start(scope)
            refreshDevices()
            scope.launch {
                sessionManager.state.collect { onState(it.describe()) }
            }
            startAcceptLoop()
        }
    }

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

    private fun receiveDirectory() =
        "${NSHomeDirectory()}/Downloads/opentomac".toPath()

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
