package dev.opentomac.shared.transfer

import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.pairing.SystemClock
import dev.opentomac.shared.protocol.DuplicatePolicy
import dev.opentomac.shared.protocol.FileCancel
import dev.opentomac.shared.protocol.FileChunk
import dev.opentomac.shared.protocol.FileChunkAck
import dev.opentomac.shared.protocol.FileDone
import dev.opentomac.shared.protocol.FileMeta
import dev.opentomac.shared.protocol.FileOffer
import dev.opentomac.shared.protocol.FileOfferReply
import dev.opentomac.shared.protocol.FrameCodec
import dev.opentomac.shared.protocol.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.buffer
import okio.use
import kotlin.math.min

data class SourceFile(
    val path: Path,
    val fileSystem: FileSystem,
    val meta: FileMeta,
)

data class OfferDecision(
    val accepted: Boolean,
    val perFilePolicy: List<DuplicatePolicy>,
)

class TransferEngine(
    private val destinationDir: Path,
    private val destinationFileSystem: FileSystem,
    private val outbound: suspend (Message) -> Unit,
    private val onOffer: suspend (
        jobId: String,
        files: List<FileMeta>,
        targetExists: List<Boolean>,
    ) -> OfferDecision,
    private val clock: Clock = SystemClock,
    private val scope: CoroutineScope,
) {
    constructor(
        destinationDir: Path,
        destinationFileSystem: FileSystem,
        outbound: suspend (Message) -> Unit,
        onOffer: suspend (jobId: String, files: List<FileMeta>) -> OfferDecision,
        clock: Clock = SystemClock,
        scope: CoroutineScope,
    ) : this(
        destinationDir = destinationDir,
        destinationFileSystem = destinationFileSystem,
        outbound = outbound,
        onOffer = { jobId, files, _ -> onOffer(jobId, files) },
        clock = clock,
        scope = scope,
    )

    private val jobs = mutableMapOf<String, TransferJob>()
    private val sends = mutableMapOf<String, SendTransfer>()
    private val receives = mutableMapOf<String, ReceiveTransfer>()
    private var nextJobNumber = 0L

    suspend fun offer(
        files: List<SourceFile>,
        jobId: String = "transfer-${clock.nowMs()}-${nextJobNumber++}",
    ): TransferJob {
        require(jobId.isNotBlank()) { "jobId must not be blank" }
        require(jobId !in jobs) { "Transfer job $jobId already exists" }
        files.forEach { source ->
            require(source.meta.sizeBytes >= 0) { "File size must not be negative: ${source.meta.name}" }
        }
        val job = TransferJob(jobId, TransferDirection.SEND, files.map { it.meta }, clock)
        jobs[jobId] = job
        sends[jobId] = SendTransfer(job, files)
        outbound(FileOffer(jobId, job.files))
        return job
    }

    fun job(jobId: String): TransferJob? = jobs[jobId]

    suspend fun onMessage(msg: Message) {
        when (msg) {
            is FileOffer -> receiveOffer(msg)
            is FileOfferReply -> receiveOfferReply(msg)
            is FileChunk -> receiveChunk(msg)
            is FileChunkAck -> receiveAck(msg)
            is FileDone -> receiveDone(msg)
            is FileCancel -> receiveCancel(msg)
            else -> Unit
        }
    }

    suspend fun cancel(jobId: String, reason: String = "cancelled") {
        val job = jobs[jobId] ?: return
        if (job.progress.value.state.isTerminal) return
        if (job.direction == TransferDirection.RECEIVE) cleanupParts(receives[jobId])
        job.update(state = TransferState.CANCELLED)
        sends[jobId]?.task?.cancel()
        runCatching { outbound(FileCancel(jobId, reason)) }
    }

    suspend fun resume(jobId: String) {
        val job = jobs[jobId] ?: return
        if (job.progress.value.state.isTerminal) return
        when (job.direction) {
            TransferDirection.SEND -> {
                val transfer = sends[jobId] ?: return
                transfer.task?.cancel()
                transfer.awaitingResumeReply = true
                outbound(FileOffer(jobId, job.files))
            }

            TransferDirection.RECEIVE -> {
                val transfer = receives[jobId] ?: return
                closeSinks(transfer)
                transfer.partPaths.forEachIndexed { index, path ->
                    if (transfer.completed[index] || transfer.policies[index] == DuplicatePolicy.SKIP) {
                        return@forEachIndexed
                    }
                    val actualSize = if (destinationFileSystem.exists(path)) {
                        destinationFileSystem.metadata(path).size ?: 0
                    } else {
                        0
                    }
                    if (actualSize > transfer.job.files[index].sizeBytes) {
                        deleteIfExists(path)
                        transfer.offsets[index] = 0
                    } else {
                        transfer.offsets[index] = actualSize
                    }
                    transfer.lastAcked[index] = transfer.offsets[index]
                }
                transfer.resumable = true
            }
        }
    }

    suspend fun resume(job: TransferJob) = resume(job.jobId)

    private suspend fun receiveOffer(offer: FileOffer) {
        val resumed = receives[offer.jobId]
        if (resumed != null && resumed.job.files == offer.files && resumed.resumable) {
            outbound(FileOfferReply(offer.jobId, accepted = true, resumed.policies))
            resumed.offsets.forEachIndexed { index, offset ->
                if (resumed.policies[index] != DuplicatePolicy.SKIP && offset > 0) {
                    outbound(FileChunkAck(offer.jobId, index, offset))
                }
            }
            return
        }

        if (offer.jobId in jobs) {
            outbound(FileOfferReply(offer.jobId, accepted = false))
            return
        }

        destinationFileSystem.createDirectories(destinationDir)
        val originalTargets = offer.files.map { destinationDir / it.name }
        val exists = originalTargets.map(destinationFileSystem::exists)
        val decision = onOffer(offer.jobId, offer.files, exists)
        val validPolicies = decision.perFilePolicy.size == offer.files.size &&
            decision.perFilePolicy.none { it == DuplicatePolicy.ASK }
        val accepted = decision.accepted && validPolicies
        val job = TransferJob(offer.jobId, TransferDirection.RECEIVE, offer.files, clock)
        jobs[offer.jobId] = job
        if (!accepted) {
            job.fail(if (decision.accepted) "unresolved duplicate policy" else "declined")
            outbound(FileOfferReply(offer.jobId, accepted = false))
            return
        }

        val reserved = mutableSetOf<Path>()
        val finalPaths = offer.files.mapIndexed { index, meta ->
            when (decision.perFilePolicy[index]) {
                DuplicatePolicy.REPLACE -> originalTargets[index]
                DuplicatePolicy.KEEP_BOTH -> chooseKeepBoth(originalTargets[index], reserved)
                DuplicatePolicy.SKIP -> originalTargets[index]
                DuplicatePolicy.ASK -> error("ASK was rejected above")
            }.also(reserved::add)
        }
        val transfer = ReceiveTransfer(
            job = job,
            policies = decision.perFilePolicy,
            finalPaths = finalPaths,
            partPaths = finalPaths.map { it.parent!! / "${it.name}.part" },
            offsets = LongArray(offer.files.size),
            lastAcked = LongArray(offer.files.size),
            completed = BooleanArray(offer.files.size) { decision.perFilePolicy[it] == DuplicatePolicy.SKIP },
            hashingSinks = arrayOfNulls(offer.files.size),
            bufferedSinks = arrayOfNulls(offer.files.size),
        )
        receives[offer.jobId] = transfer
        val effectiveTotal = effectiveTotal(offer.files, decision.perFilePolicy)
        job.update(state = TransferState.ACTIVE, totalBytes = effectiveTotal)
        outbound(FileOfferReply(offer.jobId, accepted = true, decision.perFilePolicy))
        if (transfer.completed.all { it }) job.update(state = TransferState.DONE)
    }

    private fun receiveOfferReply(reply: FileOfferReply) {
        val transfer = sends[reply.jobId] ?: return
        if (transfer.job.progress.value.state.isTerminal) return
        if (!reply.accepted) {
            transfer.job.fail("declined")
            return
        }
        if (reply.perFilePolicy.size != transfer.sources.size) {
            transfer.job.fail("invalid per-file policy count")
            return
        }
        if (transfer.awaitingResumeReply) {
            transfer.ackedThrough.fill(0)
            transfer.sentThrough.fill(0)
            transfer.doneSent.fill(false)
            transfer.verifiedComplete.fill(false)
            transfer.awaitingResumeReply = false
        }
        transfer.policies = reply.perFilePolicy
        transfer.job.update(
            completedBytes = senderCompletedBytes(transfer),
            state = TransferState.ACTIVE,
            totalBytes = effectiveTotal(transfer.job.files, reply.perFilePolicy),
        )
        transfer.task?.cancel()
        transfer.task = scope.launch { streamFiles(transfer) }
    }

    private suspend fun streamFiles(transfer: SendTransfer) {
        try {
            transfer.sources.forEachIndexed { index, sourceFile ->
                if (transfer.job.progress.value.state != TransferState.ACTIVE) return
                if (transfer.policies[index] == DuplicatePolicy.SKIP) return@forEachIndexed
                if (transfer.verifiedComplete[index]) return@forEachIndexed
                transfer.doneSent[index] = false
                val startOffset = transfer.ackedThrough[index].coerceIn(0, sourceFile.meta.sizeBytes)
                transfer.sentThrough[index] = startOffset
                transfer.job.update(
                    completedBytes = senderCompletedBytes(transfer),
                    currentFileIndex = index,
                )
                val hashingSource = HashingSource.sha256(sourceFile.fileSystem.source(sourceFile.path))
                hashingSource.buffer().use { source ->
                    if (startOffset > 0) source.skip(startOffset)
                    var offset = startOffset
                    while (offset < sourceFile.meta.sizeBytes) {
                        if (transfer.job.progress.value.state != TransferState.ACTIVE) return
                        val byteCount = min(CHUNK_BYTES.toLong(), sourceFile.meta.sizeBytes - offset)
                        val bytes = source.readByteArray(byteCount)
                        if (bytes.isEmpty()) error("Source ${sourceFile.path} ended before ${sourceFile.meta.sizeBytes} bytes")
                        outbound(FileChunk(transfer.job.jobId, index, offset, bytes))
                        if (transfer.job.progress.value.state != TransferState.ACTIVE) return
                        offset += bytes.size
                        transfer.sentThrough[index] = offset
                        transfer.job.update(completedBytes = senderCompletedBytes(transfer))
                    }
                }
                if (transfer.job.progress.value.state != TransferState.ACTIVE) return
                transfer.doneSent[index] = true
                outbound(FileDone(transfer.job.jobId, index, hashingSource.hash.toByteArray()))
            }
            maybeCompleteSend(transfer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Transport failures intentionally keep the job ACTIVE so resume(jobId) can continue it.
        }
    }

    private suspend fun receiveChunk(chunk: FileChunk) {
        val transfer = receives[chunk.jobId] ?: return
        if (transfer.job.progress.value.state != TransferState.ACTIVE) return
        if (chunk.fileIndex !in transfer.job.files.indices) {
            failReceive(transfer, "invalid file index")
            return
        }
        val index = chunk.fileIndex
        val meta = transfer.job.files[index]
        if (
            transfer.policies[index] == DuplicatePolicy.SKIP ||
            transfer.completed[index] ||
            chunk.offset != transfer.offsets[index] ||
            chunk.bytes.size > CHUNK_BYTES ||
            chunk.offset + chunk.bytes.size > meta.sizeBytes
        ) {
            failReceive(transfer, "invalid chunk")
            return
        }
        val sink = ensureSink(transfer, index)
        sink.write(chunk.bytes)
        sink.flush()
        transfer.offsets[index] += chunk.bytes.size
        transfer.job.update(
            completedBytes = receiverCompletedBytes(transfer),
            currentFileIndex = index,
        )
        if (transfer.offsets[index] - transfer.lastAcked[index] >= ACK_EVERY_BYTES) {
            transfer.lastAcked[index] = transfer.offsets[index]
            outbound(FileChunkAck(chunk.jobId, index, transfer.offsets[index]))
        }
    }

    private fun receiveAck(ack: FileChunkAck) {
        val transfer = sends[ack.jobId] ?: return
        if (ack.fileIndex !in transfer.sources.indices) return
        val size = transfer.sources[ack.fileIndex].meta.sizeBytes
        if (ack.verifiedThrough !in 0..size) return
        transfer.ackedThrough[ack.fileIndex] = maxOf(
            transfer.ackedThrough[ack.fileIndex],
            ack.verifiedThrough,
        )
        if (transfer.sentThrough[ack.fileIndex] < ack.verifiedThrough) {
            transfer.sentThrough[ack.fileIndex] = ack.verifiedThrough
        }
        if (transfer.doneSent[ack.fileIndex] && ack.verifiedThrough == size) {
            transfer.verifiedComplete[ack.fileIndex] = true
        }
        if (!transfer.job.progress.value.state.isTerminal) {
            transfer.job.update(completedBytes = senderCompletedBytes(transfer))
            maybeCompleteSend(transfer)
        }
    }

    private suspend fun receiveDone(done: FileDone) {
        val transfer = receives[done.jobId] ?: return
        if (transfer.job.progress.value.state != TransferState.ACTIVE) return
        if (done.fileIndex !in transfer.job.files.indices) {
            failReceive(transfer, "invalid file index")
            return
        }
        val index = done.fileIndex
        if (transfer.completed[index]) {
            outbound(FileChunkAck(done.jobId, index, transfer.offsets[index]))
            return
        }
        if (
            transfer.policies[index] == DuplicatePolicy.SKIP ||
            transfer.offsets[index] != transfer.job.files[index].sizeBytes
        ) {
            failReceive(transfer, "incomplete file")
            return
        }
        val sink = ensureSink(transfer, index)
        sink.close()
        transfer.bufferedSinks[index] = null
        val hashingSink = transfer.hashingSinks[index]!!
        transfer.hashingSinks[index] = null
        val actualHash = if (transfer.reopened[index]) {
            hashFile(transfer.partPaths[index])
        } else {
            hashingSink.hash.toByteArray()
        }
        if (!actualHash.contentEquals(done.sha256)) {
            deleteIfExists(transfer.partPaths[index])
            failReceive(transfer, "integrity")
            return
        }

        val finalPath = transfer.finalPaths[index]
        if (transfer.policies[index] == DuplicatePolicy.REPLACE) {
            deleteIfExists(finalPath)
        } else if (destinationFileSystem.exists(finalPath)) {
            failReceive(transfer, "destination appeared during transfer")
            return
        }
        destinationFileSystem.atomicMove(transfer.partPaths[index], finalPath)
        transfer.completed[index] = true
        transfer.lastAcked[index] = transfer.offsets[index]
        outbound(FileChunkAck(done.jobId, index, transfer.offsets[index]))
        if (transfer.completed.all { it }) {
            transfer.job.update(
                completedBytes = transfer.job.progress.value.totalBytes,
                state = TransferState.DONE,
            )
        }
    }

    private suspend fun failReceive(transfer: ReceiveTransfer, reason: String) {
        cleanupParts(transfer)
        transfer.job.fail(reason)
        runCatching { outbound(FileCancel(transfer.job.jobId, reason)) }
    }

    private fun receiveCancel(cancel: FileCancel) {
        val job = jobs[cancel.jobId] ?: return
        if (job.progress.value.state.isTerminal) return
        sends[cancel.jobId]?.task?.cancel()
        if (job.direction == TransferDirection.RECEIVE) cleanupParts(receives[cancel.jobId])
        if (cancel.reason == "integrity" || cancel.reason.startsWith("invalid") || cancel.reason == "incomplete file") {
            job.fail(cancel.reason)
        } else {
            job.update(state = TransferState.CANCELLED)
        }
    }

    private fun ensureSink(transfer: ReceiveTransfer, index: Int): BufferedSink {
        transfer.bufferedSinks[index]?.let { return it }
        val partPath = transfer.partPaths[index]
        val offset = transfer.offsets[index]
        val rawSink = if (offset > 0 && transfer.resumable && destinationFileSystem.exists(partPath)) {
            transfer.reopened[index] = true
            destinationFileSystem.appendingSink(partPath)
        } else {
            transfer.offsets[index] = 0
            transfer.lastAcked[index] = 0
            destinationFileSystem.sink(partPath)
        }
        val hashingSink = HashingSink.sha256(rawSink)
        return hashingSink.buffer().also {
            transfer.hashingSinks[index] = hashingSink
            transfer.bufferedSinks[index] = it
        }
    }

    private fun hashFile(path: Path): ByteArray {
        val hashingSource = HashingSource.sha256(destinationFileSystem.source(path))
        hashingSource.buffer().use { source ->
            val scratch = Buffer()
            while (source.read(scratch, CHUNK_BYTES.toLong()) != -1L) scratch.clear()
        }
        return hashingSource.hash.toByteArray()
    }

    private fun chooseKeepBoth(original: Path, reserved: Set<Path>): Path {
        if (!destinationFileSystem.exists(original) && original !in reserved) return original
        val name = original.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = original.parent!! / "$stem ($suffix)$extension"
            if (!destinationFileSystem.exists(candidate) && candidate !in reserved) return candidate
            suffix++
        }
    }

    private fun cleanupParts(transfer: ReceiveTransfer?) {
        if (transfer == null) return
        closeSinks(transfer)
        transfer.partPaths.forEachIndexed { index, path ->
            if (!transfer.completed[index]) deleteIfExists(path)
        }
    }

    private fun closeSinks(transfer: ReceiveTransfer) {
        transfer.bufferedSinks.forEachIndexed { index, sink ->
            runCatching { sink?.close() }
            transfer.bufferedSinks[index] = null
            transfer.hashingSinks[index] = null
        }
    }

    private fun deleteIfExists(path: Path) {
        if (destinationFileSystem.exists(path)) destinationFileSystem.delete(path)
    }

    private fun senderCompletedBytes(transfer: SendTransfer): Long =
        transfer.sentThrough.indices.sumOf { index ->
            if (transfer.policies.getOrNull(index) == DuplicatePolicy.SKIP) 0 else transfer.sentThrough[index]
        }

    private fun maybeCompleteSend(transfer: SendTransfer) {
        if (
            transfer.job.progress.value.state == TransferState.ACTIVE &&
            transfer.sources.indices.all { index ->
                transfer.policies[index] == DuplicatePolicy.SKIP || transfer.verifiedComplete[index]
            }
        ) {
            transfer.job.update(
                completedBytes = transfer.job.progress.value.totalBytes,
                state = TransferState.DONE,
            )
        }
    }

    private fun receiverCompletedBytes(transfer: ReceiveTransfer): Long =
        transfer.offsets.indices.sumOf { index ->
            if (transfer.policies[index] == DuplicatePolicy.SKIP) 0 else transfer.offsets[index]
        }

    private fun effectiveTotal(files: List<FileMeta>, policies: List<DuplicatePolicy>): Long =
        files.indices.sumOf { index ->
            if (policies[index] == DuplicatePolicy.SKIP) 0 else files[index].sizeBytes
        }

    private data class SendTransfer(
        val job: TransferJob,
        val sources: List<SourceFile>,
        var policies: List<DuplicatePolicy> = emptyList(),
        val ackedThrough: LongArray = LongArray(sources.size),
        val sentThrough: LongArray = LongArray(sources.size),
        val doneSent: BooleanArray = BooleanArray(sources.size),
        val verifiedComplete: BooleanArray = BooleanArray(sources.size),
        var awaitingResumeReply: Boolean = false,
        var task: Job? = null,
    )

    private data class ReceiveTransfer(
        val job: TransferJob,
        val policies: List<DuplicatePolicy>,
        val finalPaths: List<Path>,
        val partPaths: List<Path>,
        val offsets: LongArray,
        val lastAcked: LongArray,
        val completed: BooleanArray,
        val hashingSinks: Array<HashingSink?>,
        val bufferedSinks: Array<BufferedSink?>,
        val reopened: BooleanArray = BooleanArray(job.files.size),
        var resumable: Boolean = false,
    )

    companion object {
        const val CHUNK_BYTES: Int = 1 shl 20
        const val ACK_EVERY_BYTES: Long = 8L * 1024 * 1024

        init {
            check(CHUNK_BYTES < FrameCodec.MAX_FRAME_BYTES)
        }
    }
}

private val TransferState.isTerminal: Boolean
    get() = this == TransferState.DONE || this == TransferState.FAILED || this == TransferState.CANCELLED
