package dev.opentomac.shared.transfer

import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.protocol.DuplicatePolicy
import dev.opentomac.shared.protocol.FileChunk
import dev.opentomac.shared.protocol.FileMeta
import dev.opentomac.shared.protocol.FrameCodec
import dev.opentomac.shared.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransferEngineTest {

    @Test
    fun singleSmallFileTransfersVerifiesAndRenamesPart() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        val bytes = "hello from opentomac".encodeToByteArray()
        val source = sourceFile(sourceFs, "/source/hello.txt", bytes)
        val pair = enginePair(
            sourceFs = sourceFs,
            destinationFs = destinationFs,
            decision = { _, files, exists ->
                assertEquals(listOf(source.meta), files)
                assertEquals(listOf(false), exists)
                OfferDecision(true, listOf(DuplicatePolicy.KEEP_BOTH))
            },
        )

        val job = pair.sender.offer(listOf(source), jobId = "small")
        runCurrent()

        assertContentEquals(bytes, destinationFs.read("/received/hello.txt".toPath()) { readByteArray() })
        assertFalse(destinationFs.exists("/received/hello.txt.part".toPath()))
        assertEquals(TransferState.DONE, job.progress.value.state)
        assertEquals(bytes.size.toLong(), job.progress.value.completedBytes)
        assertEquals(TransferState.DONE, pair.receiver.job("small")!!.progress.value.state)
    }

    @Test
    fun multiFileBatchPreservesOrderAndStreamsMultipleChunks() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        val inputs = List(3) { index ->
            ByteArray(TransferEngine.CHUNK_BYTES * 2 + TransferEngine.CHUNK_BYTES / 2) {
                (it * 31 + index).toByte()
            }
        }
        val sources = inputs.mapIndexed { index, bytes ->
            sourceFile(sourceFs, "/source/file-$index.bin", bytes)
        }
        val seenIndices = mutableListOf<Int>()
        val pair = enginePair(
            sourceFs,
            destinationFs,
            senderInterceptor = { message ->
                if (message is FileChunk && seenIndices.lastOrNull() != message.fileIndex) {
                    seenIndices += message.fileIndex
                }
                message
            },
        )

        val job = pair.sender.offer(sources, jobId = "batch")
        runCurrent()

        assertEquals(listOf(0, 1, 2), seenIndices)
        inputs.forEachIndexed { index, bytes ->
            assertContentEquals(
                bytes,
                destinationFs.read("/received/file-$index.bin".toPath()) { readByteArray() },
            )
        }
        assertEquals(inputs.sumOf { it.size.toLong() }, job.progress.value.completedBytes)
        assertEquals(TransferState.DONE, job.progress.value.state)
    }

    @Test
    fun thousandFileOfferAndReplyKeepsAllMetadata() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        val sources = List(1_000) { index ->
            sourceFile(sourceFs, "/source/tiny-$index.txt", byteArrayOf(index.toByte()))
        }
        var offeredFiles = emptyList<FileMeta>()
        val pair = enginePair(
            sourceFs,
            destinationFs,
            decision = { _, files, _ ->
                offeredFiles = files
                OfferDecision(true, List(files.size) { DuplicatePolicy.KEEP_BOTH })
            },
        )

        val job = pair.sender.offer(sources, jobId = "metadata")
        runCurrent()

        assertEquals(sources.map { it.meta }, offeredFiles)
        assertEquals(1_000, pair.receiver.job("metadata")!!.files.size)
        assertEquals(TransferState.DONE, job.progress.value.state)
    }

    @Test
    fun corruptChunkFailsBothJobsAndDeletesPartialAndFinalFiles() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        val bytes = ByteArray(TransferEngine.CHUNK_BYTES + 17) { it.toByte() }
        val source = sourceFile(sourceFs, "/source/corrupt.bin", bytes)
        var corrupted = false
        val pair = enginePair(
            sourceFs,
            destinationFs,
            senderInterceptor = { message ->
                if (message is FileChunk && !corrupted) {
                    corrupted = true
                    message.copy(bytes = message.bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
                } else {
                    message
                }
            },
        )

        val job = pair.sender.offer(listOf(source), jobId = "corrupt")
        runCurrent()

        assertTrue(corrupted)
        assertEquals(TransferState.FAILED, job.progress.value.state)
        assertEquals(TransferState.FAILED, pair.receiver.job("corrupt")!!.progress.value.state)
        assertFalse(destinationFs.exists("/received/corrupt.bin".toPath()))
        assertFalse(destinationFs.exists("/received/corrupt.bin.part".toPath()))
    }

    @Test
    fun senderCancellationStopsWithinChunkBoundaryAndCleansReceiverPart() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        val source = sourceFile(
            sourceFs,
            "/source/cancel.bin",
            ByteArray(TransferEngine.CHUNK_BYTES * 5) { it.toByte() },
        )
        lateinit var pair: EnginePair
        var chunkCount = 0
        pair = enginePair(
            sourceFs,
            destinationFs,
            afterSenderDelivery = { message ->
                if (message is FileChunk && ++chunkCount == 2) pair.sender.cancel("sender-cancel")
            },
        )

        val job = pair.sender.offer(listOf(source), jobId = "sender-cancel")
        runCurrent()
        val countAtCancel = chunkCount
        runCurrent()

        assertEquals(countAtCancel, chunkCount)
        assertEquals(TransferState.CANCELLED, job.progress.value.state)
        assertEquals(TransferState.CANCELLED, pair.receiver.job("sender-cancel")!!.progress.value.state)
        assertFalse(destinationFs.exists("/received/cancel.bin".toPath()))
        assertFalse(destinationFs.exists("/received/cancel.bin.part".toPath()))
    }

    @Test
    fun receiverCancellationStopsSenderAndCleansPart() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        val source = sourceFile(
            sourceFs,
            "/source/receiver-cancel.bin",
            ByteArray(TransferEngine.CHUNK_BYTES * 5) { (it * 7).toByte() },
        )
        lateinit var pair: EnginePair
        var chunkCount = 0
        pair = enginePair(
            sourceFs,
            destinationFs,
            afterSenderDelivery = { message ->
                if (message is FileChunk && ++chunkCount == 2) pair.receiver.cancel("receiver-cancel")
            },
        )

        val job = pair.sender.offer(listOf(source), jobId = "receiver-cancel")
        runCurrent()
        val countAtCancel = chunkCount
        runCurrent()

        assertEquals(countAtCancel, chunkCount)
        assertEquals(TransferState.CANCELLED, job.progress.value.state)
        assertEquals(TransferState.CANCELLED, pair.receiver.job("receiver-cancel")!!.progress.value.state)
        assertFalse(destinationFs.exists("/received/receiver-cancel.bin.part".toPath()))
    }

    @Test
    fun resumeContinuesFromReceiverVerifiedOffsetInsteadOfZero() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        val bytes = ByteArray(TransferEngine.CHUNK_BYTES * 12 + 123) { (it * 13).toByte() }
        val source = sourceFile(sourceFs, "/source/resume.bin", bytes)
        var deliver = true
        var deliveredChunks = 0
        var senderToReceiver: suspend (Message) -> Unit = { error("wire not installed") }
        var receiverToSender: suspend (Message) -> Unit = { error("wire not installed") }
        val sender = TransferEngine(
            destinationDir = "/unused".toPath(),
            destinationFileSystem = sourceFs,
            outbound = { senderToReceiver(it) },
            onOffer = ::acceptAll,
            clock = TestClock(),
            scope = backgroundScope,
        )
        val receiver = TransferEngine(
            destinationDir = "/received".toPath(),
            destinationFileSystem = destinationFs,
            outbound = { receiverToSender(it) },
            onOffer = ::acceptAll,
            clock = TestClock(),
            scope = backgroundScope,
        )
        senderToReceiver = { message ->
            if (message is FileChunk) {
                if (!deliver) error("transport dropped")
                deliveredChunks++
                receiver.onMessage(message)
                if (deliveredChunks == 10) deliver = false
            } else if (deliver) {
                receiver.onMessage(message)
            } else {
                error("transport dropped")
            }
        }
        receiverToSender = { message ->
            if (deliver) sender.onMessage(message) else error("transport dropped")
        }

        val job = sender.offer(listOf(source), jobId = "resume")
        runCurrent()
        assertEquals(TransferState.ACTIVE, job.progress.value.state)
        assertEquals(10L * TransferEngine.CHUNK_BYTES, destinationFs.metadata("/received/resume.bin.part".toPath()).size)

        val resumedOffsets = mutableListOf<Long>()
        deliver = true
        senderToReceiver = { message ->
            if (message is FileChunk) resumedOffsets += message.offset
            receiver.onMessage(message)
        }
        receiverToSender = sender::onMessage

        receiver.resume("resume")
        sender.resume("resume")
        runCurrent()

        assertEquals(10L * TransferEngine.CHUNK_BYTES, resumedOffsets.first())
        assertFalse(resumedOffsets.contains(0L))
        assertContentEquals(bytes, destinationFs.read("/received/resume.bin".toPath()) { readByteArray() })
        assertEquals(TransferState.DONE, job.progress.value.state)
    }

    @Test
    fun duplicatePoliciesReplaceKeepBothIncrementSkipAndExposeExistsFlags() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        destinationFs.createDirectories("/received".toPath())
        destinationFs.write("/received/name.txt".toPath()) { writeUtf8("old") }
        destinationFs.write("/received/copy.txt".toPath()) { writeUtf8("first") }
        destinationFs.write("/received/copy (2).txt".toPath()) { writeUtf8("second") }
        destinationFs.write("/received/skip.txt".toPath()) { writeUtf8("keep") }
        val sources = listOf(
            sourceFile(sourceFs, "/source/name.txt", "new".encodeToByteArray()),
            sourceFile(sourceFs, "/source/copy.txt", "third".encodeToByteArray()),
            sourceFile(sourceFs, "/source/skip.txt", "discard".encodeToByteArray()),
        )
        var existsFlags = emptyList<Boolean>()
        val sentFileIndices = mutableSetOf<Int>()
        val pair = enginePair(
            sourceFs,
            destinationFs,
            decision = { _, _, exists ->
                existsFlags = exists
                OfferDecision(
                    true,
                    listOf(DuplicatePolicy.REPLACE, DuplicatePolicy.KEEP_BOTH, DuplicatePolicy.SKIP),
                )
            },
            senderInterceptor = { message ->
                if (message is FileChunk) sentFileIndices += message.fileIndex
                message
            },
        )

        val job = pair.sender.offer(sources, jobId = "duplicates")
        runCurrent()

        assertEquals(listOf(true, true, true), existsFlags)
        assertEquals(setOf(0, 1), sentFileIndices)
        assertEquals("new", destinationFs.read("/received/name.txt".toPath()) { readUtf8() })
        assertEquals("third", destinationFs.read("/received/copy (3).txt".toPath()) { readUtf8() })
        assertEquals("keep", destinationFs.read("/received/skip.txt".toPath()) { readUtf8() })
        assertEquals(TransferState.DONE, job.progress.value.state)
        assertEquals(8, job.progress.value.completedBytes)
    }

    @Test
    fun askPolicyIsResolvedWithoutSilentOverwrite() = runTest {
        val sourceFs = FakeFileSystem()
        val destinationFs = FakeFileSystem()
        destinationFs.createDirectories("/received".toPath())
        destinationFs.write("/received/ask.txt".toPath()) { writeUtf8("old") }
        val source = sourceFile(sourceFs, "/source/ask.txt", "new".encodeToByteArray())
        val pair = enginePair(
            sourceFs,
            destinationFs,
            decision = { _, _, _ -> OfferDecision(true, listOf(DuplicatePolicy.ASK)) },
        )

        val job = pair.sender.offer(listOf(source), jobId = "ask")
        runCurrent()

        assertEquals(TransferState.FAILED, job.progress.value.state)
        assertEquals("old", destinationFs.read("/received/ask.txt".toPath()) { readUtf8() })
        assertFalse(destinationFs.exists("/received/ask.txt.part".toPath()))
    }

    @Test
    fun chunkSizeAlwaysFitsFrameLimit() {
        assertTrue(TransferEngine.CHUNK_BYTES < FrameCodec.MAX_FRAME_BYTES)
    }

    private suspend fun enginePair(
        sourceFs: FakeFileSystem,
        destinationFs: FakeFileSystem,
        decision: suspend (String, List<FileMeta>, List<Boolean>) -> OfferDecision = ::acceptAll,
        senderInterceptor: (Message) -> Message = { it },
        afterSenderDelivery: suspend (Message) -> Unit = {},
    ): EnginePair {
        val scope = CoroutineScope(currentCoroutineContext())
        lateinit var sender: TransferEngine
        lateinit var receiver: TransferEngine
        sender = TransferEngine(
            destinationDir = "/unused".toPath(),
            destinationFileSystem = sourceFs,
            outbound = { message ->
                val delivered = senderInterceptor(message)
                receiver.onMessage(delivered)
                afterSenderDelivery(delivered)
            },
            onOffer = ::acceptAll,
            clock = TestClock(),
            scope = scope,
        )
        receiver = TransferEngine(
            destinationDir = "/received".toPath(),
            destinationFileSystem = destinationFs,
            outbound = sender::onMessage,
            onOffer = decision,
            clock = TestClock(),
            scope = scope,
        )
        return EnginePair(sender, receiver)
    }

    private fun sourceFile(fileSystem: FakeFileSystem, pathString: String, bytes: ByteArray): SourceFile {
        val path = pathString.toPath()
        fileSystem.createDirectories(path.parent!!)
        fileSystem.write(path) { write(bytes) }
        return SourceFile(
            path = path,
            fileSystem = fileSystem,
            meta = FileMeta(path.name, bytes.size.toLong(), "application/octet-stream"),
        )
    }

    private data class EnginePair(val sender: TransferEngine, val receiver: TransferEngine)
}

private suspend fun acceptAll(
    jobId: String,
    files: List<FileMeta>,
    exists: List<Boolean>,
): OfferDecision {
    jobId.length
    exists.size
    return OfferDecision(true, List(files.size) { DuplicatePolicy.KEEP_BOTH })
}

private class TestClock(var now: Long = 0) : Clock {
    override fun nowMs(): Long = now++
}
