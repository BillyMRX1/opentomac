package dev.opentomac.shared.mac

import dev.opentomac.shared.clipboard.ClipItem
import dev.opentomac.shared.clipboard.ClipType
import dev.opentomac.shared.clipboard.LocalClipboard
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString

/**
 * NSPasteboard-backed [LocalClipboard]. macOS exposes no change notification, so
 * [changes] polls `changeCount` about twice a second (the standard approach for Mac
 * clipboard tools). Applying a remote item writes it to the general pasteboard.
 */
@OptIn(ExperimentalForeignApi::class)
class MacClipboard : LocalClipboard {
    private val pasteboard = NSPasteboard.generalPasteboard

    override fun changes(): Flow<ClipItem> = callbackFlow {
        var lastChangeCount = pasteboard.changeCount
        while (true) {
            val current = pasteboard.changeCount
            if (current != lastChangeCount) {
                lastChangeCount = current
                readString()?.let { text ->
                    trySend(ClipItem.create(typeFor(text), text.encodeToByteArray(), sensitive = false))
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        awaitClose { }
    }

    override suspend fun apply(item: ClipItem) {
        if (item.type != ClipType.TEXT && item.type != ClipType.URL) return
        val text = item.payload.decodeToString()
        pasteboard.clearContents()
        pasteboard.setString(text, forType = NSPasteboardTypeString)
    }

    private fun readString(): String? =
        pasteboard.stringForType(NSPasteboardTypeString)?.takeIf { it.isNotBlank() }

    private fun typeFor(text: String): ClipType {
        val trimmed = text.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) ClipType.URL else ClipType.TEXT
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
