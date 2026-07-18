@file:OptIn(ExperimentalSerializationApi::class, ExperimentalStdlibApi::class)

package dev.opentomac.shared.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
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
        val msg = PairAccept(
            publicKey = ByteArray(32) { (it + 1).toByte() },
            signature = ByteArray(64) { (255 - it).toByte() },
            ephemeralKey = ByteArray(32) { (it * 2).toByte() },
        )
        val decoded = assertIs<PairAccept>(roundTrip(msg))
        assertContentEquals(msg.publicKey, decoded.publicKey)
        assertContentEquals(msg.signature, decoded.signature)
        assertContentEquals(msg.ephemeralKey, decoded.ephemeralKey)
        assertEquals(msg, decoded)
    }

    @Test
    fun pairConfirmRoundTrip() {
        val msg = PairConfirm(
            signature = ByteArray(64) { it.toByte() },
            publicKey = ByteArray(32) { (it + 7).toByte() },
        )
        val decoded = assertIs<PairConfirm>(roundTrip(msg))
        assertContentEquals(msg.signature, decoded.signature)
        assertContentEquals(msg.publicKey, decoded.publicKey)
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
    fun openUrlRoundTrip() {
        val msg = OpenUrl(url = "https://example.com/path?q=opentomac")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
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
        val msg = FileOfferReply(
            jobId = "job-1",
            accepted = true,
            perFilePolicy = listOf(
                DuplicatePolicy.ASK,
                DuplicatePolicy.REPLACE,
                DuplicatePolicy.KEEP_BOTH,
                DuplicatePolicy.SKIP,
            ),
        )
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
    fun mediaFetchRequestRoundTrip() {
        val msg = MediaFetchRequest(mediaId = "content://media/external/images/media/42")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun screenshotTakenRoundTrip() {
        val msg = ScreenshotTaken(
            mediaId = "content://media/external/images/media/77",
            name = "Screenshot_20260718.jpg",
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))

        val defaults = assertIs<MirrorRequest>(
            roundTrip(MirrorRequest(requestedAtMs = 1), channel = ChannelId.EVENT),
        )
        assertEquals(1280, defaults.maxLongEdge)
        assertEquals(6_000_000, defaults.bitrateBps)
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
            bucket = "Camera",
            page = 3,
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
    fun mirrorRequestRoundTrip() {
        val msg = MirrorRequest(
            requestedAtMs = 1_720_000_000_789,
            maxLongEdge = 1920,
            bitrateBps = 10_000_000,
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun mirrorStopRoundTrip() {
        val msg = MirrorStop(reason = "Stopped by peer")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun videoConfigRoundTrip() {
        val msg = VideoConfig(
            width = 720,
            height = 1280,
            csd0 = byteArrayOf(0, 0, 0, 1, 0x67),
            csd1 = byteArrayOf(0, 0, 0, 1, 0x68),
            frameRate = 30,
        )
        val decoded = assertIs<VideoConfig>(roundTrip(msg, channel = ChannelId.VIDEO))
        assertContentEquals(msg.csd0, decoded.csd0)
        assertContentEquals(msg.csd1, decoded.csd1)
        assertEquals(msg, decoded)
    }

    @Test
    fun videoFrameRoundTrip() {
        val msg = VideoFrame(
            ptsUs = 33_333,
            keyframe = true,
            data = ByteArray(2048) { (it % 251).toByte() },
        )
        val decoded = assertIs<VideoFrame>(roundTrip(msg, channel = ChannelId.VIDEO))
        assertContentEquals(msg.data, decoded.data)
        assertEquals(msg, decoded)
    }

    @Test
    fun cameraRequestRoundTrip() {
        val msg = CameraRequest(facing = "front", withAudio = true)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun cameraStopRoundTrip() {
        val msg = CameraStop(reason = "Stopped by peer")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun cameraConfigRoundTrip() {
        val msg = CameraConfig(
            width = 1280,
            height = 720,
            csd0 = byteArrayOf(0, 0, 0, 1, 0x67),
            csd1 = byteArrayOf(0, 0, 0, 1, 0x68),
            frameRate = 30,
        )
        val decoded = assertIs<CameraConfig>(roundTrip(msg, channel = ChannelId.VIDEO))
        assertContentEquals(msg.csd0, decoded.csd0)
        assertContentEquals(msg.csd1, decoded.csd1)
        assertEquals(msg, decoded)
    }

    @Test
    fun cameraFrameRoundTrip() {
        val msg = CameraFrame(
            ptsUs = 66_666,
            keyframe = false,
            data = ByteArray(3072) { (it % 239).toByte() },
        )
        val decoded = assertIs<CameraFrame>(roundTrip(msg, channel = ChannelId.VIDEO))
        assertContentEquals(msg.data, decoded.data)
        assertEquals(msg, decoded)
    }

    @Test
    fun audioFrameRoundTrip() {
        val msg = AudioFrame(
            ptsUs = 23_219,
            data = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x40, 0x01, 0x7F, 0xFC.toByte(), 1, 2),
        )
        val decoded = assertIs<AudioFrame>(roundTrip(msg, channel = ChannelId.VIDEO))
        assertContentEquals(msg.data, decoded.data)
        assertEquals(msg, decoded)
    }

    @Test
    fun inputTapRoundTrip() {
        val msg = InputTap(x = 0.25f, y = 0.75f)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun inputSwipeRoundTrip() {
        val msg = InputSwipe(
            x1 = 0.2f,
            y1 = 0.8f,
            x2 = 0.7f,
            y2 = 0.1f,
            durationMs = 425,
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun inputKeyRoundTrip() {
        val msg = InputKey(action = "recents")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun inputTextRoundTrip() {
        val msg = InputText(text = "hello \uD83D\uDC4B", deleteCount = 2)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))

        val defaults = assertIs<InputText>(
            roundTrip(InputText(text = "world"), channel = ChannelId.EVENT),
        )
        assertEquals(0, defaults.deleteCount)
    }

    @Test
    fun mediaNowPlayingRoundTrip() {
        val msg = MediaNowPlaying(
            appName = "Music",
            title = "Continuity",
            artist = "Open Tomac",
            isPlaying = true,
            hasSession = true,
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun mediaControlRoundTrip() {
        val msg = MediaControl(command = "volume_up")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.EVENT))
    }

    @Test
    fun contactsSearchRequestRoundTrip() {
        val msg = ContactsSearchRequest(query = "Ada", limit = 20)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun contactsSearchResponseRoundTrip() {
        val msg = ContactsSearchResponse(
            query = "Ada",
            items = listOf(
                ContactItem(
                    name = "Ada Lovelace",
                    phones = listOf("+44 20 7946 0958"),
                    emails = listOf("ada@example.com"),
                ),
            ),
            granted = false,
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun smsThreadsRequestRoundTrip() {
        val msg = SmsThreadsRequest(limit = 40)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun smsThreadsResponseRoundTrip() {
        val msg = SmsThreadsResponse(
            threads = listOf(
                SmsThread(
                    threadId = "17",
                    address = "+81 90 1234 5678",
                    contactName = "Mina",
                    snippet = "See you soon",
                    dateMs = 1_721_111_222_333,
                    unread = true,
                ),
            ),
            granted = false,
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun smsThreadRequestRoundTrip() {
        val msg = SmsThreadRequest(threadId = "17", limit = 100)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun smsThreadResponseRoundTrip() {
        val msg = SmsThreadResponse(
            threadId = "17",
            address = "+81 90 1234 5678",
            messages = listOf(
                SmsMessage("Are you nearby?", 1_721_111_200_000, incoming = true),
                SmsMessage("See you soon", 1_721_111_222_333, incoming = false),
            ),
            granted = true,
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun smsSendRequestRoundTrip() {
        val msg = SmsSendRequest(address = "+81 90 1234 5678", body = "On my way", id = "sms-op-1")
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun smsSendResultRoundTrip() {
        val msg = SmsSendResult(
            address = "+81 90 1234 5678",
            sent = false,
            error = "No service",
            id = "sms-op-1",
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun callLogRequestRoundTrip() {
        val msg = CallLogRequest(limit = 50)
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
    }

    @Test
    fun callLogResponseRoundTrip() {
        val msg = CallLogResponse(
            entries = listOf(
                CallLogEntry(
                    number = "+44 20 7946 0958",
                    contactName = "Ada Lovelace",
                    type = "incoming",
                    dateMs = 1_721_222_333_444,
                    durationSec = 83,
                ),
            ),
            granted = false,
        )
        assertEquals(msg, roundTrip(msg, channel = ChannelId.BULK))
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
    fun unsupportedVersionWithUndecodablePayloadReportsVersionError() {
        // A future-version envelope whose payload this build cannot decode must be
        // reported as an unsupported version, not as a malformed payload.
        val wire = WireEnvelope(
            version = ProtocolCodec.PROTOCOL_VERSION + 1,
            channel = ChannelId.CONTROL,
            seq = 1,
            payloadBytes = byteArrayOf(0x7F, -1, -1, -1), // garbage, undecodable as Message
        )
        val bytes = ProtoBuf.encodeToByteArray(WireEnvelope.serializer(), wire)
        val e = assertFailsWith<ProtocolException> { ProtocolCodec.decode(bytes) }
        assertTrue(e !is UnknownMessageException)
        assertTrue(
            e.message!!.contains("Unsupported protocol version"),
            "expected version error, was: ${e.message}",
        )
    }

    @Test
    fun unknownDiscriminatorThrowsUnknownMessageException() {
        // Same polymorphic wire shape (field 1 = type string, field 2 = body message)
        // but with a discriminator this build does not know.
        val payloadBytes = ProtoBuf.encodeToByteArray(
            FakePolymorphicMessage.serializer(),
            FakePolymorphicMessage(type = "message_from_the_future", value = FakeBody(x = 42)),
        )
        val wire = WireEnvelope(
            version = ProtocolCodec.PROTOCOL_VERSION,
            channel = ChannelId.EVENT,
            seq = 5,
            payloadBytes = payloadBytes,
        )
        val bytes = ProtoBuf.encodeToByteArray(WireEnvelope.serializer(), wire)
        val e = assertFailsWith<UnknownMessageException> { ProtocolCodec.decode(bytes) }
        assertTrue(
            e.message!!.contains("message_from_the_future"),
            "expected discriminator in message, was: ${e.message}",
        )
    }

    @Test
    fun goldenHeartbeatEnvelopeBytes() {
        // Pins the wire format. If this test fails, the change is protocol-breaking:
        // bump PROTOCOL_VERSION or revert, do not just update the hex.
        val envelope = Envelope(
            version = ProtocolCodec.PROTOCOL_VERSION,
            channel = ChannelId.CONTROL,
            seq = 42,
            payload = Heartbeat(sentAtMs = 123456789),
        )
        // Layout: 0801 version=1, 1000 channel=CONTROL, 182a seq=42,
        // 2212 payloadBytes(18): 0a09 "heartbeat" discriminator, 1205 body { 08 sentAtMs varint }.
        val expectedHex = "08011000182a22120a09686561727462656174120508959aef3a"
        assertEquals(expectedHex, ProtocolCodec.encode(envelope).toHexString())
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

/** Mirrors kotlinx ProtoBuf's polymorphic wire shape with an unknown discriminator. */
@Serializable
private data class FakePolymorphicMessage(
    @ProtoNumber(1) val type: String,
    @ProtoNumber(2) val value: FakeBody,
)

@Serializable
private data class FakeBody(
    @ProtoNumber(1) val x: Int,
)
