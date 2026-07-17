package dev.opentomac.android.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.opentomac.shared.clipboard.ClipItem
import dev.opentomac.shared.clipboard.ClipType
import dev.opentomac.shared.clipboard.LocalClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Android 10 and newer forbid reading the clipboard while the app is backgrounded.
 * So automatic phone-to-Mac sync happens two ways: whenever the clipboard changes while
 * the app is foregrounded, and once each time the app returns to the foreground (so a
 * copy made in another app syncs the moment you reopen opentomac). The Quick Settings
 * tile, share sheet, and text-selection action remain the background escape hatches.
 * Applying content received from the Mac works regardless of foreground state.
 *
 * Images larger than [MAX_IMAGE_SEND_BYTES] are downscaled to JPEG before sending so
 * they always fit the wire frame limit; originals travel via file transfer instead.
 */
class AndroidClipboard(context: Context) : LocalClipboard {
    private val appContext = context.applicationContext
    private val clipboard = appContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun changes(): Flow<ClipItem> = kotlinx.coroutines.flow.merge(
        platformChanges(),
        foregroundResumes(),
    )

    override suspend fun apply(item: ClipItem) = withContext(Dispatchers.Main.immediate) {
        val clip = when (item.type) {
            ClipType.IMAGE -> imageClip(item.payload)
            ClipType.URL -> ClipData.newRawUri(
                "opentomac URL",
                Uri.parse(item.payload.toString(StandardCharsets.UTF_8)),
            )
            else -> ClipData.newPlainText("opentomac", item.payload.toString(StandardCharsets.UTF_8))
        }
        clipboard.setPrimaryClip(clip)
    }

    /** Reads the current clipboard as a sendable item, or null when empty/unreadable. */
    suspend fun readCurrent(): ClipItem? = currentItem()

    /** Builds a text/URL item from explicit text (share sheet, text-selection action). */
    suspend fun textItem(text: String): ClipItem? {
        if (text.isBlank()) return null
        return ClipItem.create(typeFor(text), text.toByteArray(), sensitive = false)
    }

