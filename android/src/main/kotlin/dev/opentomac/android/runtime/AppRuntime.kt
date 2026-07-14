package dev.opentomac.android.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import dev.opentomac.android.platform.AndroidClipboard
import dev.opentomac.android.platform.AndroidKeyValueStore
import dev.opentomac.android.platform.AndroidMediaSource
import dev.opentomac.android.platform.AndroidNotificationSource
import dev.opentomac.shared.clipboard.ClipboardSync
import dev.opentomac.shared.crypto.Identity
import dev.opentomac.shared.media.MediaAgent
import dev.opentomac.shared.media.MediaSource
import dev.opentomac.shared.notifications.NotificationAgent
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
import dev.opentomac.shared.protocol.MediaListRequest
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.MediaItem
import dev.opentomac.shared.protocol.NotificationAction
import dev.opentomac.shared.protocol.NotificationDismissed
import dev.opentomac.shared.protocol.NotificationPosted
import dev.opentomac.shared.protocol.RevokeDevice
import dev.opentomac.shared.protocol.ThumbnailRequest
import dev.opentomac.shared.session.ConnectionState
import dev.opentomac.shared.session.SessionManager
import dev.opentomac.shared.session.TransportFactory
import dev.opentomac.shared.transfer.OfferDecision
import dev.opentomac.shared.transfer.SourceFile
import dev.opentomac.shared.transfer.TransferEngine
import dev.opentomac.shared.transport.TcpServer
import dev.opentomac.shared.transport.TcpTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

object AppRuntime {
    private const val DEFAULT_PORT = 42_420
    private const val PUBLIC_KEY = "identity/public"
    private const val SECRET_KEY = "identity/secret"
    private const val ENDPOINT_PREFIX = "endpoint/"

    private val initMutex = Mutex()
    private var initialized = false
    private var scope: CoroutineScope? = null
    private var kv: AndroidKeyValueStore? = null
    private var trustStore: PersistentTrustStore? = null
    private var pairingManager: PairingManager? = null
    private var sessionManager: SessionManager? = null
    private var clipboard: AndroidClipboard? = null
    private var clipboardSync: ClipboardSync? = null
    private var transferEngine: TransferEngine? = null
    private var notificationAgent: NotificationAgent? = null
    private var mediaAgent: MediaAgent? = null
    private var mediaSource: MediaSource? = null
    private var transportFactory: ConfigurableTransportFactory? = null
    private var pendingPairing: PendingPairing? = null
    private var pendingEndpoint: Endpoint? = null
    private var hostServer: TcpServer? = null
    private val queuedOffers = mutableListOf<List<SourceFile>>()

