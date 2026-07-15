package dev.opentomac.android.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.opentomac.shared.clipboard.ClipItem
import dev.opentomac.shared.clipboard.ClipType
import dev.opentomac.shared.clipboard.LocalClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Android 10 and newer forbid reading the clipboard while the app is backgrounded.
 * So automatic phone-to-Mac sync happens two ways: whenever the clipboard changes while
 * the app is foregrounded, and once each time the app returns to the foreground (so a
 * copy made in another app syncs the moment you reopen opentomac). The Quick Settings
 * tile, share sheet, and text-selection action remain the background escape hatches.
 * Applying content received from the Mac works regardless of foreground state.
 */
class AndroidClipboard(private val context: Context) : LocalClipboard {
    private val appContext = context.applicationContext
    private val clipboard = appContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val manualChanges = MutableSharedFlow<ClipItem>(extraBufferCapacity = 1)

    override fun changes(): Flow<ClipItem> =
        merge(platformChanges(), foregroundResumes(), manualChanges)

    override suspend fun apply(item: ClipItem) = withContext(Dispatchers.Main.immediate) {
        val clip = when (item.type) {
            ClipType.IMAGE -> imageClip(item.payload)
            ClipType.URL -> ClipData.newRawUri("opentomac URL", Uri.parse(item.payload.toString(StandardCharsets.UTF_8)))
            else -> ClipData.newPlainText("opentomac", item.payload.toString(StandardCharsets.UTF_8))
        }
        clipboard.setPrimaryClip(clip)
    }

    suspend fun sendCurrent(): Boolean {
        val item = currentItem() ?: return false
        manualChanges.emit(item)
        return true
    }

    suspend fun sendText(text: String): Boolean {
        if (text.isBlank()) return false
        val item = ClipItem.create(typeFor(text), text.toByteArray(), sensitive = false)
        apply(item)
        manualChanges.emit(item)
        return true
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
                    kotlinx.coroutines.delay(250)
                    currentItem()?.let { trySend(it) }
                }
            }
        }
        withContext(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        awaitClose {
            launch(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
        }
    }

    private suspend fun currentItem(): ClipItem? = withContext(Dispatchers.Main.immediate) {
        val clip = clipboard.primaryClip ?: return@withContext null
        val item = clip.getItemAt(0) ?: return@withContext null
        val imageBytes = readImage(clip, item)
        if (imageBytes != null) {
            return@withContext ClipItem.create(ClipType.IMAGE, imageBytes, sensitive = false)
        }
        val text = runCatching { item.coerceToText(appContext)?.toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        ClipItem.create(typeFor(text), text.toByteArray(), sensitive = false)
    }

    private fun readImage(clip: ClipData, item: ClipData.Item): ByteArray? {
        val uri = item.uri ?: return null
        val mime = clip.description.getMimeType(0) ?: appContext.contentResolver.getType(uri)
        if (mime?.startsWith("image/") != true) return null
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
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
}
