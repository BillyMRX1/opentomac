package dev.opentomac.android.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.Lifecycle
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
import java.nio.charset.StandardCharsets

/**
 * Android 10 and newer restrict background clipboard reads. The listener therefore
 * reads and emits clipboard content only while this app process is foregrounded.
 * Applying content received from a trusted peer remains available in the background.
 */
class AndroidClipboard(context: Context) : LocalClipboard {
    private val clipboard = context.applicationContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val manualChanges = MutableSharedFlow<ClipItem>(extraBufferCapacity = 1)

    override fun changes(): Flow<ClipItem> = merge(platformChanges(), manualChanges)

    override suspend fun apply(item: ClipItem) = withContext(Dispatchers.Main.immediate) {
        val text = item.payload.toString(StandardCharsets.UTF_8)
        val clip = if (item.type == ClipType.URL) {
            ClipData.newRawUri("opentomac URL", Uri.parse(text))
        } else {
            ClipData.newPlainText("opentomac", text)
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
            launch {
                currentItem()?.let { trySend(it) }
            }
        }
        clipboard.addPrimaryClipChangedListener(listener)
        awaitClose { clipboard.removePrimaryClipChangedListener(listener) }
    }

    private suspend fun currentItem(): ClipItem? = withContext(Dispatchers.Main.immediate) {
        val text = runCatching {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(null)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        ClipItem.create(typeFor(text), text.toByteArray(), sensitive = false)
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
