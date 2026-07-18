@file:OptIn(ExperimentalSerializationApi::class)

package dev.opentomac.shared.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.serializer

/**
 * Thrown for any wire-protocol violation: malformed bytes, unsupported protocol
 * version, oversized or truncated frames, or a closed transport.
 */
open class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the envelope is well-formed and its version is supported, but the payload's
 * type discriminator names a message type unknown to this build. The session layer can
 * catch this specifically to log-and-ignore unknown message types (e.g. from a newer peer)
 * without dropping the connection.
 */
class UnknownMessageException(message: String, cause: Throwable? = null) : ProtocolException(message, cause)

/**
 * Every message exchanged between devices. Serialized with kotlinx ProtoBuf using
 * sealed-class polymorphism: the wire form is a nested message carrying the stable
 * [SerialName] discriminator plus the payload fields. All field numbers are pinned
 * with [ProtoNumber] so reordering declarations never changes the wire format.
 */
@Serializable
sealed interface Message

/**
 * Outer wrapper for every frame: protocol version, logical channel, per-channel sequence
 * number. This is the public API shape; on the wire it is encoded as a [WireEnvelope]
 * whose payload is a separately ProtoBuf-encoded [Message], so the envelope header is
 * always readable even when the payload type is unknown.
 */
data class Envelope(
    val version: Int,
    val channel: ChannelId,
    val seq: Long,
    val payload: Message,
)

/** Wire form of [Envelope]: the payload travels as opaque bytes (two-stage encoding). */
@Serializable
internal data class WireEnvelope(
    @ProtoNumber(1) val version: Int,
    @ProtoNumber(2) val channel: ChannelId,
    @ProtoNumber(3) val seq: Long,
    @ProtoNumber(4) val payloadBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireEnvelope) return false
        return version == other.version &&
            channel == other.channel &&
            seq == other.seq &&
            payloadBytes.contentEquals(other.payloadBytes)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + channel.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        return result
    }
}

// --- Session ---

@Serializable
@SerialName("hello")
data class Hello(
    @ProtoNumber(1) val protocolVersion: Int,
    @ProtoNumber(2) val deviceId: String,
    @ProtoNumber(3) val deviceName: String,
    @ProtoNumber(4) val platform: String,
    @ProtoNumber(5) val capabilities: List<String> = emptyList(),
) : Message

@Serializable
@SerialName("pair_init")
data class PairInit(
    @ProtoNumber(1) val token: ByteArray,
    @ProtoNumber(2) val publicKey: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairInit) return false
        return token.contentEquals(other.token) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * token.contentHashCode() + publicKey.contentHashCode()
}

/**
 * Handshake message 2 (responder -> initiator): the responder's long-term identity
 * key in [publicKey], its ephemeral X25519 key in [ephemeralKey], and an Ed25519
 * [signature] over the handshake transcript.
 */
@Serializable
@SerialName("pair_accept")
data class PairAccept(
    @ProtoNumber(1) val publicKey: ByteArray,
    @ProtoNumber(2) val signature: ByteArray,
    @ProtoNumber(3) val ephemeralKey: ByteArray = ByteArray(0),
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairAccept) return false
        return publicKey.contentEquals(other.publicKey) &&
            signature.contentEquals(other.signature) &&
            ephemeralKey.contentEquals(other.ephemeralKey)
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + ephemeralKey.contentHashCode()
        return result
    }
}

/**
 * Handshake message 3 (initiator -> responder): the initiator's long-term identity
 * key in [publicKey] and its Ed25519 [signature] over the handshake transcript.
 */
@Serializable
@SerialName("pair_confirm")
data class PairConfirm(
    @ProtoNumber(1) val signature: ByteArray,
    @ProtoNumber(2) val publicKey: ByteArray = ByteArray(0),
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairConfirm) return false
        return signature.contentEquals(other.signature) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * signature.contentHashCode() + publicKey.contentHashCode()
}

@Serializable
@SerialName("heartbeat")
data class Heartbeat(
    @ProtoNumber(1) val sentAtMs: Long,
) : Message

@Serializable
@SerialName("heartbeat_ack")
data class HeartbeatAck(
    @ProtoNumber(1) val sentAtMs: Long,
) : Message

// --- Clipboard ---

