package dev.opentomac.shared.media

import dev.opentomac.shared.protocol.MediaItem
import dev.opentomac.shared.protocol.MediaListRequest
import dev.opentomac.shared.protocol.MediaListResponse
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.ThumbnailRequest
import dev.opentomac.shared.protocol.ThumbnailResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MediaSource {
    suspend fun list(
        bucket: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<MediaItem>, Boolean>

    suspend fun thumbnail(mediaId: String): ByteArray?
}

/** Phone-side media role backed by the platform's media index and thumbnailer. */
class MediaAgent(
    private val source: MediaSource,
    private val send: suspend (Message) -> Unit,
) {
    suspend fun onMessage(msg: Message) {
        when (msg) {
            is MediaListRequest -> {
                val (items, hasMore) = source.list(msg.bucket, msg.page, msg.pageSize)
                send(
                    MediaListResponse(
                        items = items,
                        hasMore = hasMore,
                        bucket = msg.bucket,
                        page = msg.page,
                    ),
                )
            }

            is ThumbnailRequest -> {
                // An empty payload is the wire representation for an unavailable thumbnail.
                val jpegBytes = source.thumbnail(msg.mediaId) ?: ByteArray(0)
                send(ThumbnailResponse(msg.mediaId, jpegBytes))
            }

            else -> Unit
        }
    }
}

/** Desktop-side media requester with page and thumbnail request single-flight. */
class MediaCompanionBrowser(
    private val send: suspend (Message) -> Unit,
    private val cacheCapacityBytes: Long = DEFAULT_CACHE_CAPACITY_BYTES,
) {
    private data class PageKey(val bucket: String, val page: Int)

    private val stateMutex = Mutex()
    private val pendingPages = mutableMapOf<PageKey, CompletableDeferred<MediaListResponse>>()
    private val pendingThumbnails = mutableMapOf<String, CompletableDeferred<ByteArray>>()
    private val thumbnailCache = mutableMapOf<String, ByteArray>()
    private val thumbnailLru = mutableListOf<String>()
    private var cachedBytes = 0L

    init {
        require(cacheCapacityBytes >= 0) { "cacheCapacityBytes must not be negative" }
    }

    suspend fun requestPage(
        bucket: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): MediaListResponse {
        val key = PageKey(bucket, page)
        var shouldSend = false
        val deferred = stateMutex.withLock {
            pendingPages[key] ?: CompletableDeferred<MediaListResponse>().also {
                pendingPages[key] = it
                shouldSend = true
            }
        }

        if (shouldSend) {
            try {
                send(MediaListRequest(bucket, page, pageSize))
            } catch (cause: Throwable) {
                stateMutex.withLock {
                    if (pendingPages[key] === deferred) {
                        pendingPages.remove(key)
                    }
                }
                deferred.completeExceptionally(cause)
            }
        }

        return deferred.await()
    }

    suspend fun thumbnail(mediaId: String): ByteArray {
        var cached: ByteArray? = null
        var shouldSend = false
        val deferred = stateMutex.withLock {
            val cachedEntry = thumbnailCache[mediaId]
            if (cachedEntry != null) {
                thumbnailLru.remove(mediaId)
                thumbnailLru += mediaId
                cached = cachedEntry.copyOf()
                null
            } else {
                pendingThumbnails[mediaId] ?: CompletableDeferred<ByteArray>().also {
                    pendingThumbnails[mediaId] = it
                    shouldSend = true
                }
            }
        }

        cached?.let { return it }
        checkNotNull(deferred)

        if (shouldSend) {
            try {
                send(ThumbnailRequest(mediaId))
            } catch (cause: Throwable) {
                stateMutex.withLock {
                    if (pendingThumbnails[mediaId] === deferred) {
                        pendingThumbnails.remove(mediaId)
                    }
                }
                deferred.completeExceptionally(cause)
            }
        }

        return deferred.await().copyOf()
    }

    /** Completes requests from inbound CONTROL messages. */
    suspend fun onMessage(msg: Message) {
        when (msg) {
            is MediaListResponse -> completePage(msg)
            is ThumbnailResponse -> completeThumbnail(msg)
            else -> Unit
        }
    }

    private suspend fun completePage(response: MediaListResponse) {
        val deferred = stateMutex.withLock {
            pendingPages.remove(PageKey(response.bucket, response.page))
        }
        deferred?.complete(response)
    }

    private suspend fun completeThumbnail(response: ThumbnailResponse) {
        val bytes = response.jpegBytes.copyOf()
        val deferred = stateMutex.withLock {
            val pending = pendingThumbnails.remove(response.mediaId)
            if (pending != null) cache(response.mediaId, bytes)
            pending
        }
        deferred?.complete(bytes)
    }

    private fun cache(mediaId: String, bytes: ByteArray) {
        val byteCount = bytes.size.toLong()
        if (cacheCapacityBytes == 0L || byteCount > cacheCapacityBytes) return

        thumbnailCache.remove(mediaId)?.let { previous ->
            cachedBytes -= previous.size
            thumbnailLru.remove(mediaId)
        }
        while (cachedBytes + byteCount > cacheCapacityBytes && thumbnailLru.isNotEmpty()) {
            val evictedId = thumbnailLru.removeAt(0)
            cachedBytes -= thumbnailCache.remove(evictedId)?.size ?: 0
        }
        thumbnailCache[mediaId] = bytes.copyOf()
        thumbnailLru += mediaId
        cachedBytes += byteCount
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val DEFAULT_CACHE_CAPACITY_BYTES = 32L * 1024 * 1024
    }
}
