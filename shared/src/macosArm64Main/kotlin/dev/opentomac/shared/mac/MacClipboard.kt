package dev.opentomac.shared.mac

import dev.opentomac.shared.clipboard.ClipItem
import dev.opentomac.shared.clipboard.ClipType
import dev.opentomac.shared.clipboard.LocalClipboard
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.AppKit.NSImage
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypePNG
import platform.AppKit.NSPasteboardTypeString
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * NSPasteboard-backed [LocalClipboard]. macOS exposes no change notification, so
 * [changes] polls `changeCount` about twice a second (the standard approach for Mac
 * clipboard tools). Text, URLs, and images are read and applied; images travel as PNG.
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
                readItem()?.let { trySend(it) }
            }
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        awaitClose { }
    }

    override suspend fun apply(item: ClipItem) {
        pasteboard.clearContents()
        when (item.type) {
            ClipType.IMAGE -> {
                val image = NSImage(data = item.payload.toNSData()) ?: return
                pasteboard.writeObjects(listOf(image))
            }
            else -> pasteboard.setString(item.payload.decodeToString(), forType = NSPasteboardTypeString)
        }
    }

    private suspend fun readItem(): ClipItem? {
        readImagePng()?.let { png ->
            return ClipItem.create(ClipType.IMAGE, png, sensitive = false)
        }
        val text = pasteboard.stringForType(NSPasteboardTypeString)?.takeIf { it.isNotBlank() } ?: return null
        return ClipItem.create(typeFor(text), text.encodeToByteArray(), sensitive = false)
    }

    // Reads a copied image only when the pasteboard already carries PNG (screenshots and
    // browser-copied images do). TIFF-only sources such as Preview are not synced.
    private fun readImagePng(): ByteArray? =
        pasteboard.dataForType(NSPasteboardTypePNG)?.toByteArray()

    private fun typeFor(text: String): ClipType {
        val trimmed = text.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) ClipType.URL else ClipType.TEXT
    }

    private fun NSData.toByteArray(): ByteArray {
        val length = this.length.toInt()
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, this.length) }
        return out
    }

    private fun ByteArray.toNSData(): NSData {
        if (isEmpty()) return NSData()
        return usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
