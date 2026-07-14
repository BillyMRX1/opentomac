package dev.opentomac.shared.transfer

import dev.opentomac.shared.pairing.Clock
import dev.opentomac.shared.protocol.FileMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransferDirection {
    SEND,
    RECEIVE,
}

enum class TransferState {
    OFFERED,
    ACTIVE,
    DONE,
    FAILED,
    CANCELLED,
}

data class TransferProgress(
    val totalBytes: Long,
    val completedBytes: Long,
    val currentFileIndex: Int,
    val throughputBytesPerSec: Long,
    val etaMs: Long,
    val state: TransferState,
)

class TransferJob internal constructor(
    val jobId: String,
    val direction: TransferDirection,
    val files: List<FileMeta>,
    private val clock: Clock,
) {
    private val startedAtMs = clock.nowMs()
    private var measuredFromBytes = 0L
    private var measuredFromMs = startedAtMs
    private val mutableProgress = MutableStateFlow(
        TransferProgress(
            totalBytes = files.sumOf { it.sizeBytes },
            completedBytes = 0,
            currentFileIndex = if (files.isEmpty()) -1 else 0,
            throughputBytesPerSec = 0,
            etaMs = 0,
            state = TransferState.OFFERED,
        ),
    )

    val progress: StateFlow<TransferProgress> = mutableProgress.asStateFlow()

    var failureReason: String? = null
        internal set

    internal fun update(
        completedBytes: Long = mutableProgress.value.completedBytes,
        currentFileIndex: Int = mutableProgress.value.currentFileIndex,
        state: TransferState = mutableProgress.value.state,
        totalBytes: Long = mutableProgress.value.totalBytes,
    ) {
        val safeTotal = totalBytes.coerceAtLeast(0)
        val safeCompleted = completedBytes.coerceIn(0, safeTotal)
        val nowMs = clock.nowMs()
        val elapsedMs = nowMs - measuredFromMs
        val byteDelta = safeCompleted - measuredFromBytes
        val throughput = if (elapsedMs > 0 && byteDelta > 0) {
            byteDelta * 1_000 / elapsedMs
        } else {
            mutableProgress.value.throughputBytesPerSec
        }
        if (elapsedMs >= SAMPLE_WINDOW_MS || safeCompleted < measuredFromBytes) {
            measuredFromMs = nowMs
            measuredFromBytes = safeCompleted
        }
        val remaining = (safeTotal - safeCompleted).coerceAtLeast(0)
        val etaMs = if (throughput > 0 && remaining > 0) remaining * 1_000 / throughput else 0
        mutableProgress.value = TransferProgress(
            totalBytes = safeTotal,
            completedBytes = safeCompleted,
            currentFileIndex = currentFileIndex,
            throughputBytesPerSec = if (state == TransferState.DONE) 0 else throughput,
            etaMs = if (state == TransferState.DONE) 0 else etaMs,
            state = state,
        )
    }

    internal fun fail(reason: String) {
        failureReason = reason
        update(state = TransferState.FAILED)
    }

    private companion object {
        const val SAMPLE_WINDOW_MS = 500L
    }
}