    private val mutableConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired)
    val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()

    private val mutablePairedDevices = MutableStateFlow<List<TrustedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<TrustedDevice>> = mutablePairedDevices.asStateFlow()

    private val mutablePairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = mutablePairingState.asStateFlow()

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    private val mutableMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = mutableMediaItems.asStateFlow()

    suspend fun initialize(context: Context, ownerScope: CoroutineScope) {
        initMutex.withLock {
            if (initialized) return
            val appContext = context.applicationContext
            scope = ownerScope
            val store = AndroidKeyValueStore(appContext).also { kv = it }
            val identity = loadIdentity(store)
            val trusted = PersistentTrustStore(store).also { trustStore = it }
            val pairing = PairingManager(identity, trusted).also { pairingManager = it }
            val configurableFactory = ConfigurableTransportFactory().also { transportFactory = it }
            val session = SessionManager(
                identity = identity,
                pairingManager = pairing,
                trustStore = trusted,
                transportFactory = configurableFactory,
                clock = SystemClock,
                scope = ownerScope,
            ).also { sessionManager = it }
            val localClipboard = AndroidClipboard(appContext).also { clipboard = it }
            val sync = ClipboardSync(
                deviceId = identity.deviceId,
                local = localClipboard,
                send = { safeSend(ChannelId.EVENT, it) },
                clock = SystemClock,
                historyLimit = 20,
            ).also { clipboardSync = it }
            val transfer = TransferEngine(
                destinationDir = receiveDirectory(appContext).absolutePath.toPath(),
                destinationFileSystem = FileSystem.SYSTEM,
                outbound = { safeSend(ChannelId.BULK, it) },
                onOffer = { _, files, _ ->
                    OfferDecision(true, files.map { DuplicatePolicy.KEEP_BOTH })
                },
                scope = ownerScope,
            ).also { transferEngine = it }
            val notifications = NotificationAgent(
                source = AndroidNotificationSource(),
                send = { safeSend(ChannelId.EVENT, it) },
                ownPackageId = appContext.packageName,
            ).also { notificationAgent = it }
            val androidMedia = AndroidMediaSource(appContext).also { mediaSource = it }
            val media = MediaAgent(androidMedia) {
                safeSend(ChannelId.BULK, it)
            }.also { mediaAgent = it }

            session.registerHandler(ChannelId.EVENT) { envelope ->
                when (val message = envelope.payload) {
                    is ClipboardItemMsg -> sync.onRemoteItem(message)
                    else -> notifications.onMessage(message)
                }
            }
            session.registerHandler(ChannelId.CONTROL) { envelope ->
                notifications.onMessage(envelope.payload)
                media.onMessage(envelope.payload)
            }
            session.registerHandler(ChannelId.BULK) { envelope ->
                transfer.onMessage(envelope.payload)
                media.onMessage(envelope.payload)
            }
            sync.start(ownerScope)
            notifications.start(ownerScope)
            refreshDevices()
            ownerScope.launch {
                session.state.collect { state ->
                    mutableConnectionState.value = if (
                        state == ConnectionState.Idle && mutablePairedDevices.value.isEmpty()
                    ) {
                        ConnectionState.Unpaired
                    } else {
                        state
                    }
                    if (state is ConnectionState.Connected) flushQueuedOffers()
                }
            }
            initialized = true
            mutableReady.value = true
        }
    }

    suspend fun awaitReady() {
        ready.first { it }
    }

    suspend fun connect(device: TrustedDevice) = runCatchingAction("Could not connect") {
        withContext(Dispatchers.IO) {
            val endpointBytes = requireNotNull(kv).get("$ENDPOINT_PREFIX${device.deviceId}")
                ?: error("No saved network address for ${device.displayName}. Pair it again.")
            val endpoint = Endpoint.parse(endpointBytes.decodeToString())
            requireNotNull(transportFactory).configure(endpoint)
            requireNotNull(sessionManager).connect(device)
        }
    }

    suspend fun disconnect() {
        sessionManager?.disconnect()
    }

    suspend fun forget(device: TrustedDevice) = runCatchingAction("Could not forget device") {
        withContext(Dispatchers.IO) {
            safeSend(ChannelId.CONTROL, RevokeDevice(device.deviceId))
            requireNotNull(pairingManager).revoke(device.deviceId)
            requireNotNull(kv).remove("$ENDPOINT_PREFIX${device.deviceId}")
            refreshDevices()
        }
    }

    suspend fun joinPairing(encodedPayload: String) {
        mutablePairingState.value = PairingState.Working("Contacting device")
        try {
            val payload = PairingPayload.fromBase64(encodedPayload.trim())
            val endpoint = payload.addresses.firstNotNullOfOrNull { address ->
                runCatching { Endpoint.parse(address) }.getOrNull()
            } ?: error("Pairing code has no usable TCP address")
            val pending = withContext(Dispatchers.IO) {
                val transport = TcpTransportFactory(endpoint.host, endpoint.port).connect()
                try {
                    requireNotNull(pairingManager).join(payload, transport)
                } finally {
                    transport.close()
                }
            }
            pendingPairing = pending
            pendingEndpoint = endpoint
            mutablePairingState.value = PairingState.Verification(pending.verificationCode)
        } catch (cause: Throwable) {
            pendingPairing = null
            pendingEndpoint = null
            mutablePairingState.value = PairingState.Error(cause.userMessage())
        }
    }

    suspend fun confirmPairing() {
        val pending = pendingPairing ?: return
        mutablePairingState.value = PairingState.Working("Saving trusted device")
        try {
            val device = withContext(Dispatchers.IO) {
                val shortId = pending.peerIdentityKey.take(3).joinToString("") { "%02x".format(it) }
                pending.confirm("Desktop $shortId", "desktop").also { confirmed ->
                    pendingEndpoint?.let { endpoint ->
                        requireNotNull(kv).put(
                            "$ENDPOINT_PREFIX${confirmed.deviceId}",
                            endpoint.encoded().encodeToByteArray(),
                        )
                    }
                }
            }
            pendingPairing = null
            pendingEndpoint = null
            refreshDevices()
            mutablePairingState.value = PairingState.Complete(device.displayName)
        } catch (cause: Throwable) {
            mutablePairingState.value = PairingState.Error(cause.userMessage())
        }
    }

    fun rejectPairing() {
        runCatching { pendingPairing?.reject() }
        pendingPairing = null
        pendingEndpoint = null
        mutablePairingState.value = PairingState.Idle
    }

    fun resetPairing() {
        if (mutablePairingState.value !is PairingState.Working) {
            mutablePairingState.value = PairingState.Idle
        }
    }

    suspend fun startHosting(): String {
        val server = TcpServer(DEFAULT_PORT)
        server.start()
        hostServer?.close()
        hostServer = server
        val addresses = localAddresses().map { "$it:${server.boundPort}" }
        val payload = requireNotNull(pairingManager).startHosting(addresses)
        mutablePairingState.value = PairingState.Working("Waiting for device")
        requireNotNull(scope).launch {
            val transport = runCatching { server.accept() }.getOrElse {
                mutablePairingState.value = PairingState.Error(it.userMessage())
                return@launch
            }
            try {
                pendingPairing = requireNotNull(pairingManager).awaitPairing(transport)
                mutablePairingState.value = PairingState.Verification(
                    requireNotNull(pendingPairing).verificationCode,
                )
            } catch (cause: Throwable) {
                mutablePairingState.value = PairingState.Error(cause.userMessage())
            } finally {
                transport.close()
                server.close()
                if (hostServer === server) hostServer = null
            }
        }
        return payload.toBase64()
    }

    suspend fun sendClipboard(): Boolean {
        val sent = clipboard?.sendCurrent() == true
        mutableNotice.value = if (sent) "Clipboard sent" else "Clipboard is empty"
        return sent
    }

    suspend fun sendText(text: String): Boolean {
        val sent = clipboard?.sendText(text) == true
        mutableNotice.value = if (sent) "Text sent" else "No text to send"
        return sent
    }

    suspend fun enqueueSharedUris(context: Context, uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        val sources = withContext(Dispatchers.IO) { uris.mapNotNull { stageUri(context, it) } }
        if (sources.isEmpty()) return 0
        if (connectionState.value is ConnectionState.Connected) {
            requireNotNull(transferEngine).offer(sources)
            mutableNotice.value = "Sending ${sources.size} item${if (sources.size == 1) "" else "s"}"
        } else {
            synchronized(queuedOffers) { queuedOffers += sources }
            mutableNotice.value = "Queued ${sources.size} item${if (sources.size == 1) "" else "s"}"
        }
        return sources.size
    }

    suspend fun loadMedia(bucket: String = "images") {
        runCatching {
            mutableMediaItems.value = requireNotNull(mediaSource).list(bucket, 0, 40).first
        }.onFailure {
            mutableNotice.value = "Could not browse photos: ${it.userMessage()}"
        }
    }

    suspend fun thumbnail(mediaId: String): ByteArray? = mediaSource?.thumbnail(mediaId)

    fun clearNotice() {
        mutableNotice.value = null
    }

    fun shutdown() {
        clipboardSync?.stop()
        notificationAgent?.stop()
        hostServer?.close()
        scope?.cancel()
        mutableReady.value = false
        initialized = false
    }

    private suspend fun loadIdentity(store: AndroidKeyValueStore): Identity {
        val publicKey = store.get(PUBLIC_KEY)
        val secretKey = store.get(SECRET_KEY)
        if (publicKey != null && secretKey != null) return Identity.fromKeys(publicKey, secretKey)
        val identity = Identity.generate()
        store.put(PUBLIC_KEY, identity.publicKey)
        store.put(SECRET_KEY, identity.secretKey)
        return identity
    }

    private suspend fun refreshDevices() {
        val devices = trustStore?.list().orEmpty()
        mutablePairedDevices.value = devices
        if (devices.isEmpty() && sessionManager?.state?.value == ConnectionState.Idle) {
            mutableConnectionState.value = ConnectionState.Unpaired
        }
    }

    private suspend fun safeSend(channel: ChannelId, message: Message) {
        if (sessionManager?.state?.value !is ConnectionState.Connected) return
        runCatching { requireNotNull(sessionManager).send(channel, message) }
    }

    private suspend fun flushQueuedOffers() {
        val offers = synchronized(queuedOffers) {
            queuedOffers.toList().also { queuedOffers.clear() }
        }
        offers.forEach { files ->
            runCatching { transferEngine?.offer(files) }
                .onFailure { synchronized(queuedOffers) { queuedOffers += files } }
        }
    }

    private suspend fun runCatchingAction(prefix: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { mutableNotice.value = "$prefix: ${it.userMessage()}" }
    }

    private fun receiveDirectory(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "received")

    private fun stageUri(context: Context, uri: Uri): SourceFile? {
        val resolver = context.contentResolver
        var displayName = "shared-${System.currentTimeMillis()}"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        val stagingDir = File(context.cacheDir, "outbox").apply { mkdirs() }
        val target = uniqueFile(stagingDir, safeName)
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use(input::copyTo)
        } ?: return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return SourceFile(
            path = target.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
            meta = FileMeta(
                name = displayName,
                sizeBytes = if (declaredSize >= 0) declaredSize else target.length(),
                mimeType = mime,
            ),
        )
    }

    private fun uniqueFile(directory: File, name: String): File {
        var candidate = File(directory, name)
        var suffix = 2
        while (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            candidate = File(directory, "$stem ($suffix)$extension")
            suffix++
        }
        return candidate
    }

    private fun localAddresses(): List<String> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList()).ifEmpty { listOf("127.0.0.1") }

    private fun Throwable.userMessage(): String = message ?: this::class.simpleName ?: "Unknown error"

    private data class Endpoint(val host: String, val port: Int) {
        fun encoded(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

        companion object {
            fun parse(value: String): Endpoint {
                val trimmed = value.trim()
                val uriValue = if (trimmed.contains("://")) trimmed else "tcp://$trimmed"
                val uri = Uri.parse(uriValue)
                val host = uri.host?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Invalid address '$value'")
                val port = if (uri.port > 0) uri.port else DEFAULT_PORT
                return Endpoint(host, port)
            }
        }
    }

    private class ConfigurableTransportFactory : TransportFactory {
        @Volatile
        private var endpoint: Endpoint? = null

        fun configure(endpoint: Endpoint) {
            this.endpoint = endpoint
        }

        override suspend fun connect() = endpoint?.let { TcpTransportFactory(it.host, it.port).connect() }
            ?: error("No peer network address configured")
    }
}

sealed interface PairingState {
    data object Idle : PairingState
    data class Working(val message: String) : PairingState
    data class Verification(val code: String) : PairingState
    data class Complete(val deviceName: String) : PairingState
    data class Error(val message: String) : PairingState
}
