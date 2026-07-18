package dev.opentomac.shared.messaging

import dev.opentomac.shared.protocol.CallLogEntry
import dev.opentomac.shared.protocol.CallLogRequest
import dev.opentomac.shared.protocol.CallLogResponse
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.SmsMessage
import dev.opentomac.shared.protocol.SmsSendRequest
import dev.opentomac.shared.protocol.SmsSendResult
import dev.opentomac.shared.protocol.SmsThread
import dev.opentomac.shared.protocol.SmsThreadRequest
import dev.opentomac.shared.protocol.SmsThreadResponse
import dev.opentomac.shared.protocol.SmsThreadsRequest
import dev.opentomac.shared.protocol.SmsThreadsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

interface MessagingSource {
    /** Returns null when SMS read permission is missing. */
    suspend fun threads(limit: Int): List<SmsThread>?

    /** Returns the address and messages, or null when SMS read permission is missing. */
    suspend fun thread(threadId: String, limit: Int): Pair<String, List<SmsMessage>>?

    /**
     * Requests on-phone confirmation and returns immediately. The callback fires only
     * after the phone user cancels or Android reports the final sent status.
     */
    suspend fun send(
        id: String,
        address: String,
        body: String,
        onResult: suspend (Result<Unit>) -> Unit,
    )

    /** Returns null when call-log read permission is missing. */
    suspend fun callLog(limit: Int): List<CallLogEntry>?
}

