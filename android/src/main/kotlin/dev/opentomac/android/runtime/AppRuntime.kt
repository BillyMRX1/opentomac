package dev.opentomac.android.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.opentomac.android.R
import dev.opentomac.android.platform.AndroidClipboard
import dev.opentomac.android.platform.AndroidContactsSource
import dev.opentomac.android.platform.AndroidKeyValueStore
import dev.opentomac.android.platform.AndroidMediaSource
import dev.opentomac.android.platform.AndroidNotificationSource
import dev.opentomac.android.platform.MediaRemoteAgent
import dev.opentomac.shared.clipboard.ClipboardSync
import dev.opentomac.shared.contacts.ContactsAgent
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
import dev.opentomac.shared.protocol.MediaControl
import dev.opentomac.shared.protocol.MediaListRequest
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.MediaItem
import dev.opentomac.shared.protocol.NotificationAction
import dev.opentomac.shared.protocol.NotificationDismissed
import dev.opentomac.shared.protocol.NotificationPosted
import dev.opentomac.shared.protocol.OpenUrl
import dev.opentomac.shared.protocol.RevokeDevice
import dev.opentomac.shared.protocol.ScreenshotTaken
import dev.opentomac.shared.protocol.ThumbnailRequest
import dev.opentomac.shared.session.ConnectionState
import dev.opentomac.shared.session.SessionManager
import dev.opentomac.shared.session.TransportFactory
import dev.opentomac.shared.transfer.OfferDecision
import dev.opentomac.shared.transfer.SourceFile
import dev.opentomac.shared.transfer.TransferEngine
import dev.opentomac.shared.transfer.TransferJob
import dev.opentomac.shared.transport.TcpServer
import dev.opentomac.shared.transport.TcpTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private const val AUTO_SEND_SCREENSHOTS_KEY = "settings.autoSendScreenshots"
    private const val LINKS_CHANNEL_ID = "opentomac_links"
    private const val SCREENSHOT_DEBOUNCE_MS = 650L

    private val initMutex = Mutex()
    private var initialized = false
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var kv: AndroidKeyValueStore? = null
    private var trustStore: PersistentTrustStore? = null
    private var pairingManager: PairingManager? = null
    private var sessionManager: SessionManager? = null
    private var clipboard: AndroidClipboard? = null
    private var clipboardSync: ClipboardSync? = null
    private var transferEngine: TransferEngine? = null
    private var notificationAgent: NotificationAgent? = null
    private var contactsAgent: ContactsAgent? = null
    private var mediaAgent: MediaAgent? = null
    private var mediaRemoteAgent: MediaRemoteAgent? = null
    private var mediaSource: MediaSource? = null
    private var transportFactory: ConfigurableTransportFactory? = null
    private var pendingPairing: PendingPairing? = null
    private var pendingEndpoint: Endpoint? = null
    private var hostServer: TcpServer? = null
    private val queuedOffers = mutableListOf<List<SourceFile>>()
    private val screenshotScanMutex = Mutex()
    private var screenshotObserver: ContentObserver? = null
    private var screenshotDebounceJob: Job? = null
    private var lastSeenImageId = 0L

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

    private val mutableAutoSendScreenshots = MutableStateFlow(false)
    val autoSendScreenshots: StateFlow<Boolean> = mutableAutoSendScreenshots.asStateFlow()

    private val mutableMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = mutableMediaItems.asStateFlow()

    private val mutableTransfers = MutableStateFlow<List<TransferJob>>(emptyList())
    val transfers: StateFlow<List<TransferJob>> = mutableTransfers.asStateFlow()

    private var receiveDir: File? = null

    /** Absolute directory where received files land, for opening them from the UI. */
    fun receiveDirectoryFile(): File? = receiveDir

    suspend fun initialize(context: Context, ownerScope: CoroutineScope) {
        initMutex.withLock {
            if (initialized) return
            val appContext = context.applicationContext
            this.appContext = appContext
            scope = ownerScope
            val store = AndroidKeyValueStore(appContext).also { kv = it }
            mutableAutoSendScreenshots.value = store.get(AUTO_SEND_SCREENSHOTS_KEY)
                ?.decodeToString()
                ?.toBooleanStrictOrNull()
                ?: false
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
                // Must THROW on failure (unlike safeSend) so the engine knows the item
                // was not delivered and keeps it eligible for retry/manual send.
                send = { message ->
                    val state = session.state.value
                    Log.w("opentomac", "clipboard send attempt: type=${message.type} state=$state")
                    check(state is ConnectionState.Connected) { "Not connected" }
                    session.send(ChannelId.EVENT, message)
                    Log.w("opentomac", "clipboard send delivered: type=${message.type}")
                },
                clock = SystemClock,
                historyLimit = 20,
            ).also { clipboardSync = it }
            receiveDir = receiveDirectory(appContext)
            // Staged outbox copies have no delivery-tied lifecycle (documented limit);
            // day-old orphans are dead weight and auto-send would otherwise grow the
            // cache without bound. Anything mid-transfer is far younger than a day.
            ownerScope.launch(Dispatchers.IO) {
                runCatching {
                    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                    File(appContext.cacheDir, "outbox").listFiles()
                        ?.filter { it.lastModified() < cutoff }
                        ?.forEach { it.delete() }
                }
            }
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
                send = {
                    Log.w(
                        "opentomac",
                        "notification -> Mac: ${it::class.simpleName} " +
                            "state=${session.state.value::class.simpleName}",
                    )
                    safeSend(ChannelId.EVENT, it)
                },
                ownPackageId = appContext.packageName,
            ).also { notificationAgent = it }
            val contacts = ContactsAgent(
                source = AndroidContactsSource(appContext),
                send = { safeSend(ChannelId.BULK, it) },
            ).also { contactsAgent = it }
            val mediaRemote = MediaRemoteAgent(
                context = appContext,
                scope = ownerScope,
                send = { safeSend(ChannelId.EVENT, it) },
            ).also { mediaRemoteAgent = it }
            val androidMedia = AndroidMediaSource(appContext).also { mediaSource = it }
            val media = MediaAgent(
                source = androidMedia,
                send = { safeSend(ChannelId.BULK, it) },
                // Detached so staging a large original never stalls the session dispatch
                // loop (heartbeats and transfer replies arrive on the same loop).
                fetch = { mediaId ->
                    ownerScope.launch {
                        runCatchingAction("Could not import photo") {
                            val uri = Uri.parse(mediaId)
                            // The peer is authenticated but the URI is wire-supplied: only
                            // MediaStore content is fetchable, never arbitrary providers.
                            require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                                "Refused non-MediaStore photo request"
                            }
                            val sourceFile = withContext(Dispatchers.IO) {
                                requireNotNull(stageUri(appContext, uri)) {
                                    "Could not read the selected photo"
                                }
                            }
                            transfer.offer(listOf(sourceFile))
                        }
                    }
                },
            ).also { mediaAgent = it }

            session.registerHandler(ChannelId.EVENT) { envelope ->
                when (val message = envelope.payload) {
                    is ClipboardItemMsg -> sync.onRemoteItem(message)
                    is MediaControl -> mediaRemote.onControl(message)
                    is OpenUrl -> handleOpenUrlFromPeer(appContext, message.url)
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
                contacts.onMessage(envelope.payload)
            }
            sync.start(ownerScope)
            notifications.start(ownerScope)
            mediaRemote.start()
            ownerScope.launch { transfer.transfers.collect { mutableTransfers.value = it } }
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
                    if (state is ConnectionState.Connected) {
                        // A restarted peer's sequence counter starts over; drop the old
                        // replay watermark or its items are silently discarded.
                        sync.onSessionEstablished()
                        mediaRemote.pushCurrentState()
                        flushQueuedOffers()
                        // The resume-time clipboard read races reconnection: if it lost,
                        // its item was dropped as unsendable. Re-drive it now that the
                        // session is up; dedupe keeps this from resending old content.
                        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            localClipboard.readCurrent()?.let { sync.trySend(it) }
                        }
                    }
                }
            }
            initialized = true
            if (mutableAutoSendScreenshots.value) startScreenshotObserver(appContext)
            mutableReady.value = true
            // Connect to the paired Mac without waiting for a dashboard tap, so entry
            // points that never show the UI (tile, share sheet, text selection,
            // notification mirroring after a process restart) come up working. The
            // session manager keeps reconnecting on its own once this first attempt
            // establishes the session.
            ownerScope.launch {
                val device = mutablePairedDevices.value.firstOrNull() ?: return@launch
                if (session.state.value is ConnectionState.Idle) connect(device)
            }
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

    suspend fun cancelTransfer(jobId: String) {
        transferEngine?.cancel(jobId)
    }

    suspend fun sendClipboard(): Boolean {
        val item = clipboard?.readCurrent()
        if (item == null) {
            mutableNotice.value = "Clipboard is empty or unreadable"
            return false
        }
        return dispatchClipboardItem(item, "Clipboard sent")
    }

    suspend fun sendText(text: String): Boolean {
        val item = clipboard?.textItem(text)
        if (item == null) {
            mutableNotice.value = "No text to send"
            return false
        }
        clipboard?.apply(item)
        return dispatchClipboardItem(item, "Text sent")
    }

    suspend fun openUrlOnPeer(url: String): Boolean {
        val normalized = webUriOrNull(url)?.toString()
        if (normalized == null) {
            mutableNotice.value = "No valid web link to send"
            return false
        }
        val sent = runCatching {
            val session = requireNotNull(sessionManager)
            check(session.state.value is ConnectionState.Connected) { "Not connected" }
            Log.w("opentomac", "URL -> Mac")
            session.send(ChannelId.EVENT, OpenUrl(normalized))
        }.isSuccess
        mutableNotice.value = when {
            sent -> "Link sent"
            connectionState.value !is ConnectionState.Connected -> "Not connected to a device"
            else -> "Could not send link"
        }
        return sent
    }

    fun isHttpUrl(value: String): Boolean = webUriOrNull(value) != null

    suspend fun setAutoSendScreenshots(enabled: Boolean) {
        runCatching {
            requireNotNull(kv).put(AUTO_SEND_SCREENSHOTS_KEY, enabled.toString().encodeToByteArray())
            mutableAutoSendScreenshots.value = enabled
            if (enabled) {
                // The switch must not claim to be on when nothing is watching.
                if (!startScreenshotObserver(requireNotNull(appContext))) {
                    mutableAutoSendScreenshots.value = false
                    requireNotNull(kv).put(AUTO_SEND_SCREENSHOTS_KEY, "false".encodeToByteArray())
                    mutableNotice.value = "Could not watch screenshots. Check photo permission."
                    return
                }
            } else {
                stopScreenshotObserver()
            }
            Log.w("opentomac", "auto-send screenshots ${if (enabled) "enabled" else "disabled"}")
        }.onFailure {
            mutableNotice.value = "Could not update screenshot setting: ${it.userMessage()}"
        }
    }

    private suspend fun dispatchClipboardItem(
        item: dev.opentomac.shared.clipboard.ClipItem,
        successNotice: String,
    ): Boolean {
        val sent = clipboardSync?.sendNow(item) == true
        mutableNotice.value = when {
            sent -> successNotice
            connectionState.value !is ConnectionState.Connected -> "Not connected to a device"
            else -> "Could not send clipboard item"
        }
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
        initialized = false
        stopScreenshotObserver()
        clipboardSync?.stop()
        notificationAgent?.stop()
        mediaRemoteAgent?.stop()
        hostServer?.close()
        scope?.cancel()
        mutableReady.value = false
    }

    private suspend fun startScreenshotObserver(context: Context): Boolean {
        if (!initialized || !mutableAutoSendScreenshots.value) return false
        if (screenshotObserver != null) return true
        val currentMax = runCatching {
            withContext(Dispatchers.IO) { queryCurrentMaxImageId(context) }
        }.onFailure {
            Log.w("opentomac", "could not initialize screenshot observer", it)
        }.getOrNull() ?: return false
        if (!initialized || !mutableAutoSendScreenshots.value) return false
        if (screenshotObserver != null) return true

        lastSeenImageId = currentMax
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                screenshotDebounceJob?.cancel()
                screenshotDebounceJob = scope?.launch {
                    delay(SCREENSHOT_DEBOUNCE_MS)
                    // Clear the debounce handle before scanning so a later observer
                    // callback schedules another scan without cancelling one already
                    // staging an image.
                    screenshotDebounceJob = null
                    screenshotScanMutex.withLock { scanForNewScreenshots(context) }
                }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            screenshotObserver = observer
            Log.w("opentomac", "screenshot observer started at image ID $lastSeenImageId")
        }.onFailure {
            Log.w("opentomac", "could not register screenshot observer", it)
        }
        return screenshotObserver === observer
    }

    private fun stopScreenshotObserver() {
        screenshotDebounceJob?.cancel()
        screenshotDebounceJob = null
        val observer = screenshotObserver ?: return
        screenshotObserver = null
        runCatching { appContext?.contentResolver?.unregisterContentObserver(observer) }
            .onFailure { Log.w("opentomac", "could not unregister screenshot observer", it) }
        Log.w("opentomac", "screenshot observer stopped")
    }

    private suspend fun scanForNewScreenshots(context: Context) {
        if (!mutableAutoSendScreenshots.value) return
        val afterId = lastSeenImageId
        val rows = runCatching {
            withContext(Dispatchers.IO) { queryNewImages(context, afterId) }
        }.onFailure {
            Log.w("opentomac", "could not query new screenshots", it)
        }.getOrNull() ?: return
        if (rows.isEmpty()) return

        lastSeenImageId = maxOf(lastSeenImageId, rows.maxOf { it.id })
        val screenshots = rows.filter { it.isScreenshot }
        if (screenshots.isEmpty()) return
        if (connectionState.value !is ConnectionState.Connected) {
            Log.w("opentomac", "skipping ${screenshots.size} screenshot(s): not connected")
            return
        }

        // Announce only: the Mac shows a notification whose Send action fetches the
        // original through the existing media_fetch_request path. Not every
        // screenshot deserves to leave the phone, so the user decides per shot.
        for (row in screenshots) {
            if (!mutableAutoSendScreenshots.value || connectionState.value !is ConnectionState.Connected) {
                Log.w("opentomac", "skipping screenshot ID ${row.id}: not connected")
                continue
            }
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.id)
            safeSend(ChannelId.EVENT, ScreenshotTaken(uri.toString(), row.name ?: "Screenshot"))
            Log.w("opentomac", "announced screenshot ID ${row.id} to Mac")
        }
    }

    private fun queryCurrentMaxImageId(context: Context): Long {
        val resolver = context.contentResolver
        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun queryNewImages(context: Context, afterId: Long): List<MediaStoreImage> {
        val idColumn = MediaStore.Images.Media._ID
        val bucketColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        val projection = buildList {
            add(idColumn)
            add(bucketColumn)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        val selection = buildString {
            append("$idColumn > ?")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Images.Media.IS_PENDING} = 0")
            }
        }
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(afterId.toString()),
            "$idColumn ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(idColumn)
            val bucketIndex = cursor.getColumnIndex(bucketColumn)
            val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MediaStoreImage(
                            id = cursor.getLong(idIndex),
                            bucket = bucketIndex.takeIf { it >= 0 }?.let(cursor::getString),
                            relativePath = pathIndex.takeIf { it >= 0 }?.let(cursor::getString),
                            name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun handleOpenUrlFromPeer(context: Context, value: String) {
        val uri = webUriOrNull(value)
        if (uri == null) {
            Log.w("opentomac", "refused invalid URL from peer")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (foreground && runCatching { context.startActivity(intent) }.isSuccess) {
            Log.w("opentomac", "opened URL from Mac: host=${uri.host}")
            return
        }
        postOpenUrlNotification(context, uri, intent)
    }

    private fun postOpenUrlNotification(context: Context, uri: Uri, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                LINKS_CHANNEL_ID,
                "Links from Mac",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val requestCode = uri.toString().hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, LINKS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_clipboard)
            .setContentTitle("Open link from Mac")
            .setContentText(uri.host ?: uri.toString())
            .setStyle(NotificationCompat.BigTextStyle().bigText(uri.toString()))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(requestCode, notification) }
            .onSuccess { Log.w("opentomac", "posted URL notification from Mac: host=${uri.host}") }
            .onFailure { Log.w("opentomac", "could not post URL notification", it) }
    }

    private fun webUriOrNull(value: String): Uri? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return null
        }
        return uri.takeIf { !it.host.isNullOrBlank() }
    }

    private data class MediaStoreImage(
        val id: Long,
        val bucket: String?,
        val relativePath: String?,
        val name: String?,
    ) {
        val isScreenshot: Boolean
            get() = bucket?.contains("Screenshots", ignoreCase = true) == true ||
                relativePath?.contains("Screenshots", ignoreCase = true) == true
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
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                }
            }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        val stagingDir = File(context.cacheDir, "outbox").apply { mkdirs() }
        val target = uniqueFile(stagingDir, safeName)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: return null
        } catch (cause: Throwable) {
            target.delete()
            throw cause
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return SourceFile(
            path = target.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
            meta = FileMeta(
                name = displayName,
                // The staged copy is authoritative: a stale provider-declared size would
                // make the transfer engine truncate or under-read the snapshot.
                sizeBytes = target.length(),
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
