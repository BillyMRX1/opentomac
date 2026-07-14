package dev.opentomac.android.platform

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import dev.opentomac.shared.media.MediaSource
import dev.opentomac.shared.protocol.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class AndroidMediaSource(context: Context) : MediaSource {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun list(
        bucket: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<MediaItem>, Boolean> = withContext(Dispatchers.IO) {
        require(page >= 0) { "page must not be negative" }
        require(pageSize in 1..200) { "pageSize must be between 1 and 200" }
        val query = queryFor(bucket.lowercase())
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize + 1)
            putInt(ContentResolver.QUERY_ARG_OFFSET, page * pageSize)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            query.selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            query.selectionArgs?.let {
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it)
            }
        }
        val items = mutableListOf<MediaItem>()
        resolver.query(query.uri, projection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && items.size <= pageSize) {
                val uri = ContentUris.withAppendedId(query.uri, cursor.getLong(idColumn))
                items += MediaItem(
                    mediaId = uri.toString(),
                    name = cursor.getString(nameColumn) ?: "Untitled",
                    sizeBytes = cursor.getLong(sizeColumn),
                    mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                    modifiedAt = cursor.getLong(modifiedColumn) * 1_000L,
                )
            }
        }
        val hasMore = items.size > pageSize
        items.take(pageSize) to hasMore
    }

    override suspend fun thumbnail(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(mediaId) }.getOrNull() ?: return@withContext null
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, Size(384, 384), null)
            } else {
                resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    BitmapFactory.decodeFileDescriptor(
                        descriptor.fileDescriptor,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = 4 },
                    )
                }
            }
        }.getOrNull() ?: return@withContext null
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun queryFor(bucket: String): MediaQuery = when (bucket) {
        "images", "photos" -> MediaQuery(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        "videos", "video" -> MediaQuery(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        "downloads", "files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaQuery(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        } else {
            MediaQuery(
                uri = MediaStore.Files.getContentUri("external"),
                selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?",
                selectionArgs = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString()),
            )
        }
        else -> throw IllegalArgumentException("Unsupported media bucket '$bucket'")
    }

    private data class MediaQuery(
        val uri: Uri,
        val selection: String? = null,
        val selectionArgs: Array<String>? = null,
    )
}
