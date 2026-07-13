package dev.opentomac.shared.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesTest {

    private fun roundTrip(message: Message, channel: ChannelId = ChannelId.CONTROL, seq: Long = 7): Message {
        val envelope = Envelope(
            version = ProtocolCodec.PROTOCOL_VERSION,
            channel = channel,
            seq = seq,
            payload = message,
        )
        val bytes = ProtocolCodec.encode(envelope)
        val decoded = ProtocolCodec.decode(bytes)
        assertEquals(envelope.version, decoded.version)
        assertEquals(channel, decoded.channel)
        assertEquals(seq, decoded.seq)
        return decoded.payload
    }

    @Test
    fun helloRoundTrip() {
        val msg = Hello(
            protocolVersion = 1,
            deviceId = "device-123",
            deviceName = "Pixel 9",
            platform = "android",
            capabilities = listOf("clipboard", "files", "notifications"),
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun pairInitRoundTrip() {
        val msg = PairInit(token = byteArrayOf(1, 2, 3, 4, 5, 6), publicKey = ByteArray(32) { it.toByte() })
        val decoded = assertIs<PairInit>(roundTrip(msg))
        assertContentEquals(msg.token, decoded.token)
        assertContentEquals(msg.publicKey, decoded.publicKey)
        assertEquals(msg, decoded)
    }

    @Test
    fun pairAcceptRoundTrip() {
        val msg = PairAccept(publicKey = ByteArray(32) { (it + 1).toByte() }, signature = ByteArray(64) { (255 - it).toByte() })
        val decoded = assertIs<PairAccept>(roundTrip(msg))
        assertContentEquals(msg.publicKey, decoded.publicKey)
        assertContentEquals(msg.signature, decoded.signature)
        assertEquals(msg, decoded)
    }

    @Test
    fun pairConfirmRoundTrip() {
        val msg = PairConfirm(signature = ByteArray(64) { it.toByte() })
        val decoded = assertIs<PairConfirm>(roundTrip(msg))
        assertContentEquals(msg.signature, decoded.signature)
        assertEquals(msg, decoded)
    }

    @Test
    fun heartbeatRoundTrip() {
        val msg = Heartbeat(sentAtMs = 1_720_000_000_123)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun heartbeatAckRoundTrip() {
        val msg = HeartbeatAck(sentAtMs = 1_720_000_000_456)
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun clipboardItemMsgRoundTrip() {
        val msg = ClipboardItemMsg(
            itemId = "clip-1",
            originDeviceId = "device-123",
            seq = 42,
            type = "text/plain",
            contentHash = ByteArray(32) { 9 },
            payloadBytes = "hello clipboard".encodeToByteArray(),
            sensitive = true,
        )
        val decoded = assertIs<ClipboardItemMsg>(roundTrip(msg, channel = ChannelId.EVENT))
        assertContentEquals(msg.contentHash, decoded.contentHash)
        assertContentEquals(msg.payloadBytes, decoded.payloadBytes)
        assertEquals(msg, decoded)
    }

    @Test
    fun fileOfferRoundTrip() {
        val msg = FileOffer(
            jobId = "job-1",
            files = listOf(
                FileMeta(name = "a.jpg", sizeBytes = 1024, mimeType = "image/jpeg"),
                FileMeta(name = "b.pdf", sizeBytes = 2_000_000, mimeType = "application/pdf"),
            ),
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun fileOfferReplyRoundTrip() {
        val msg = FileOfferReply(jobId = "job-1", accepted = true, perFilePolicy = listOf(0, 1, 2))
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun fileChunkRoundTrip() {
        val msg = FileChunk(jobId = "job-1", fileIndex = 0, offset = 65536, bytes = ByteArray(1024) { (it % 251).toByte() })
        val decoded = assertIs<FileChunk>(roundTrip(msg, channel = ChannelId.BULK))
        assertContentEquals(msg.bytes, decoded.bytes)
        assertEquals(msg, decoded)
    }

    @Test
    fun fileChunkAckRoundTrip() {
        val msg = FileChunkAck(jobId = "job-1", fileIndex = 0, verifiedThrough = 131072)
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun fileDoneRoundTrip() {
        val msg = FileDone(jobId = "job-1", fileIndex = 1, sha256 = ByteArray(32) { (it * 3).toByte() })
        val decoded = assertIs<FileDone>(roundTrip(msg))
        assertContentEquals(msg.sha256, decoded.sha256)
        assertEquals(msg, decoded)
    }

    @Test
    fun fileCancelRoundTrip() {
        val msg = FileCancel(jobId = "job-1", reason = "user cancelled")
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun mediaListRequestRoundTrip() {
        val msg = MediaListRequest(bucket = "Camera", page = 3, pageSize = 50)
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun mediaListResponseRoundTrip() {
        val msg = MediaListResponse(
            items = listOf(
                MediaItem(mediaId = "m1", name = "IMG_0001.jpg", sizeBytes = 3_400_000, mimeType = "image/jpeg", modifiedAt = 1_719_999_999_000),
                MediaItem(mediaId = "m2", name = "VID_0002.mp4", sizeBytes = 88_000_000, mimeType = "video/mp4", modifiedAt = 1_720_000_100_000),
            ),
            hasMore = true,
        )
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun thumbnailRequestRoundTrip() {
        val msg = ThumbnailRequest(mediaId = "m1")
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun thumbnailResponseRoundTrip() {
        val msg = ThumbnailResponse(mediaId = "m1", jpegBytes = ByteArray(512) { (it % 127).toByte() })
        val decoded = assertIs<ThumbnailResponse>(roundTrip(msg, channel = ChannelId.BULK))
        assertContentEquals(msg.jpegBytes, decoded.jpegBytes)
        assertEquals(msg, decoded)
    }

    @Test
    fun notificationPostedRoundTrip() {
        val msg = NotificationPosted(
            key = "0|com.example|1|null|10001",
            packageId = "com.example",
            appName = "Example",
            title = "New message",
            body = "Hi there",
            postedAt = 1_720_000_200_000,
            iconPng = byteArrayOf(0x50, 0x4E, 0x47),
            actions = listOf(
                NotifAction(index = 0, title = "Reply", isRemoteInput = true),
                NotifAction(index = 1, title = "Mark read", isRemoteInput = false),
            ),
        )
        val decoded = assertIs<NotificationPosted>(roundTrip(msg, channel = ChannelId.EVENT))
        assertContentEquals(msg.iconPng, decoded.iconPng)
        assertEquals(msg, decoded)
    }

    @Test
    fun notificationPostedWithNullIconRoundTrip() {
        val msg = NotificationPosted(
            key = "0|com.example|2|null|10001",
            packageId = "com.example",
            appName = "Example",
            title = "No icon",
            body = "",
            postedAt = 1_720_000_300_000,
            iconPng = null,
            actions = emptyList(),
        )
        val decoded = assertIs<NotificationPosted>(roundTrip(msg, channel = ChannelId.EVENT))
        assertNull(decoded.iconPng)
        assertEquals(msg, decoded)
    }

    @Test
    fun notificationDismissedRoundTrip() {
        val msg = NotificationDismissed(key = "0|com.example|1|null|10001")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun notificationActionRoundTrip() {
        val withText = NotificationAction(key = "k", actionIndex = 0, remoteInputText = "reply text")
        assertEquals(withText, roundTrip(withText))
        val withoutText = NotificationAction(key = "k", actionIndex = 1, remoteInputText = null)
        val decoded = assertIs<NotificationAction>(roundTrip(withoutText))
        assertNull(decoded.remoteInputText)
        assertEquals(withoutText, decoded)
    }

    @Test
    fun filterUpdateRoundTrip() {
        val msg = FilterUpdate(deniedPackages = listOf("com.spam.app", "com.other"), paused = false)
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun revokeDeviceRoundTrip() {
        val msg = RevokeDevice(deviceId = "device-999")
        assertEquals(msg, roundTrip(msg))
    }

    @Test
    fun everyChannelRoundTrips() {
        for (channel in ChannelId.entries) {
            val decoded = ProtocolCodec.decode(
                ProtocolCodec.encode(
                    Envelope(ProtocolCodec.PROTOCOL_VERSION, channel, 1, Heartbeat(1)),
                ),
            )
            assertEquals(channel, decoded.channel)
        }
    }

    @Test
    fun unsupportedVersionThrowsProtocolException() {
        val bytes = ProtocolCodec.encode(
            Envelope(version = ProtocolCodec.PROTOCOL_VERSION + 1, channel = ChannelId.CONTROL, seq = 1, payload = Heartbeat(1)),
        )
        val e = assertFailsWith<ProtocolException> { ProtocolCodec.decode(bytes) }
        assertTrue(e.message!!.contains("version"), "expected message to mention version, was: ${e.message}")
    }

    @Test
    fun garbageBytesThrowProtocolException() {
        assertFailsWith<ProtocolException> {
            ProtocolCodec.decode(byteArrayOf(0x7F, -1, -1, -1, -1, -1, 0x00, 0x13, 0x37))
        }
    }

    @Test
    fun byteArrayMessagesHaveValueEquality() {
        val a = PairInit(token = byteArrayOf(1, 2), publicKey = byteArrayOf(3, 4))
        val b = PairInit(token = byteArrayOf(1, 2), publicKey = byteArrayOf(3, 4))
        val c = PairInit(token = byteArrayOf(1, 2), publicKey = byteArrayOf(3, 5))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }
}
