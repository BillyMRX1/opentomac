@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.opentomac.shared.media

import dev.opentomac.shared.protocol.MediaItem
import dev.opentomac.shared.protocol.MediaListRequest
import dev.opentomac.shared.protocol.MediaListResponse
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.ThumbnailRequest
import dev.opentomac.shared.protocol.ThumbnailResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