@Serializable
@SerialName("clipboard_item")
data class ClipboardItemMsg(
    @ProtoNumber(1) val itemId: String,
    @ProtoNumber(2) val originDeviceId: String,
    @ProtoNumber(3) val seq: Long,
    @ProtoNumber(4) val type: String,
    @ProtoNumber(5) val contentHash: ByteArray,
    @ProtoNumber(6) val payloadBytes: ByteArray,
    @ProtoNumber(7) val sensitive: Boolean,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardItemMsg) return false
        return itemId == other.itemId &&
            originDeviceId == other.originDeviceId &&
            seq == other.seq &&
            type == other.type &&
            contentHash.contentEquals(other.contentHash) &&
            payloadBytes.contentEquals(other.payloadBytes) &&
            sensitive == other.sensitive
    }

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + originDeviceId.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + contentHash.contentHashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        result = 31 * result + sensitive.hashCode()
        return result
    }
}

// --- URL handoff ---

@Serializable
@SerialName("open_url")
data class OpenUrl(
    @ProtoNumber(1) val url: String,
) : Message

// --- File transfer ---

@Serializable
data class FileMeta(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val sizeBytes: Long,
    @ProtoNumber(3) val mimeType: String,
)

@Serializable
@SerialName("file_offer")
data class FileOffer(
    @ProtoNumber(1) val jobId: String,
    @ProtoNumber(2) val files: List<FileMeta> = emptyList(),
) : Message

/** Per-file duplicate handling policy for incoming transfers. */
@Serializable
enum class DuplicatePolicy {
    @ProtoNumber(0)
    ASK,

    @ProtoNumber(1)
    REPLACE,

    @ProtoNumber(2)
    KEEP_BOTH,

    @ProtoNumber(3)
    SKIP,
}

@Serializable
@SerialName("file_offer_reply")
data class FileOfferReply(
    @ProtoNumber(1) val jobId: String,
    @ProtoNumber(2) val accepted: Boolean,
    @ProtoNumber(3) val perFilePolicy: List<DuplicatePolicy> = emptyList(),
) : Message

@Serializable
@SerialName("file_chunk")
data class FileChunk(
    @ProtoNumber(1) val jobId: String,
    @ProtoNumber(2) val fileIndex: Int,
    @ProtoNumber(3) val offset: Long,
    @ProtoNumber(4) val bytes: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileChunk) return false
        return jobId == other.jobId &&
            fileIndex == other.fileIndex &&
            offset == other.offset &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = jobId.hashCode()
        result = 31 * result + fileIndex
        result = 31 * result + offset.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

@Serializable
@SerialName("file_chunk_ack")
data class FileChunkAck(
    @ProtoNumber(1) val jobId: String,
    @ProtoNumber(2) val fileIndex: Int,
    @ProtoNumber(3) val verifiedThrough: Long,
) : Message

@Serializable
@SerialName("file_done")
data class FileDone(
    @ProtoNumber(1) val jobId: String,
    @ProtoNumber(2) val fileIndex: Int,
    @ProtoNumber(3) val sha256: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDone) return false
        return jobId == other.jobId && fileIndex == other.fileIndex && sha256.contentEquals(other.sha256)
    }

    override fun hashCode(): Int {
        var result = jobId.hashCode()
        result = 31 * result + fileIndex
        result = 31 * result + sha256.contentHashCode()
        return result
    }
}

@Serializable
@SerialName("file_cancel")
data class FileCancel(
    @ProtoNumber(1) val jobId: String,
    @ProtoNumber(2) val reason: String,
) : Message

// --- Media browsing ---

@Serializable
@SerialName("media_fetch_request")
data class MediaFetchRequest(
    @ProtoNumber(1) val mediaId: String,
) : Message

/** Announces a fresh phone screenshot; the peer decides whether to fetch it. */
@Serializable
@SerialName("screenshot_taken")
data class ScreenshotTaken(
    @ProtoNumber(1) val mediaId: String,
    @ProtoNumber(2) val name: String,
) : Message

@Serializable
data class MediaItem(
    @ProtoNumber(1) val mediaId: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val sizeBytes: Long,
    @ProtoNumber(4) val mimeType: String,
    @ProtoNumber(5) val modifiedAt: Long,
)

