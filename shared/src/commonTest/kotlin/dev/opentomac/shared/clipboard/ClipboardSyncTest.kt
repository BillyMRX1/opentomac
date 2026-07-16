@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.opentomac.shared.clipboard

import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.protocol.ClipboardItemMsg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

    @Test
    fun failedSendDoesNotSuppressRetryOfSameContent() = runTest {
        val local = FakeLocalClipboard()
        var failNext = true
        val sent = mutableListOf<ClipboardItemMsg>()
        val engine = ClipboardSync("A", local, { message ->
            if (failNext) {
                failNext = false
                throw IllegalStateException("Not connected")
            }
            sent += message
        }, FakeClock(), historyLimit = null)
        engine.start(backgroundScope)

        val item = clip("retry me")
        local.copy(item)
        runCurrent()
        assertTrue(sent.isEmpty())

        // Same content again (e.g. re-read on app foreground): must NOT be deduped away.
        local.copy(item)
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun sendNowBypassesDuplicateSuppression() = runTest {
        val local = FakeLocalClipboard()
        val sent = mutableListOf<ClipboardItemMsg>()
        val engine = ClipboardSync("A", local, { sent += it }, FakeClock(), historyLimit = null)
        engine.start(backgroundScope)

        val item = clip("send twice")
        local.copy(item)
        runCurrent()
        assertEquals(1, sent.size)

        // Automatic path dedupes the repeat...
        local.copy(item)
        runCurrent()
        assertEquals(1, sent.size)

        // ...but an explicit user send always goes through.
        assertTrue(engine.sendNow(item))
        assertEquals(2, sent.size)
    }

    @Test
    fun sendNowReportsFailureAndStaysRetryable() = runTest {
        val local = FakeLocalClipboard()
        var fail = true
        val sent = mutableListOf<ClipboardItemMsg>()
        val engine = ClipboardSync("A", local, { message ->
            if (fail) throw IllegalStateException("offline")
            sent += message
        }, FakeClock(), historyLimit = null)

        val item = clip("manual")
        assertFalse(engine.sendNow(item))
        assertTrue(sent.isEmpty())

        fail = false
        assertTrue(engine.sendNow(item))
        assertEquals(1, sent.size)
    }

    @Test
    fun concurrentAutoAndManualSendsReachWireInSequenceOrder() = runTest {
        val local = FakeLocalClipboard()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var gateArmed = true
        val wire = mutableListOf<ClipboardItemMsg>()
        val engine = ClipboardSync("A", local, { message ->
            if (gateArmed) {
                gateArmed = false
                gate.await() // first (automatic) send parks mid-transport
            }
            wire += message
        }, FakeClock(), historyLimit = null)
        engine.start(backgroundScope)

        local.copy(clip("first"))
        runCurrent() // auto send is now suspended inside the transport

        val second = clip("second")
        val manual = async { engine.sendNow(second) }
        runCurrent()
        // The manual send must be waiting, not overtaking the parked auto send.
        assertTrue(wire.isEmpty())

        gate.complete(Unit)
        assertTrue(manual.await())
        runCurrent()

        assertEquals(2, wire.size)
        // Wire order matches sequence order, strictly increasing, no duplicates.
        assertEquals(wire.map { it.seq }, wire.map { it.seq }.sorted())
        assertEquals(wire.map { it.seq }.distinct().size, wire.size)
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
