@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.opentomac.shared.clipboard

import com.ionspin.kotlin.crypto.generichash.GenericHash
import dev.opentomac.shared.crypto.ensureLibsodiumInitialized
import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.protocol.ClipboardItemMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Clipboard payload types supported by the MVP. */
enum class ClipType {
    TEXT,
    URL,
    IMAGE,
}

/**
 * One clipboard value with its stable BLAKE2b-32 content identity. Byte arrays are
 * defensively copied so callers cannot invalidate the hash after creation.
 */
class ClipItem private constructor(
    val type: ClipType,
    payload: ByteArray,
    val sensitive: Boolean,
    contentHash: ByteArray,
) {
    private val storedPayload = payload
    private val storedContentHash = contentHash

    val payload: ByteArray
        get() = storedPayload.copyOf()

    val contentHash: ByteArray
        get() = storedContentHash.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipItem) return false
        return type == other.type &&
            storedPayload.contentEquals(other.storedPayload) &&
            sensitive == other.sensitive &&
            storedContentHash.contentEquals(other.storedContentHash)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + storedPayload.contentHashCode()
        result = 31 * result + sensitive.hashCode()
        result = 31 * result + storedContentHash.contentHashCode()
        return result
    }

    companion object {
        private const val CONTENT_HASH_BYTES = 32

        /** Creates an item after libsodium is ready and hashes a snapshot of [payload]. */
        suspend fun create(
            type: ClipType,
            payload: ByteArray,
            sensitive: Boolean = false,
        ): ClipItem {
            ensureLibsodiumInitialized()
            val payloadSnapshot = payload.copyOf()
            val hash = GenericHash.genericHash(
                message = payloadSnapshot.toUByteArray(),
                requestedHashLength = CONTENT_HASH_BYTES,
            ).toByteArray()
            return ClipItem(type, payloadSnapshot, sensitive, hash)
        }
    }
}

/** Platform clipboard bridge; applying a remote item may re-emit it from [changes]. */
interface LocalClipboard {
    fun changes(): Flow<ClipItem>

    suspend fun apply(item: ClipItem)
}

/**
 * Bidirectional clipboard engine with content-hash loop suppression, per-origin
 * sequence filtering, sensitive-content exclusion, and an optional local history.
 */
class ClipboardSync(
    private val deviceId: String,
    private val local: LocalClipboard,
    private val send: suspend (ClipboardItemMsg) -> Unit,
    private val clock: Clock,
    historyLimit: Int? = null,
) {
    val history = ClipboardHistory(historyLimit)

    private val mutablePaused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = mutablePaused.asStateFlow()

    private val lastSeenSequenceByOrigin = mutableMapOf<String, Long>()
    private var lastAppliedRemoteHash: ByteArray? = null
    private var nextSequence = 0L
    private var collectionJob: Job? = null

    /** Starts watching local changes; calling start while already running is a no-op. */
    fun start(scope: CoroutineScope) {
        if (collectionJob?.isActive == true) return
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            local.changes().collect { item ->
                onLocalItem(item)
            }
        }
    }

    /** Stops watching local changes. Inbound items remain accepted. */
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    fun pause() {
        mutablePaused.value = true
    }

    fun resume() {
        mutablePaused.value = false
    }

    /** Applies a new in-order peer item unless it is an echo from this device. */
    suspend fun onRemoteItem(msg: ClipboardItemMsg) {
        if (msg.originDeviceId == deviceId) return
        val lastSeen = lastSeenSequenceByOrigin[msg.originDeviceId]
        if (lastSeen != null && msg.seq <= lastSeen) return

        val item = ClipItem.create(
            type = ClipType.valueOf(msg.type),
            payload = msg.payloadBytes,
            sensitive = msg.sensitive,
        )
        lastSeenSequenceByOrigin[msg.originDeviceId] = msg.seq
        lastAppliedRemoteHash = msg.contentHash.copyOf()
        local.apply(item)
        history.record(item)
    }

    private suspend fun onLocalItem(item: ClipItem) {
        if (paused.value || item.sensitive) return
        if (lastAppliedRemoteHash?.contentEquals(item.contentHash) == true) return

        val sequence = ++nextSequence
        val message = ClipboardItemMsg(
            itemId = "$deviceId-${clock.nowMs()}-$sequence-${Random.nextLong()}",
            originDeviceId = deviceId,
            seq = sequence,
            type = item.type.name,
            contentHash = item.contentHash,
            payloadBytes = item.payload,
            sensitive = item.sensitive,
        )
        send(message)
        history.record(item)
    }
}
