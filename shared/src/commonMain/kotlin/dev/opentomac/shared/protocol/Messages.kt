@file:OptIn(ExperimentalSerializationApi::class)

package dev.opentomac.shared.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Thrown for any wire-protocol violation: malformed bytes, unsupported protocol
 * version, oversized or truncated frames, or a closed transport.
 */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Every message exchanged between devices. Serialized with kotlinx ProtoBuf using
 * sealed-class polymorphism: the wire form is a nested message carrying the stable
 * [SerialName] discriminator plus the payload fields. All field numbers are pinned
 * with [ProtoNumber] so reordering declarations never changes the wire format.
 */
@Serializable
sealed interface Message

/** Outer wrapper for every frame: protocol version, logical channel, per-channel sequence number. */
@Serializable
data class Envelope(
    @ProtoNumber(1) val version: Int,
    @ProtoNumber(2) val channel: ChannelId,
    @ProtoNumber(3) val seq: Long,
    @ProtoNumber(4) val payload: Message,
)

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

@Serializable
@SerialName("pair_accept")
data class PairAccept(
    @ProtoNumber(1) val publicKey: ByteArray,
    @ProtoNumber(2) val signature: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairAccept) return false
        return publicKey.contentEquals(other.publicKey) && signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int = 31 * publicKey.contentHashCode() + signature.contentHashCode()
}

@Serializable
@SerialName("pair_confirm")
data class PairConfirm(
    @ProtoNumber(1) val signature: ByteArray,
) : Message {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairConfirm) return false
        return signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int = signature.contentHashCode()
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

@Serializable
@SerialName("file_offer_reply")
data class FileOfferReply(
    @ProtoNumber(1) val jobId: String,
    @ProtoNumber(2) val accepted: Boolean,
    @ProtoNumber(3) val perFilePolicy: List<Int> = emptyList(),
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
        return key == other.key &&
            packageId == other.packageId &&
            appName == other.appName &&
            title == other.title &&
            body == other.body &&
            postedAt == other.postedAt &&
            (iconPng?.contentEquals(other.iconPng ?: return false) ?: (other.iconPng == null)) &&
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
 * Encodes and decodes [Envelope]s to/from ProtoBuf bytes.
 * [decode] rejects malformed bytes and unsupported protocol versions with [ProtocolException].
 */
object ProtocolCodec {
    const val PROTOCOL_VERSION: Int = 1

    private val protobuf = ProtoBuf

    fun encode(envelope: Envelope): ByteArray =
        protobuf.encodeToByteArray(Envelope.serializer(), envelope)

    fun decode(bytes: ByteArray): Envelope {
        val envelope = try {
            protobuf.decodeFromByteArray(Envelope.serializer(), bytes)
        } catch (e: SerializationException) {
            throw ProtocolException("Malformed envelope (${bytes.size} bytes): ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Malformed envelope (${bytes.size} bytes): ${e.message}", e)
        }
        if (envelope.version != PROTOCOL_VERSION) {
            throw ProtocolException(
                "Unsupported protocol version ${envelope.version}; this build supports version $PROTOCOL_VERSION",
            )
        }
        return envelope
    }
}