@Serializable
@SerialName("media_list_request")
data class MediaListRequest(
    @ProtoNumber(1) val bucket: String,
    @ProtoNumber(2) val page: Int,
    @ProtoNumber(3) val pageSize: Int,
) : Message

@Serializable
@SerialName("media_list_response")
data class MediaListResponse(
    @ProtoNumber(1) val items: List<MediaItem> = emptyList(),
    @ProtoNumber(2) val hasMore: Boolean,
    @ProtoNumber(3) val bucket: String = "",
    @ProtoNumber(4) val page: Int = 0,
) : Message

@Serializable
@SerialName("thumbnail_request")
data class ThumbnailRequest(
    @ProtoNumber(1) val mediaId: String,
) : Message

@Serializable
@SerialName("thumbnail_response")
data class ThumbnailResponse(
    @ProtoNumber(1) val mediaId: String,
    @ProtoNumber(2) val jpegBytes: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThumbnailResponse) return false
        return mediaId == other.mediaId && jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int = 31 * mediaId.hashCode() + jpegBytes.contentHashCode()
}

// --- Screen mirroring ---

@Serializable
@SerialName("mirror_request")
data class MirrorRequest(
    @ProtoNumber(1) val requestedAtMs: Long,
    @ProtoNumber(2) val maxLongEdge: Int = 1280,
    @ProtoNumber(3) val bitrateBps: Int = 6_000_000,
) : Message

@Serializable
@SerialName("mirror_stop")
data class MirrorStop(
    @ProtoNumber(1) val reason: String,
) : Message

@Serializable
@SerialName("video_config")
data class VideoConfig(
    @ProtoNumber(1) val width: Int,
    @ProtoNumber(2) val height: Int,
    @ProtoNumber(3) val csd0: ByteArray,
    @ProtoNumber(4) val csd1: ByteArray,
    @ProtoNumber(5) val frameRate: Int,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoConfig) return false
        return width == other.width &&
            height == other.height &&
            csd0.contentEquals(other.csd0) &&
            csd1.contentEquals(other.csd1) &&
            frameRate == other.frameRate
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + csd0.contentHashCode()
        result = 31 * result + csd1.contentHashCode()
        result = 31 * result + frameRate
        return result
    }
}

@Serializable
@SerialName("video_frame")
data class VideoFrame(
    @ProtoNumber(1) val ptsUs: Long,
    @ProtoNumber(2) val keyframe: Boolean,
    @ProtoNumber(3) val data: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFrame) return false
        return ptsUs == other.ptsUs && keyframe == other.keyframe && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = ptsUs.hashCode()
        result = 31 * result + keyframe.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

// --- Media remote ---

@Serializable
@SerialName("media_now_playing")
data class MediaNowPlaying(
    @ProtoNumber(1) val appName: String,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val artist: String,
    @ProtoNumber(4) val isPlaying: Boolean,
    @ProtoNumber(5) val hasSession: Boolean,
) : Message

@Serializable
@SerialName("media_control")
data class MediaControl(
    @ProtoNumber(1) val command: String,
) : Message

// --- Contacts ---

@Serializable
data class ContactItem(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val phones: List<String> = emptyList(),
    @ProtoNumber(3) val emails: List<String> = emptyList(),
)

@Serializable
@SerialName("contacts_search_request")
data class ContactsSearchRequest(
    @ProtoNumber(1) val query: String,
    @ProtoNumber(2) val limit: Int,
) : Message

@Serializable
@SerialName("contacts_search_response")
data class ContactsSearchResponse(
    @ProtoNumber(1) val query: String,
    @ProtoNumber(2) val items: List<ContactItem> = emptyList(),
    @ProtoNumber(3) val granted: Boolean = true,
) : Message

// --- Notifications ---

@Serializable
data class NotifAction(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val isRemoteInput: Boolean,
)

