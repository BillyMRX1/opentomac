package dev.opentomac.shared.media

import dev.opentomac.shared.protocol.MediaItem
import dev.opentomac.shared.protocol.MediaListRequest
import dev.opentomac.shared.protocol.MediaListResponse
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.ThumbnailRequest
import dev.opentomac.shared.protocol.ThumbnailResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    maxConcurrentThumbnailRequests: Int = DEFAULT_MAX_CONCURRENT_THUMBNAIL_REQUESTS,
) {
    private data class PageKey(val bucket: String, val page: Int)

    private val stateMutex = Mutex()
    private val pendingPages = mutableMapOf<PageKey, CompletableDeferred<MediaListResponse>>()
    private val pendingThumbnails = mutableMapOf<String, CompletableDeferred<ByteArray>>()
    private val thumbnailCache = mutableMapOf<String, ByteArray>()
    private val thumbnailLru = mutableListOf<String>()
    private val thumbnailPermits = Semaphore(maxConcurrentThumbnailRequests)
    private var cachedBytes = 0L

    init {
        require(cacheCapacityBytes >= 0) { "cacheCapacityBytes must not be negative" }
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(maxConcurrentThumbnailRequests > 0) {
            "maxConcurrentThumbnailRequests must be positive"
        }
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

        return try {
            withTimeout(requestTimeoutMillis) {
                if (shouldSend) {
                    send(MediaListRequest(bucket, page, pageSize))
                }
                deferred.await()
            }
        } catch (cause: Throwable) {
            failPending(
                pending = pendingPages,
                key = key,
                deferred = deferred,
                cause = cause,
                abandonedMessage = "page request abandoned",
            )
            throw cause
        }
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

        var permitAcquired = false
        try {
            try {
                return withTimeout(requestTimeoutMillis) {
                    if (shouldSend) {
                        thumbnailPermits.acquire()
                        permitAcquired = true
                        val stillPending = stateMutex.withLock {
                            pendingThumbnails[mediaId] === deferred
                        }
                        if (stillPending) {
                            send(ThumbnailRequest(mediaId))
                        }
                    }
                    deferred.await().copyOf()
                }
            } finally {
                if (permitAcquired) thumbnailPermits.release()
            }
        } catch (cause: Throwable) {
            failPending(
                pending = pendingThumbnails,
                key = mediaId,
                deferred = deferred,
                cause = cause,
                abandonedMessage = "thumbnail request abandoned",
            )
            throw cause
        }
    }

    /** Completes requests from inbound BULK messages. */
    suspend fun onMessage(msg: Message) {
        when (msg) {
            is MediaListResponse -> completePage(msg)
            is ThumbnailResponse -> completeThumbnail(msg)
            else -> Unit
        }
    }

    private suspend fun completePage(response: MediaListResponse) {
        stateMutex.withLock {
            pendingPages.remove(PageKey(response.bucket, response.page))?.complete(response)
        }
    }

    private suspend fun completeThumbnail(response: ThumbnailResponse) {
        val bytes = response.jpegBytes.copyOf()
        stateMutex.withLock {
            val pending = pendingThumbnails.remove(response.mediaId)
            if (pending != null) cache(response.mediaId, bytes)
            pending?.complete(bytes)
        }
    }

    private suspend fun <K, V> failPending(
        pending: MutableMap<K, CompletableDeferred<V>>,
        key: K,
        deferred: CompletableDeferred<V>,
        cause: Throwable,
        abandonedMessage: String,
    ) = withContext(NonCancellable) {
        stateMutex.withLock {
            if (pending[key] === deferred) {
                pending.remove(key)
                val sharedCause = if (cause is CancellationException) {
                    IllegalStateException(abandonedMessage, cause)
                } else {
                    cause
                }
                deferred.completeExceptionally(sharedCause)
            }
        }
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
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_MAX_CONCURRENT_THUMBNAIL_REQUESTS = 8
    }
}
