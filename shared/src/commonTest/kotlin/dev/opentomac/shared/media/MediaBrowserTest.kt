@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.opentomac.shared.media

import dev.opentomac.shared.protocol.MediaFetchRequest
import dev.opentomac.shared.protocol.MediaItem
import dev.opentomac.shared.protocol.MediaListRequest
import dev.opentomac.shared.protocol.MediaListResponse
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.ThumbnailRequest
import dev.opentomac.shared.protocol.ThumbnailResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MediaBrowserTest {

    @Test
    fun agentReturnsPagedItemsAndHasMore() = runTest {
        val items = listOf(media("one"), media("two"))
        val source = FakeMediaSource(items = items, hasMore = true)
        val sent = mutableListOf<Message>()
        val agent = MediaAgent(source, sent::add)

        agent.onMessage(MediaListRequest(bucket = "camera", page = 3, pageSize = 2))

        assertEquals(listOf(ListCall("camera", 3, 2)), source.listCalls)
        assertEquals(
            MediaListResponse(items, hasMore = true, bucket = "camera", page = 3),
            sent.single(),
        )
    }

    @Test
    fun agentMapsMissingThumbnailToEmptyBytes() = runTest {
        val source = FakeMediaSource(thumbnails = emptyMap())
        val sent = mutableListOf<Message>()
        val agent = MediaAgent(source, sent::add)

        agent.onMessage(ThumbnailRequest("missing"))

        val response = assertIs<ThumbnailResponse>(sent.single())
        assertEquals("missing", response.mediaId)
        assertTrue(response.jpegBytes.isEmpty())
    }

    @Test
    fun agentDispatchesMediaFetchRequestsAndIgnoresOtherMessages() = runTest {
        val fetched = mutableListOf<String>()
        val agent = MediaAgent(
            source = FakeMediaSource(),
            send = {},
            fetch = fetched::add,
        )

        agent.onMessage(MediaFetchRequest("content://media/external/images/media/42"))
        agent.onMessage(ThumbnailResponse("ignored", byteArrayOf(1)))

        assertEquals(listOf("content://media/external/images/media/42"), fetched)
    }

    @Test
    fun duplicateConcurrentPageRequestsShareOneWireRequest() = runTest {
        val sent = mutableListOf<Message>()
        val browser = MediaCompanionBrowser(sent::add)

        val first = async { browser.requestPage("camera", page = 0, pageSize = 25) }
        val duplicate = async { browser.requestPage("camera", page = 0, pageSize = 25) }
        runCurrent()

        assertEquals(listOf<Message>(MediaListRequest("camera", 0, 25)), sent)
        val response = MediaListResponse(
            items = listOf(media("photo")),
            hasMore = false,
            bucket = "camera",
            page = 0,
        )
        browser.onMessage(response)

        assertEquals(response, first.await())
        assertEquals(response, duplicate.await())
    }

    @Test
    fun differentPagesCompleteCorrectlyWhenResponsesArriveOutOfOrder() = runTest {
        val sent = mutableListOf<Message>()
        val browser = MediaCompanionBrowser(sent::add)

        val pageOne = async { browser.requestPage("camera", page = 1) }
        val pageTwo = async { browser.requestPage("camera", page = 2) }
        runCurrent()

        assertEquals(
            listOf<Message>(
                MediaListRequest("camera", 1, 50),
                MediaListRequest("camera", 2, 50),
            ),
            sent,
        )
        val responseTwo = MediaListResponse(
            items = listOf(media("page-two")),
            hasMore = false,
            bucket = "camera",
            page = 2,
        )
        val responseOne = MediaListResponse(
            items = listOf(media("page-one")),
            hasMore = true,
            bucket = "camera",
            page = 1,
        )
        browser.onMessage(
            MediaListResponse(
                items = listOf(media("unmatched")),
                hasMore = false,
                bucket = "downloads",
                page = 1,
            ),
        )
        assertFalse(pageOne.isCompleted)
        assertFalse(pageTwo.isCompleted)
        browser.onMessage(responseTwo)
        browser.onMessage(responseOne)

        assertEquals(responseOne, pageOne.await())
        assertEquals(responseTwo, pageTwo.await())
    }

    @Test
    fun pageTimeoutFailsPendingRequestAndRetrySendsAgain() = runTest {
        val sent = mutableListOf<Message>()
        val browser = MediaCompanionBrowser(
            send = sent::add,
            requestTimeoutMillis = 100,
        )

        val first = async { runCatching { browser.requestPage("camera", page = 0) } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertIs<TimeoutCancellationException>(first.await().exceptionOrNull())
        val retry = async { browser.requestPage("camera", page = 0) }
        runCurrent()
        assertEquals(
            listOf<Message>(
                MediaListRequest("camera", 0, 50),
                MediaListRequest("camera", 0, 50),
            ),
            sent,
        )

        val response = MediaListResponse(emptyList(), false, "camera", 0)
        browser.onMessage(response)
        assertEquals(response, retry.await())
    }

    @Test
    fun cancelledPageSenderFailsPiggybackerWithoutCancellingItAndAllowsRetry() = runTest {
        val sent = mutableListOf<Message>()
        val browser = MediaCompanionBrowser(sent::add)

        val sender = async { browser.requestPage("camera", page = 0) }
        runCurrent()
        val piggybacker = async { runCatching { browser.requestPage("camera", page = 0) } }
        runCurrent()

        sender.cancelAndJoin()
        val failure = assertIs<IllegalStateException>(piggybacker.await().exceptionOrNull())
        assertEquals("page request abandoned", failure.message)
        assertFalse(failure is CancellationException)

        val retry = async { browser.requestPage("camera", page = 0) }
        runCurrent()
        assertEquals(2, sent.size)
        val response = MediaListResponse(emptyList(), false, "camera", 0)
        browser.onMessage(response)
        assertEquals(response, retry.await())
    }

    @Test
    fun pageTimeoutBoundsSuspendedSendAndAllowsRetry() = runTest {
        val sent = mutableListOf<Message>()
        var blockSend = true
        val browser = MediaCompanionBrowser(
            send = {
                sent += it
                if (blockSend) awaitCancellation()
            },
            requestTimeoutMillis = 100,
        )

        val first = async { runCatching { browser.requestPage("camera", page = 0) } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertIs<TimeoutCancellationException>(first.await().exceptionOrNull())

        blockSend = false
        val retry = async { browser.requestPage("camera", page = 0) }
        runCurrent()
        assertEquals(2, sent.size)
        val response = MediaListResponse(emptyList(), false, "camera", 0)
        browser.onMessage(response)
        assertEquals(response, retry.await())
    }

    @Test
    fun thumbnailCacheHitDoesNotSendAnotherRequest() = runTest {
        val sent = mutableListOf<Message>()
        lateinit var browser: MediaCompanionBrowser
        browser = MediaCompanionBrowser(send = { message ->
            sent += message
            val request = assertIs<ThumbnailRequest>(message)
            browser.onMessage(ThumbnailResponse(request.mediaId, byteArrayOf(1, 2, 3)))
        })

        val first = browser.thumbnail("photo")
        val cached = browser.thumbnail("photo")

        assertContentEquals(byteArrayOf(1, 2, 3), first)
        assertContentEquals(first, cached)
        assertEquals(listOf<Message>(ThumbnailRequest("photo")), sent)
    }

    @Test
    fun thumbnailCacheUsesLeastRecentlyUsedByteCapacity() = runTest {
        val payloads = mapOf(
            "a" to byteArrayOf(1, 1),
            "b" to byteArrayOf(2, 2),
            "c" to byteArrayOf(3, 3),
        )
        val requested = mutableListOf<String>()
        lateinit var browser: MediaCompanionBrowser
        browser = MediaCompanionBrowser(
            send = { message ->
                val request = assertIs<ThumbnailRequest>(message)
                requested += request.mediaId
                browser.onMessage(ThumbnailResponse(request.mediaId, payloads.getValue(request.mediaId)))
            },
            cacheCapacityBytes = 4,
        )

        browser.thumbnail("a")
        browser.thumbnail("b")
        browser.thumbnail("a") // Promote a, making b the least recently used entry.
        browser.thumbnail("c") // Evicts b.
        browser.thumbnail("a")
        browser.thumbnail("b")
        browser.thumbnail("c")
        browser.thumbnail("a") // a was eventually evicted and must remain requestable.

        assertEquals(listOf("a", "b", "c", "b", "c", "a"), requested)
    }

    @Test
    fun concurrentThumbnailRequestsForSameIdShareOneWireRequest() = runTest {
        val sent = mutableListOf<Message>()
        val browser = MediaCompanionBrowser(sent::add)

        val first = async { browser.thumbnail("shared") }
        val duplicate = async { browser.thumbnail("shared") }
        runCurrent()

        assertEquals(listOf<Message>(ThumbnailRequest("shared")), sent)
        browser.onMessage(ThumbnailResponse("shared", byteArrayOf(9, 8, 7)))

        assertContentEquals(byteArrayOf(9, 8, 7), first.await())
        assertContentEquals(first.await(), duplicate.await())
        assertFalse(first.isCancelled)
    }

    @Test
    fun cancelledThumbnailSenderFailsPiggybackerWithoutCancellingItAndAllowsRetry() = runTest {
        val sent = mutableListOf<Message>()
        val browser = MediaCompanionBrowser(sent::add)

        val sender = async { browser.thumbnail("shared") }
        runCurrent()
        val piggybacker = async { runCatching { browser.thumbnail("shared") } }
        runCurrent()

        sender.cancelAndJoin()
        val failure = assertIs<IllegalStateException>(piggybacker.await().exceptionOrNull())
        assertEquals("thumbnail request abandoned", failure.message)
        assertFalse(failure is CancellationException)

        val retry = async { browser.thumbnail("shared") }
        runCurrent()
        assertEquals(2, sent.size)
        browser.onMessage(ThumbnailResponse("shared", byteArrayOf(1)))
        assertContentEquals(byteArrayOf(1), retry.await())
    }

    @Test
    fun thumbnailTimeoutCleansPendingRequestAndIgnoresLateResponse() = runTest {
        val sent = mutableListOf<Message>()
        val browser = MediaCompanionBrowser(
            send = sent::add,
            requestTimeoutMillis = 100,
        )

        val first = async { runCatching { browser.thumbnail("slow") } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertIs<TimeoutCancellationException>(first.await().exceptionOrNull())
        browser.onMessage(ThumbnailResponse("slow", byteArrayOf(1)))

        val retry = async { browser.thumbnail("slow") }
        runCurrent()
        assertEquals(
            listOf<Message>(ThumbnailRequest("slow"), ThumbnailRequest("slow")),
            sent,
        )
        browser.onMessage(ThumbnailResponse("slow", byteArrayOf(2)))
        assertContentEquals(byteArrayOf(2), retry.await())
    }

    @Test
    fun thumbnailRequestsAreCappedWhileResponsesAreOutstanding() = runTest {
        val sent = mutableListOf<String>()
        val browser = MediaCompanionBrowser(
            send = { sent += assertIs<ThumbnailRequest>(it).mediaId },
            maxConcurrentThumbnailRequests = 2,
        )
        val requests = (0 until 5).map { id ->
            async { browser.thumbnail(id.toString()) }
        }

        runCurrent()
        assertEquals(listOf("0", "1"), sent)

        browser.onMessage(ThumbnailResponse("0", byteArrayOf(0)))
        runCurrent()
        assertEquals(listOf("0", "1", "2"), sent)

        browser.onMessage(ThumbnailResponse("1", byteArrayOf(1)))
        runCurrent()
        assertEquals(listOf("0", "1", "2", "3"), sent)

        browser.onMessage(ThumbnailResponse("2", byteArrayOf(2)))
        runCurrent()
        assertEquals(listOf("0", "1", "2", "3", "4"), sent)

        browser.onMessage(ThumbnailResponse("3", byteArrayOf(3)))
        browser.onMessage(ThumbnailResponse("4", byteArrayOf(4)))
        requests.forEachIndexed { id, request ->
            assertContentEquals(byteArrayOf(id.toByte()), request.await())
        }
    }

    @Test
    fun suspendedThumbnailSendsTimeoutAndReleaseAllPermits() = runTest {
        val sent = mutableListOf<String>()
        var blockSend = true
        val browser = MediaCompanionBrowser(
            send = {
                sent += assertIs<ThumbnailRequest>(it).mediaId
                if (blockSend) awaitCancellation()
            },
            requestTimeoutMillis = 100,
            maxConcurrentThumbnailRequests = 2,
        )
        val requests = (0 until 5).map { id ->
            async { runCatching { browser.thumbnail(id.toString()) } }
        }

        runCurrent()
        assertEquals(listOf("0", "1"), sent)
        advanceTimeBy(101)
        runCurrent()
        requests.forEach { request ->
            assertIs<TimeoutCancellationException>(request.await().exceptionOrNull())
        }

        blockSend = false
        val sentBeforeRetry = sent.toList()
        val retry = async { browser.thumbnail("after-timeout") }
        runCurrent()
        assertEquals(sentBeforeRetry + "after-timeout", sent)
        browser.onMessage(ThumbnailResponse("after-timeout", byteArrayOf(9)))
        assertContentEquals(byteArrayOf(9), retry.await())
    }

    @Test
    fun responseRemovalAtTimeoutCannotBeOverwrittenByFailureCleanup() = runTest {
        val browser = MediaCompanionBrowser(
            send = {},
            requestTimeoutMillis = 100,
        )
        val response = launch {
            delay(100)
            browser.onMessage(ThumbnailResponse("race", byteArrayOf(4, 2)))
        }
        val deadlineCaller = async { runCatching { browser.thumbnail("race") } }
        runCurrent()

        advanceTimeBy(50)
        val piggybacker = async { browser.thumbnail("race") }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()

        response.join()
        assertContentEquals(byteArrayOf(4, 2), piggybacker.await())
        deadlineCaller.await().fold(
            onSuccess = { assertContentEquals(byteArrayOf(4, 2), it) },
            onFailure = { assertIs<TimeoutCancellationException>(it) },
        )
    }
}

private data class ListCall(val bucket: String, val page: Int, val pageSize: Int)

private class FakeMediaSource(
    private val items: List<MediaItem> = emptyList(),
    private val hasMore: Boolean = false,
    private val thumbnails: Map<String, ByteArray> = emptyMap(),
) : MediaSource {
    val listCalls = mutableListOf<ListCall>()

    override suspend fun list(bucket: String, page: Int, pageSize: Int): Pair<List<MediaItem>, Boolean> {
        listCalls += ListCall(bucket, page, pageSize)
        return items to hasMore
    }

    override suspend fun thumbnail(mediaId: String): ByteArray? = thumbnails[mediaId]
}

private fun media(id: String): MediaItem = MediaItem(
    mediaId = id,
    name = "$id.jpg",
    sizeBytes = 100,
    mimeType = "image/jpeg",
    modifiedAt = 1_000,
)
