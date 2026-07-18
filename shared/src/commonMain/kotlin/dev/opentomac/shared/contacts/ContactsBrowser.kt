package dev.opentomac.shared.contacts

import dev.opentomac.shared.protocol.ContactItem
import dev.opentomac.shared.protocol.ContactsSearchRequest
import dev.opentomac.shared.protocol.ContactsSearchResponse
import dev.opentomac.shared.protocol.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

interface ContactsSource {
    /** Returns null when contact permission is missing. */
    suspend fun search(query: String, limit: Int): List<ContactItem>?
}

/** Phone-side contact search role backed by the platform contacts provider. */
class ContactsAgent(
    private val source: ContactsSource,
    private val send: suspend (Message) -> Unit,
) {
    suspend fun onMessage(msg: Message) {
        if (msg !is ContactsSearchRequest) return

        val result = if (msg.query.isBlank()) {
            emptyList()
        } else {
            source.search(msg.query, msg.limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
        }
        send(
            ContactsSearchResponse(
                query = msg.query,
                items = result.orEmpty(),
                granted = result != null,
            ),
        )
    }

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
    }
}

/** Desktop-side contact search requester with one in-flight request per query. */
class ContactsCompanion(
    private val send: suspend (Message) -> Unit,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    private val stateMutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<ContactsSearchResponse>>()

    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
    }

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): ContactsSearchResponse {
        var shouldSend = false
        val deferred = stateMutex.withLock {
            pending[query] ?: CompletableDeferred<ContactsSearchResponse>().also {
                pending[query] = it
                shouldSend = true
            }
        }

        return try {
            withTimeout(requestTimeoutMillis) {
                if (shouldSend) send(ContactsSearchRequest(query, limit))
                deferred.await()
            }
        } catch (cause: Throwable) {
            failPending(query, deferred, cause)
            throw cause
        }
    }

    /** Completes searches from inbound BULK messages. */
    suspend fun onMessage(msg: Message) {
        if (msg !is ContactsSearchResponse) return
        stateMutex.withLock {
            pending.remove(msg.query)?.complete(msg)
        }
    }

    private suspend fun failPending(
        query: String,
        deferred: CompletableDeferred<ContactsSearchResponse>,
        cause: Throwable,
    ) = withContext(NonCancellable) {
        stateMutex.withLock {
            if (pending[query] === deferred) {
                pending.remove(query)
                val sharedCause = if (cause is CancellationException) {
                    IllegalStateException("contact search abandoned", cause)
                } else {
                    cause
                }
                deferred.completeExceptionally(sharedCause)
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
    }
}