/** Phone-side on-demand messaging and call-log role backed by platform providers. */
class MessagingAgent(
    private val source: MessagingSource,
    private val send: suspend (Message) -> Unit,
) {
    private val sendStateMutex = Mutex()
    private val sendStates = LinkedHashMap<String, SendState>()

    suspend fun onMessage(msg: Message) {
        when (msg) {
            is SmsThreadsRequest -> {
                val result = source.threads(msg.limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
                send(SmsThreadsResponse(threads = result.orEmpty(), granted = result != null))
            }
            is SmsThreadRequest -> {
                val result = source.thread(msg.threadId, msg.limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
                send(
                    SmsThreadResponse(
                        threadId = msg.threadId,
                        address = result?.first.orEmpty(),
                        messages = result?.second.orEmpty(),
                        granted = result != null,
                    ),
                )
            }
            is SmsSendRequest -> handleSend(msg)
            is CallLogRequest -> {
                val result = source.callLog(msg.limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
                send(CallLogResponse(entries = result.orEmpty(), granted = result != null))
            }
            else -> Unit
        }
    }

    private suspend fun handleSend(request: SmsSendRequest) {
        if (request.id.isBlank()) {
            send(sendResult(request, Result.failure(IllegalArgumentException("missing SMS operation id"))))
            return
        }

        var existing: SendState? = null
        var shouldStart = false
        val rejected = sendStateMutex.withLock {
            existing = sendStates[request.id]
            if (existing == null) {
                if (sendStates.values.count { it is SendState.Pending } >= MAX_PENDING_SENDS) {
                    true
                } else {
                    sendStates[request.id] = SendState.Pending
                    trimSendStates()
                    shouldStart = true
                    false
                }
            } else {
                false
            }
        }

        if (rejected) {
            send(sendResult(request, Result.failure(IllegalStateException("too many pending SMS requests"))))
            return
        }
        when (val state = existing) {
            is SendState.Done -> send(state.result)
            SendState.Pending -> Unit
            null -> if (shouldStart) {
                try {
                    source.send(request.id, request.address, request.body) { result ->
                        completeSend(request, result)
                    }
                } catch (cause: Throwable) {
                    completeSend(request, Result.failure(cause))
                }
            }
        }
    }

    private suspend fun completeSend(request: SmsSendRequest, result: Result<Unit>) {
        val response = sendResult(request, result)
        val ownsCompletion = sendStateMutex.withLock {
            if (sendStates[request.id] !== SendState.Pending) {
                false
            } else {
                sendStates[request.id] = SendState.Done(response)
                trimSendStates()
                true
            }
        }
        if (ownsCompletion) send(response)
    }

    private fun sendResult(request: SmsSendRequest, result: Result<Unit>) = SmsSendResult(
        address = request.address,
        sent = result.isSuccess,
        error = result.exceptionOrNull()?.message.orEmpty(),
        id = request.id,
    )

    private fun trimSendStates() {
        while (sendStates.size > MAX_RECENT_SENDS) {
            val completedKey = sendStates.entries.firstOrNull { it.value is SendState.Done }?.key ?: return
            sendStates.remove(completedKey)
        }
    }

    private sealed interface SendState {
        data object Pending : SendState
        data class Done(val result: SmsSendResult) : SendState
    }

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 200
        const val MAX_PENDING_SENDS = 5
        const val MAX_RECENT_SENDS = 64
    }
}

/** Desktop-side on-demand requester with one in-flight request per response key. */
class MessagingCompanion(
    private val send: suspend (Message) -> Unit,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val sendIdFactory: () -> String = ::randomSendId,
) {
    private val stateMutex = Mutex()
    private val pending = mutableMapOf<RequestKey, CompletableDeferred<Message>>()

    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
    }

    suspend fun threads(limit: Int = DEFAULT_THREADS_LIMIT): SmsThreadsResponse = request(
        key = RequestKey.Threads,
        message = SmsThreadsRequest(limit),
    )

    suspend fun thread(threadId: String, limit: Int = DEFAULT_MESSAGES_LIMIT): SmsThreadResponse = request(
        key = RequestKey.Thread(threadId),
        message = SmsThreadRequest(threadId, limit),
    )

    suspend fun sendSms(address: String, body: String): SmsSendResult {
        val id = sendIdFactory()
        require(id.isNotBlank()) { "SMS operation id must not be blank" }
        return try {
            request(
                key = RequestKey.Send(id),
                message = SmsSendRequest(address, body, id),
            )
        } catch (_: TimeoutCancellationException) {
            SmsSendResult(address, sent = false, error = UNKNOWN_SEND_OUTCOME, id = id)
        }
    }

    suspend fun callLog(limit: Int = DEFAULT_CALL_LOG_LIMIT): CallLogResponse = request(
        key = RequestKey.CallLog,
        message = CallLogRequest(limit),
    )

    /** Completes requests from inbound BULK messages. */
    suspend fun onMessage(msg: Message) {
        val key = when (msg) {
            is SmsThreadsResponse -> RequestKey.Threads
            is SmsThreadResponse -> RequestKey.Thread(msg.threadId)
            is SmsSendResult -> RequestKey.Send(msg.id)
            is CallLogResponse -> RequestKey.CallLog
            else -> return
        }
        stateMutex.withLock {
            pending.remove(key)?.complete(msg)
        }
    }

    private suspend inline fun <reified T : Message> request(
        key: RequestKey,
        message: Message,
    ): T {
        var shouldSend = false
        val deferred = stateMutex.withLock {
            pending[key] ?: CompletableDeferred<Message>().also {
                pending[key] = it
                shouldSend = true
            }
        }

        return try {
            val response = withTimeout(requestTimeoutMillis) {
                if (shouldSend) send(message)
                deferred.await()
            }
            response as? T ?: error("unexpected response ${response::class.simpleName} for $key")
        } catch (cause: Throwable) {
            failPending(key, deferred, cause)
            throw cause
        }
    }

    private suspend fun failPending(
        key: RequestKey,
        deferred: CompletableDeferred<Message>,
        cause: Throwable,
    ) = withContext(NonCancellable) {
        stateMutex.withLock {
            if (pending[key] === deferred) {
                pending.remove(key)
                val sharedCause = if (cause is CancellationException) {
                    IllegalStateException("messaging request abandoned", cause)
                } else {
                    cause
                }
                deferred.completeExceptionally(sharedCause)
            }
        }
    }

    private sealed interface RequestKey {
        data object Threads : RequestKey
        data class Thread(val threadId: String) : RequestKey
        data class Send(val id: String) : RequestKey
        data object CallLog : RequestKey
    }

    companion object {
        const val DEFAULT_THREADS_LIMIT = 50
        const val DEFAULT_MESSAGES_LIMIT = 100
        const val DEFAULT_CALL_LOG_LIMIT = 50
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val UNKNOWN_SEND_OUTCOME = "unknown, check your phone"
    }
}

private fun randomSendId(): String {
    val bytes = Random.nextBytes(16)
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}
