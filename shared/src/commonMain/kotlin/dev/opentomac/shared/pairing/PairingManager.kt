package dev.opentomac.shared.pairing

import dev.opentomac.shared.crypto.CryptoException
import dev.opentomac.shared.crypto.Handshake
import dev.opentomac.shared.crypto.HandshakeResult
import dev.opentomac.shared.crypto.Identity
import dev.opentomac.shared.crypto.SessionKeys
import dev.opentomac.shared.crypto.VerificationCode
import dev.opentomac.shared.crypto.deviceIdFor
import dev.opentomac.shared.protocol.FrameTransport
import dev.opentomac.shared.protocol.ProtocolCodec
import dev.opentomac.shared.protocol.ProtocolException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Drives host and joiner pairing roles and owns the local trust decisions. */
class PairingManager(
    private val identity: Identity,
    private val trustStore: TrustStore = InMemoryTrustStore(),
    private val clock: Clock = SystemClock,
) {
    private val hostingMutex = Mutex()
    private var activeToken: PairingToken? = null

    private val mutableRevocations = MutableSharedFlow<String>()

    /** Device IDs revoked locally, used by the future session layer to close live sessions. */
    val revocations: SharedFlow<String> = mutableRevocations.asSharedFlow()

    /** Starts one pending host pairing and returns the payload to render as a QR code. */
    suspend fun startHosting(addresses: List<String>): PairingPayload {
        val token = hostingMutex.withLock {
            val current = activeToken
            if (current != null && !current.consumed && !current.isExpired(clock)) {
                throw IllegalStateException(
                    "Cannot start pairing: another host pairing is still pending until ${current.expiresAtMs}",
                )
            }
            PairingToken.generate(clock).also { activeToken = it }
        }
        return PairingPayload(
            version = ProtocolCodec.PROTOCOL_VERSION,
            publicKey = identity.publicKey.copyOf(),
            addresses = addresses.toList(),
            token = token.bytes.copyOf(),
        )
    }

    /** Discards any pending host token so a new pairing can be started immediately. */
    suspend fun cancelHosting() {
        hostingMutex.withLock { activeToken = null }
    }

    /** Consumes the active host token before accepting exactly one handshake attempt. */
    suspend fun awaitPairing(transport: FrameTransport): PendingPairing {
        val token = hostingMutex.withLock {
            val current = activeToken
                ?: throw CryptoException("Cannot await pairing: no active host pairing token")
            if (current.isExpired(clock)) {
                throw CryptoException(
                    "Cannot await pairing: host token expired at ${current.expiresAtMs} ms " +
                        "(now ${clock.nowMs()} ms)",
                )
            }
            if (!current.consume()) {
                throw CryptoException("Cannot await pairing: host token was already consumed by a handshake attempt")
            }
            current
        }
        val result = Handshake.respond(
            transport = transport,
            identity = identity,
            pairingToken = token.bytes,
            expectedPeerKey = null,
        )
        return pending(result)
    }

    /** Joins a host advertised by [payload] and returns an untrusted result for user confirmation. */
    suspend fun join(payload: PairingPayload, transport: FrameTransport): PendingPairing {
        if (payload.version != ProtocolCodec.PROTOCOL_VERSION) {
            throw ProtocolException(
                "Unsupported pairing payload version ${payload.version}; " +
                    "this build supports version ${ProtocolCodec.PROTOCOL_VERSION}",
            )
        }
        val result = Handshake.initiate(
            transport = transport,
            identity = identity,
            pairingToken = payload.token,
            expectedPeerKey = null,
        )
        return pending(result)
    }

    /** Returns a pinned peer or rejects unknown and revoked identity keys. */
    suspend fun verifyPeer(peerIdentityKey: ByteArray): TrustedDevice =
        trustStore.findByPublicKey(peerIdentityKey)
            ?: throw CryptoException(
                "Peer identity key is not trusted locally; the device is unknown or has been revoked",
            )

    /** Removes local trust and notifies session observers (SEC-010). */
    suspend fun revoke(deviceId: String) {
        trustStore.remove(deviceId)
        mutableRevocations.emit(deviceId)
    }

    private fun pending(result: HandshakeResult): PendingPairing = PendingPairing(
        myIdentityKey = identity.publicKey,
        result = result,
        trustStore = trustStore,
        clock = clock,
    )
}

/**
 * Completed cryptographic handshake awaiting an explicit local confirm or reject
 * decision. No trust record is written until [confirm] succeeds (PAIR-002).
 */
class PendingPairing internal constructor(
    myIdentityKey: ByteArray,
    result: HandshakeResult,
    private val trustStore: TrustStore,
    private val clock: Clock,
) {
    val peerIdentityKey: ByteArray = result.peerIdentityKey.copyOf()
    val sessionKeys: SessionKeys = SessionKeys(
        txKey = result.sessionKeys.txKey.copyOf(),
        rxKey = result.sessionKeys.rxKey.copyOf(),
    )
    val transcriptHash: ByteArray = result.transcriptHash.copyOf()
    val verificationCode: String = VerificationCode.derive(
        myIdentityKey,
        peerIdentityKey,
        transcriptHash,
    )

    private val resolutionMutex = Mutex()
    private var resolution = Resolution.PENDING

    /** Persists the peer identity after the user confirms the verification code. */
    suspend fun confirm(peerName: String, peerPlatform: String): TrustedDevice = resolutionMutex.withLock {
        check(resolution == Resolution.PENDING) {
            "Cannot confirm pairing: pending pairing was already ${resolution.name.lowercase()}"
        }
        val now = clock.nowMs()
        val device = TrustedDevice(
            deviceId = deviceIdFor(peerIdentityKey),
            displayName = peerName,
            platform = peerPlatform,
            publicKey = peerIdentityKey.copyOf(),
            pairedAt = now,
            lastSeen = now,
        )
        trustStore.save(device)
        resolution = Resolution.CONFIRMED
        device
    }

    /** Rejects the peer without persisting any trust record. */
    fun reject() {
        check(resolutionMutex.tryLock()) {
            "Cannot reject pairing while confirmation is in progress"
        }
        try {
            check(resolution == Resolution.PENDING) {
                "Cannot reject pairing: pending pairing was already ${resolution.name.lowercase()}"
            }
            resolution = Resolution.REJECTED
        } finally {
            resolutionMutex.unlock()
        }
    }

    private enum class Resolution {
        PENDING,
        CONFIRMED,
        REJECTED,
    }
}
