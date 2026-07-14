@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.opentomac.shared.clipboard

import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.protocol.ClipboardItemMsg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClipboardSyncTest {

    @Test
    fun contentHashIsStableAndChangesWithPayload() = runTest {
        val first = clip("same payload")
        val same = clip("same payload")
        val different = clip("different payload")

        assertEquals(32, first.contentHash.size)
        assertContentEquals(first.contentHash, same.contentHash)
        assertFalse(first.contentHash.contentEquals(different.contentHash))
    }

    @Test
    fun twoBackToBackEnginesDeliverEachNewItemOnceWithoutBouncing() = runTest {
        val localA = FakeLocalClipboard()
        val localB = FakeLocalClipboard()
        val sentFromA = mutableListOf<ClipboardItemMsg>()
        val sentFromB = mutableListOf<ClipboardItemMsg>()
        lateinit var engineA: ClipboardSync
        lateinit var engineB: ClipboardSync
        engineA = ClipboardSync("A", localA, { message ->
            sentFromA += message
            engineB.onRemoteItem(message)
        }, FakeClock(100), historyLimit = 10)
        engineB = ClipboardSync("B", localB, { message ->
            sentFromB += message
            engineA.onRemoteItem(message)
        }, FakeClock(200), historyLimit = 10)
        engineA.start(backgroundScope)
        engineB.start(backgroundScope)

        val fromA = clip("from A")
        localA.copy(fromA)
        runCurrent()

        assertEquals(1, sentFromA.size)
        assertEquals(0, sentFromB.size)
        assertEquals(listOf(fromA), localB.applied)

        val fromB = clip("from B", ClipType.URL)
        localB.copy(fromB)
        runCurrent()

        assertEquals(1, sentFromA.size)
        assertEquals(1, sentFromB.size)
        assertEquals(listOf(fromB), localA.applied)
        assertEquals(listOf(fromA), localB.applied)
        assertEquals(listOf("from B", "from A"), engineA.history.items().map { it.text() })
        assertEquals(listOf("from B", "from A"), engineB.history.items().map { it.text() })
    }

    @Test
    fun sensitiveLocalItemsAreNeitherSentNorRecorded() = runTest {
        val local = FakeLocalClipboard()
        val sent = mutableListOf<ClipboardItemMsg>()
        val engine = ClipboardSync(
            deviceId = "local",
            local = local,
            send = sent::add,
            clock = FakeClock(),
            historyLimit = 10,
        )
        engine.start(backgroundScope)

        local.copy(clip("password", sensitive = true))
        runCurrent()

        assertTrue(sent.isEmpty())
        assertTrue(engine.history.items().isEmpty())
    }

    @Test
    fun pauseSuppressesOutboundWhileInboundStillApplies() = runTest {
        val local = FakeLocalClipboard()
        val sent = mutableListOf<ClipboardItemMsg>()
        val engine = ClipboardSync(
            deviceId = "local",
            local = local,
            send = sent::add,
            clock = FakeClock(),
            historyLimit = 10,
        )
        engine.start(backgroundScope)
        engine.pause()

        local.copy(clip("local while paused"))
        runCurrent()
        val remote = clip("remote while paused")
        engine.onRemoteItem(remote.message(origin = "peer", seq = 1))
        runCurrent()

        assertTrue(engine.paused.value)
        assertTrue(sent.isEmpty())
        assertEquals(listOf(remote), local.applied)
        assertEquals(listOf("remote while paused"), engine.history.items().map { it.text() })

        engine.resume()
        assertFalse(engine.paused.value)
    }

    @Test
    fun duplicateAndOutOfOrderRemoteSequencesAreIgnoredPerOrigin() = runTest {
        val local = FakeLocalClipboard()
        val engine = ClipboardSync("local", local, {}, FakeClock(), historyLimit = 10)
        val accepted = clip("accepted")

        engine.onRemoteItem(accepted.message(origin = "peer", seq = 2))
        engine.onRemoteItem(clip("duplicate").message(origin = "peer", seq = 2))
        engine.onRemoteItem(clip("older").message(origin = "peer", seq = 1))
        engine.onRemoteItem(clip("other origin").message(origin = "other", seq = 1))

        assertEquals(listOf("accepted", "other origin"), local.applied.map { it.text() })
        assertEquals(listOf("other origin", "accepted"), engine.history.items().map { it.text() })
    }

    @Test
    fun ownOriginEchoIsIgnored() = runTest {
        val local = FakeLocalClipboard()
        val engine = ClipboardSync("local", local, {}, FakeClock(), historyLimit = 10)

        engine.onRemoteItem(clip("echo").message(origin = "local", seq = 1))

        assertTrue(local.applied.isEmpty())
        assertTrue(engine.history.items().isEmpty())
    }

    @Test
    fun outboundMessagesMapFieldsAndUseMonotonicSequenceNumbers() = runTest {
        val local = FakeLocalClipboard()
        val sent = mutableListOf<ClipboardItemMsg>()
        val clock = FakeClock(5_000)
        val engine = ClipboardSync("device-1", local, sent::add, clock, historyLimit = 10)
        engine.start(backgroundScope)
        val text = clip("hello")
        val url = clip("https://opentomac.dev", ClipType.URL)

        local.copy(text)
        local.copy(url)
        runCurrent()

        assertEquals(listOf(1L, 2L), sent.map { it.seq })
        assertEquals(listOf("device-1", "device-1"), sent.map { it.originDeviceId })
        assertEquals(listOf("TEXT", "URL"), sent.map { it.type })
        assertContentEquals(text.payload, sent[0].payloadBytes)
        assertContentEquals(text.contentHash, sent[0].contentHash)
        assertFalse(sent[0].sensitive)
        assertNotEquals(sent[0].itemId, sent[1].itemId)
        assertEquals(listOf("https://opentomac.dev", "hello"), engine.history.items().map { it.text() })
    }

    @Test
    fun stopCancelsLocalChangeCollection() = runTest {
        val local = FakeLocalClipboard()
        val sent = mutableListOf<ClipboardItemMsg>()
        val engine = ClipboardSync("local", local, sent::add, FakeClock())
        engine.start(backgroundScope)
        engine.stop()
        runCurrent()

        local.copy(clip("after stop"))
        runCurrent()

        assertTrue(sent.isEmpty())
    }
}

private class FakeLocalClipboard : LocalClipboard {
    private val changeEvents = MutableSharedFlow<ClipItem>(extraBufferCapacity = 16)
    val applied = mutableListOf<ClipItem>()

    override fun changes(): Flow<ClipItem> = changeEvents

    override suspend fun apply(item: ClipItem) {
        applied += item
        changeEvents.emit(item)
    }

    suspend fun copy(item: ClipItem) {
        changeEvents.emit(item)
    }
}

private class FakeClock(var now: Long = 0) : Clock {
    override fun nowMs(): Long = now
}

private suspend fun clip(
    value: String,
    type: ClipType = ClipType.TEXT,
    sensitive: Boolean = false,
): ClipItem = ClipItem.create(type, value.encodeToByteArray(), sensitive)

private fun ClipItem.text(): String = payload.decodeToString()

private fun ClipItem.message(origin: String, seq: Long): ClipboardItemMsg = ClipboardItemMsg(
    itemId = "$origin-$seq",
    originDeviceId = origin,
    seq = seq,
    type = type.name,
    contentHash = contentHash,
    payloadBytes = payload,
    sensitive = sensitive,
)