    private fun platformChanges(): Flow<ClipItem> = callbackFlow {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@OnPrimaryClipChangedListener
            }
            launch { currentItem()?.let { trySend(it) } }
        }
        clipboard.addPrimaryClipChangedListener(listener)
        awaitClose { clipboard.removePrimaryClipChangedListener(listener) }
    }

    private fun foregroundResumes(): Flow<ClipItem> = callbackFlow {
        // ON_RESUME (not ON_START) so the app already holds window focus; Android 10+
        // denies clipboard reads until then. A short settle delay covers focus races.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                launch {
                    delay(250)
                    currentItem()?.let { trySend(it) }
                }
            }
        }
        withContext(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        awaitClose {
            launch(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
        }
    }

    // Never throws: one malformed clip or misbehaving content provider must not be able
    // to cancel the changes() collection and silently kill clipboard sync.
    private suspend fun currentItem(): ClipItem? = runCatching { readClipItem() }
        .onFailure { logReadFailure("read current clipboard item", null, it) }
        .getOrNull()

    private suspend fun readClipItem(): ClipItem? {
        val clip = withContext(Dispatchers.Main.immediate) { clipboard.primaryClip }
        if (clip == null) {
            Log.w(LOG_TAG, "primaryClip is null (empty, or the OS silently denied the read)")
            return null
        }
        Log.w(LOG_TAG, describeClip(clip))
        val item = clip.getItemAt(0) ?: return null
        val uri = item.uri
        if (uri != null && isImage(clip, uri)) {
            // Image clip: read + fit under the frame limit off the main thread. An
            // unreadable or oversized image yields null; never fall back to the
            // meaningless "content://..." URI text.
            val bytes = withContext(Dispatchers.IO) {
                runCatching { readImageForSend(uri) }
                    .onFailure { logReadFailure("read image for send", uri, it) }
                    .getOrNull()
            }
            if (bytes == null) {
                Log.w(LOG_TAG, "image read yielded no sendable bytes (uri=$uri)")
                return null
            }
            Log.w(LOG_TAG, "image read produced ${bytes.size} sendable bytes")
            return ClipItem.create(ClipType.IMAGE, bytes, sensitive = false)
        }
        val text = withContext(Dispatchers.Main.immediate) {
            runCatching { item.coerceToText(appContext)?.toString() }
                .onFailure { logReadFailure("coerce clipboard item to text", uri, it) }
                .getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (text == null) {
            Log.w(LOG_TAG, "clip item coerced to no usable text (uri=$uri)")
            return null
        }
        // coerceToText falls back to the URI string for non-text URIs; that is noise,
        // not user content (e.g. an image whose provider hid its MIME type).
        if (uri != null && text == uri.toString()) {
            Log.w(LOG_TAG, "dropping clip whose text is just its own URI (uri=$uri)")
            return null
        }
        Log.w(LOG_TAG, "text read produced ${text.length} chars")
        return ClipItem.create(typeFor(text), text.toByteArray(), sensitive = false)
    }

    /** Metadata-only summary of a clip for logcat diagnosis; never logs clip content. */
    private fun describeClip(clip: ClipData): String {
        val description = clip.description
        val mimeTypes = (0 until description.mimeTypeCount).joinToString(",") {
            description.getMimeType(it)
        }
        val item = if (clip.itemCount > 0) clip.getItemAt(0) else null
        return "primaryClip: items=${clip.itemCount} mimeTypes=[$mimeTypes] " +
            "label=${description.label} uri=${item?.uri} " +
            "hasText=${item?.text != null} hasHtml=${item?.htmlText != null}"
    }

    private fun isImage(clip: ClipData, uri: Uri): Boolean {
        val description = clip.description
        for (i in 0 until description.mimeTypeCount) {
            if (description.getMimeType(i).startsWith("image/")) return true
        }
        return runCatching { appContext.contentResolver.getType(uri) }
            .onFailure { logReadFailure("resolve clipboard URI MIME type", uri, it) }
            .getOrNull()?.startsWith("image/") == true
    }

    /**
     * Produces sendable image bytes without ever buffering an unbounded original in
     * heap: originals up to the wire limit are streamed through a bounded read and sent
     * as-is; larger ones are re-decoded from the stream with subsampling to at most
     * [MAX_IMAGE_DIMENSION] pixels and JPEG-compressed under the limit.
     */
    private fun readImageForSend(uri: Uri): ByteArray? {
        readBounded(uri)?.let {
            Log.w(LOG_TAG, "image fits the frame as-is (${it.size} bytes)")
            return it
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        Log.w(LOG_TAG, "image bounds decode: ${bounds.outWidth}x${bounds.outHeight}")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIMENSION) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (bitmap == null) {
            Log.w(LOG_TAG, "sampled decode produced no bitmap (sample=$sample)")
            return null
        }
        try {
            var quality = 85
            while (quality >= 40) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (bytes.size <= MAX_IMAGE_SEND_BYTES) return bytes
                quality -= 15
            }
            Log.w(LOG_TAG, "image would not compress under the frame limit")
            return null
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads the stream only while it stays within the wire limit; null when larger. */
    private fun readBounded(uri: Uri): ByteArray? {
        val input = openStream(uri) ?: return null
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) return out.toByteArray()
                out.write(buffer, 0, read)
                if (out.size() > MAX_IMAGE_SEND_BYTES) {
                    Log.w(LOG_TAG, "image exceeds frame limit; falling back to downscale")
                    return null
                }
            }
        }
    }

    private fun openStream(uri: Uri) =
        runCatching { appContext.contentResolver.openInputStream(uri) }
            .onFailure { logReadFailure("open clipboard URI stream", uri, it) }
            .getOrNull()
            .also { if (it == null) Log.w(LOG_TAG, "openInputStream gave no stream (uri=$uri)") }

    private fun logReadFailure(operation: String, uri: Uri?, cause: Throwable) {
        Log.w(LOG_TAG, "$operation failed (uri=${uri?.toString() ?: "unknown"})", cause)
    }

    private fun imageClip(bytes: ByteArray): ClipData {
        val dir = File(appContext.cacheDir, "clipimg").apply { mkdirs() }
        val file = File(dir, "clipboard.png")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        return ClipData.newUri(appContext.contentResolver, "opentomac image", uri)
    }

    private fun typeFor(value: String): ClipType {
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull()
        return if (uri?.scheme.equals("http", true) || uri?.scheme.equals("https", true)) {
            ClipType.URL
        } else {
            ClipType.TEXT
        }
    }

    private companion object {
        // Headroom under the 4 MiB wire frame limit (envelope + AEAD overhead).
        const val MAX_IMAGE_SEND_BYTES = 3 * 1024 * 1024
        const val MAX_IMAGE_DIMENSION = 2048
        const val LOG_TAG = "opentomac"
    }
}
