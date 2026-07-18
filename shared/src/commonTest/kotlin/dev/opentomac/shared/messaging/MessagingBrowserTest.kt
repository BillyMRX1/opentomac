@file:OptIn(ExperimentalCoroutinesApi::class)

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
import dev.opentomac.shared.protocol.ThumbnailRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class MessagingBrowserTest {

    @Test
    fun requestsAndResponsesRoundTripThroughBothRoles(): Unit = runTest {
        val thread = SmsThread("7", "+15551234", "Grace", "Hello", 900, unread = true)
        val message = SmsMessage("Hello", 900, incoming = true)
        val call = CallLogEntry("+15559876", "Ada", "missed", 800, 0)
        val source = FakeMessagingSource(
            threadsResult = listOf(thread),
            threadResult = "+15551234" to listOf(message),
            sendResult = Result.success(Unit),
            callLogResult = listOf(call),
        )
        lateinit var agent: MessagingAgent
        lateinit var companion: MessagingCompanion
        agent = MessagingAgent(source) { companion.onMessage(it) }
        companion = MessagingCompanion(
            send = { agent.onMessage(it) },
            sendIdFactory = { "sms-op-1" },
        )

        assertEquals(SmsThreadsResponse(listOf(thread)), companion.threads(12))
        assertEquals(SmsThreadResponse("7", "+15551234", listOf(message)), companion.thread("7", 24))
        assertEquals(
            SmsSendResult("+15551234", sent = true, id = "sms-op-1"),
            companion.sendSms("+15551234", "Reply"),
        )
        assertEquals(CallLogResponse(listOf(call)), companion.callLog(36))
        assertEquals(
            listOf(
                SourceCall.Threads(12),
                SourceCall.Thread("7", 24),
                SourceCall.Send("sms-op-1", "+15551234", "Reply"),
                SourceCall.CallLog(36),
            ),
            source.calls,
        )
    }

    @Test
    fun permissionDeniedReturnsGrantedFalseAndSendFailure(): Unit = runTest {
        val sent = mutableListOf<Message>()
        val source = FakeMessagingSource(
            threadsResult = null,
            threadResult = null,
            sendResult = Result.failure(SecurityException("SMS permission missing")),
            callLogResult = null,
        )
        val agent = MessagingAgent(source, sent::add)

        agent.onMessage(SmsThreadsRequest(20))
        agent.onMessage(SmsThreadRequest("5", 20))
        agent.onMessage(SmsSendRequest("+15551234", "Reply", "sms-op-denied"))
        agent.onMessage(CallLogRequest(20))

        assertEquals(SmsThreadsResponse(granted = false), sent[0])
        assertEquals(SmsThreadResponse("5", "", granted = false), sent[1])
        assertEquals(
            SmsSendResult(
                "+15551234",
                sent = false,
                error = "SMS permission missing",
                id = "sms-op-denied",
            ),
            sent[2],
        )
        assertEquals(CallLogResponse(granted = false), sent[3])
    }

    @Test
    fun agentClampsEveryLimitAndIgnoresOtherMessages(): Unit = runTest {
        val source = FakeMessagingSource()
        val agent = MessagingAgent(source, send = {})

        agent.onMessage(SmsThreadsRequest(-3))
        agent.onMessage(SmsThreadRequest("low", 0))
        agent.onMessage(CallLogRequest(500))
        agent.onMessage(SmsThreadsRequest(999))
        agent.onMessage(ThumbnailRequest("ignored"))

        assertEquals(
            listOf(
                SourceCall.Threads(1),
                SourceCall.Thread("low", 1),
                SourceCall.CallLog(200),
                SourceCall.Threads(200),
            ),
            source.calls,
        )
    }

    @Test
    fun duplicateKeysShareOneRequestAndResponsesCompleteOnlyTheirOwner(): Unit = runTest {
        val sent = mutableListOf<Message>()
        val companion = MessagingCompanion(sent::add)

        val first = async { companion.thread("7", 20) }
        val duplicate = async { companion.thread("7", 40) }
        val other = async { companion.thread("8", 20) }
        runCurrent()

        assertEquals(
            listOf<Message>(SmsThreadRequest("7", 20), SmsThreadRequest("8", 20)),
            sent,
        )
        companion.onMessage(SmsThreadResponse("8", "+15550008"))
        assertFalse(first.isCompleted)
        assertFalse(duplicate.isCompleted)
        assertEquals("+15550008", other.await().address)

        val response = SmsThreadResponse("7", "+15550007")
        companion.onMessage(response)
        assertEquals(response, first.await())
        assertEquals(response, duplicate.await())
    }

    @Test
    fun sendResponsesCompleteOnlyMatchingOperationId(): Unit = runTest {
        val sent = mutableListOf<Message>()
        var nextId = 0
        val companion = MessagingCompanion(
            send = sent::add,
            sendIdFactory = { "sms-op-${++nextId}" },
        )

        val first = async { companion.sendSms("+15551234", "First") }
        val second = async { companion.sendSms("+15551234", "Second") }
        runCurrent()

        assertEquals(
            listOf<Message>(
                SmsSendRequest("+15551234", "First", "sms-op-1"),
                SmsSendRequest("+15551234", "Second", "sms-op-2"),
            ),
            sent,
        )
        val secondResult = SmsSendResult("+15551234", sent = true, id = "sms-op-2")
        companion.onMessage(secondResult)
        assertFalse(first.isCompleted)
        assertEquals(secondResult, second.await())

        val firstResult = SmsSendResult("+15551234", sent = false, error = "cancelled", id = "sms-op-1")
        companion.onMessage(firstResult)
        assertEquals(firstResult, first.await())
    }

    @Test
    fun sendTimeoutReturnsUnknownWithoutRetrying(): Unit = runTest {
        val sent = mutableListOf<Message>()
        val companion = MessagingCompanion(
            send = sent::add,
            requestTimeoutMillis = 100,
            sendIdFactory = { "sms-op-timeout" },
        )

        val result = async { companion.sendSms("+15551234", "Maybe sent") }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertEquals(
            SmsSendResult(
                "+15551234",
                sent = false,
                error = MessagingCompanion.UNKNOWN_SEND_OUTCOME,
                id = "sms-op-timeout",
            ),
            result.await(),
        )
        assertEquals(
            listOf<Message>(SmsSendRequest("+15551234", "Maybe sent", "sms-op-timeout")),
            sent,
        )
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun agentDropsPendingDuplicateAndReplaysKnownResult(): Unit = runTest {
        val sent = mutableListOf<Message>()
        val source = FakeMessagingSource(autoCompleteSend = false)
        val agent = MessagingAgent(source, sent::add)
        val request = SmsSendRequest("+15551234", "Only once", "sms-op-dedup")

        agent.onMessage(request)
        agent.onMessage(request)

        assertEquals(
            listOf<SourceCall>(SourceCall.Send("sms-op-dedup", "+15551234", "Only once")),
            source.calls,
        )
        assertEquals(emptyList(), sent)

        source.completeSend("sms-op-dedup", Result.success(Unit))
        val result = SmsSendResult("+15551234", sent = true, id = "sms-op-dedup")
        assertEquals(listOf<Message>(result), sent)

        agent.onMessage(request)
        assertEquals(listOf<Message>(result, result), sent)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun timeoutCleansPendingRequestAndRetrySendsAgain(): Unit = runTest {
        val sent = mutableListOf<Message>()
        val companion = MessagingCompanion(
            send = sent::add,
            requestTimeoutMillis = 100,
        )

        val first = async { runCatching { companion.callLog() } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertIs<TimeoutCancellationException>(first.await().exceptionOrNull())
        val retry = async { companion.callLog() }
        runCurrent()
        assertEquals(
            listOf<Message>(CallLogRequest(50), CallLogRequest(50)),
            sent,
        )
        val response = CallLogResponse(listOf(CallLogEntry("123", "", "outgoing", 1, 2)))
        companion.onMessage(response)
        assertEquals(response, retry.await())
    }
}

private sealed interface SourceCall {
    data class Threads(val limit: Int) : SourceCall
    data class Thread(val threadId: String, val limit: Int) : SourceCall
    data class Send(val id: String, val address: String, val body: String) : SourceCall
    data class CallLog(val limit: Int) : SourceCall
}

private class FakeMessagingSource(
    private val threadsResult: List<SmsThread>? = emptyList(),
    private val threadResult: Pair<String, List<SmsMessage>>? = "" to emptyList(),
    private val sendResult: Result<Unit> = Result.success(Unit),
    private val callLogResult: List<CallLogEntry>? = emptyList(),
    private val autoCompleteSend: Boolean = true,
) : MessagingSource {
    val calls = mutableListOf<SourceCall>()
    private val sendCallbacks = mutableMapOf<String, suspend (Result<Unit>) -> Unit>()

    override suspend fun threads(limit: Int): List<SmsThread>? {
        calls += SourceCall.Threads(limit)
        return threadsResult
    }

    override suspend fun thread(threadId: String, limit: Int): Pair<String, List<SmsMessage>>? {
        calls += SourceCall.Thread(threadId, limit)
        return threadResult
    }

    override suspend fun send(
        id: String,
        address: String,
        body: String,
        onResult: suspend (Result<Unit>) -> Unit,
    ) {
        calls += SourceCall.Send(id, address, body)
        if (autoCompleteSend) {
            onResult(sendResult)
        } else {
            sendCallbacks[id] = onResult
        }
    }

    suspend fun completeSend(id: String, result: Result<Unit>) {
        requireNotNull(sendCallbacks.remove(id))(result)
    }

    override suspend fun callLog(limit: Int): List<CallLogEntry>? {
        calls += SourceCall.CallLog(limit)
        return callLogResult
    }
}
