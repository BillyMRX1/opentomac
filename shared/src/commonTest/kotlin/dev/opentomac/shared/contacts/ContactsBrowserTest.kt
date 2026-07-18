@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.opentomac.shared.contacts

import dev.opentomac.shared.protocol.ContactItem
import dev.opentomac.shared.protocol.ContactsSearchRequest
import dev.opentomac.shared.protocol.ContactsSearchResponse
import dev.opentomac.shared.protocol.Message
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

class ContactsBrowserTest {

    @Test
    fun requestAndResponseRoundTripThroughBothRoles() = runTest {
        val item = ContactItem("Ada Lovelace", listOf("+44 123"), listOf("ada@example.com"))
        val source = FakeContactsSource(listOf(item))
        lateinit var agent: ContactsAgent
        lateinit var companion: ContactsCompanion
        agent = ContactsAgent(source) { companion.onMessage(it) }
        companion = ContactsCompanion(send = { agent.onMessage(it) })

        val response = companion.search("Ada", 12)

        assertEquals(listOf(SearchCall("Ada", 12)), source.calls)
        assertEquals(ContactsSearchResponse("Ada", listOf(item), granted = true), response)
    }

    @Test
    fun permissionDeniedReturnsGrantedFalse() = runTest {
        val sent = mutableListOf<Message>()
        val agent = ContactsAgent(FakeContactsSource(null), sent::add)

        agent.onMessage(ContactsSearchRequest("Ada", 20))

        assertEquals(ContactsSearchResponse("Ada", granted = false), sent.single())
    }

    @Test
    fun blankQueryNeverCallsSourceAndReturnsEmptyGrantedResult() = runTest {
        val source = FakeContactsSource(null)
        val sent = mutableListOf<Message>()
        val agent = ContactsAgent(source, sent::add)

        agent.onMessage(ContactsSearchRequest("  \n", 20))

        assertEquals(emptyList(), source.calls)
        assertEquals(ContactsSearchResponse("  \n", granted = true), sent.single())
    }

    @Test
    fun agentClampsLimitsAndIgnoresOtherMessages() = runTest {
        val source = FakeContactsSource(emptyList())
        val agent = ContactsAgent(source, send = {})

        agent.onMessage(ContactsSearchRequest("low", -4))
        agent.onMessage(ContactsSearchRequest("high", 500))
        agent.onMessage(ThumbnailRequest("ignored"))

        assertEquals(listOf(SearchCall("low", 1), SearchCall("high", 50)), source.calls)
    }

    @Test
    fun duplicateQueriesShareOneRequestAndResponse() = runTest {
        val sent = mutableListOf<Message>()
        val companion = ContactsCompanion(sent::add)

        val first = async { companion.search("Ada", 20) }
        val duplicate = async { companion.search("Ada", 40) }
        runCurrent()

        assertEquals(listOf<Message>(ContactsSearchRequest("Ada", 20)), sent)
        val response = ContactsSearchResponse("Ada", listOf(ContactItem("Ada")))
        companion.onMessage(response)

        assertEquals(response, first.await())
        assertEquals(response, duplicate.await())
    }

    @Test
    fun responsesOnlyCompleteMatchingQuery() = runTest {
        val companion = ContactsCompanion(send = {})
        val request = async { companion.search("Ada") }
        runCurrent()

        companion.onMessage(ContactsSearchResponse("Grace", listOf(ContactItem("Grace Hopper"))))
        assertFalse(request.isCompleted)
        val response = ContactsSearchResponse("Ada", listOf(ContactItem("Ada Lovelace")))
        companion.onMessage(response)

        assertEquals(response, request.await())
    }

    @Test
    fun timeoutCleansPendingRequestAndRetrySendsAgain() = runTest {
        val sent = mutableListOf<Message>()
        val companion = ContactsCompanion(
            send = sent::add,
            requestTimeoutMillis = 100,
        )

        val first = async { runCatching { companion.search("Ada") } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertIs<TimeoutCancellationException>(first.await().exceptionOrNull())
        val retry = async { companion.search("Ada") }
        runCurrent()
        assertEquals(
            listOf<Message>(
                ContactsSearchRequest("Ada", 20),
                ContactsSearchRequest("Ada", 20),
            ),
            sent,
        )
        val response = ContactsSearchResponse("Ada", listOf(ContactItem("Ada Lovelace")))
        companion.onMessage(response)
        assertEquals(response, retry.await())
    }
}

private data class SearchCall(val query: String, val limit: Int)

private class FakeContactsSource(
    private val result: List<ContactItem>?,
) : ContactsSource {
    val calls = mutableListOf<SearchCall>()

    override suspend fun search(query: String, limit: Int): List<ContactItem>? {
        calls += SearchCall(query, limit)
        return result
    }
}