@Serializable
@SerialName("notification_posted")
data class NotificationPosted(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val packageId: String,
    @ProtoNumber(3) val appName: String,
    @ProtoNumber(4) val title: String,
    @ProtoNumber(5) val body: String,
    @ProtoNumber(6) val postedAt: Long,
    @ProtoNumber(7) val iconPng: ByteArray? = null,
    @ProtoNumber(8) val actions: List<NotifAction> = emptyList(),
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotificationPosted) return false
        val iconsEqual = when {
            iconPng == null && other.iconPng == null -> true
            iconPng == null || other.iconPng == null -> false
            else -> iconPng.contentEquals(other.iconPng)
        }
        return key == other.key &&
            packageId == other.packageId &&
            appName == other.appName &&
            title == other.title &&
            body == other.body &&
            postedAt == other.postedAt &&
            iconsEqual &&
            actions == other.actions
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + packageId.hashCode()
        result = 31 * result + appName.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + postedAt.hashCode()
        result = 31 * result + (iconPng?.contentHashCode() ?: 0)
        result = 31 * result + actions.hashCode()
        return result
    }
}

@Serializable
@SerialName("notification_dismissed")
data class NotificationDismissed(
    @ProtoNumber(1) val key: String,
) : Message

@Serializable
@SerialName("notification_action")
data class NotificationAction(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val actionIndex: Int,
    @ProtoNumber(3) val remoteInputText: String? = null,
) : Message

@Serializable
@SerialName("filter_update")
data class FilterUpdate(
    @ProtoNumber(1) val deniedPackages: List<String> = emptyList(),
    @ProtoNumber(2) val paused: Boolean,
) : Message

// --- Device management ---

@Serializable
@SerialName("revoke_device")
data class RevokeDevice(
    @ProtoNumber(1) val deviceId: String,
) : Message

/**
 * Encodes and decodes [Envelope]s to/from ProtoBuf bytes using two-stage encoding:
 * the [Message] payload is ProtoBuf-encoded on its own, then wrapped in a [WireEnvelope]
 * carrying it as opaque bytes.
 *
 * [decode] checks the envelope version BEFORE touching the payload, so a newer-version
 * envelope with an undecodable payload reports an unsupported version, not a malformed
 * payload. A supported-version envelope whose payload discriminator is unknown throws
 * [UnknownMessageException]; all other violations throw [ProtocolException].
 */
object ProtocolCodec {
    const val PROTOCOL_VERSION: Int = 1

    private val protobuf = ProtoBuf

    private val messageSerializer = serializer<Message>()

    /** Serial names of every message type this build understands. */
    private val knownMessageTypes: Set<String> =
        messageSerializer.descriptor.getElementDescriptor(1).elementNames.toSet()

    fun encode(envelope: Envelope): ByteArray {
        val payloadBytes = protobuf.encodeToByteArray(messageSerializer, envelope.payload)
        val wire = WireEnvelope(envelope.version, envelope.channel, envelope.seq, payloadBytes)
        return protobuf.encodeToByteArray(WireEnvelope.serializer(), wire)
    }

    fun decode(bytes: ByteArray): Envelope {
        val wire = try {
            protobuf.decodeFromByteArray(WireEnvelope.serializer(), bytes)
        } catch (e: SerializationException) {
            throw ProtocolException("Malformed envelope (${bytes.size} bytes): ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Malformed envelope (${bytes.size} bytes): ${e.message}", e)
        }
        if (wire.version != PROTOCOL_VERSION) {
            throw ProtocolException(
                "Unsupported protocol version ${wire.version}; this build supports version $PROTOCOL_VERSION",
            )
        }
        val discriminator = try {
            protobuf.decodeFromByteArray(PolymorphicHeader.serializer(), wire.payloadBytes).type
        } catch (e: SerializationException) {
            throw ProtocolException("Malformed payload (${wire.payloadBytes.size} bytes): ${e.message}", e)
        }
        if (discriminator !in knownMessageTypes) {
            throw UnknownMessageException(
                "Unknown message type '$discriminator' (version ${wire.version}, channel ${wire.channel})",
            )
        }
        val payload = try {
            protobuf.decodeFromByteArray(messageSerializer, wire.payloadBytes)
        } catch (e: SerializationException) {
            throw ProtocolException("Malformed payload for message type '$discriminator': ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Malformed payload for message type '$discriminator': ${e.message}", e)
        }
        return Envelope(wire.version, wire.channel, wire.seq, payload)
    }
}

/**
 * Probe used to read only the polymorphic type discriminator (proto field 1) of an
 * encoded [Message] without decoding its body.
 */
@Serializable
private data class PolymorphicHeader(
    @ProtoNumber(1) val type: String,
)
