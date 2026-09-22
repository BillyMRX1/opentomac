package dev.opentomac.shared.mac

import dev.opentomac.shared.clipboard.ClipItem
import dev.opentomac.shared.clipboard.ClipType
import dev.opentomac.shared.clipboard.LocalClipboard
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.AppKit.NSBitmapImageRep
import platform.AppKit.NSDeviceRGBColorSpace
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSImage
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeFileURL
import platform.AppKit.NSPasteboardTypePNG
import platform.AppKit.NSPasteboardTypeString
import platform.AppKit.NSPasteboardTypeTIFF
import platform.Foundation.NSData
import platform.Foundation.NSMakeRect
import platform.Foundation.create
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreServices.kUTTypePNG
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.posix.memcpy

/**
 * NSPasteboard-backed [LocalClipboard]. macOS exposes no change notification, so
 * [changes] polls `changeCount` about twice a second (the standard approach for Mac
 * clipboard tools). Text, URLs, and images are read and applied; images travel as PNG.
 */
@OptIn(ExperimentalForeignApi::class)
class MacClipboard(private val onNotice: (String) -> Unit = {}) : LocalClipboard {
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
        if (pasteboard.dataForType(NSPasteboardTypeFileURL) != null) {
            onNotice("Finder items are files. Use Send file or drag them into opentomac.")
            return null
        }
        val hasImage = pasteboard.dataForType(NSPasteboardTypePNG) != null ||
            pasteboard.dataForType(NSPasteboardTypeTIFF) != null
        readImagePng()?.let { png ->
            return ClipItem.create(ClipType.IMAGE, png, sensitive = false)
        }
        if (hasImage) {
            onNotice("This image could not fit the clipboard transfer limit. Send it as a file instead.")
            return null
        }
        val text = pasteboard.stringForType(NSPasteboardTypeString)?.takeIf { it.isNotBlank() } ?: return null
        return ClipItem.create(typeFor(text), text.encodeToByteArray(), sensitive = false)
    }

    // Prefers native PNG bytes (screenshots, browser copies) when present and within the
    // safe payload budget. AppKit-backed copies (Preview, Pages, plain NSImage writes)
    // usually offer only TIFF, so those are decoded and re-encoded to PNG, downscaling
    // until the result fits the budget.
    private fun readImagePng(): ByteArray? {
        pasteboard.dataForType(NSPasteboardTypePNG)?.toByteArray()?.let { png ->
            if (png.size <= MAX_IMAGE_BYTES) return png
            return pngFromImageData(png)
        }
        val tiff = pasteboard.dataForType(NSPasteboardTypeTIFF)?.toByteArray() ?: return null
        return pngFromImageData(tiff)
    }

    private fun pngFromImageData(data: ByteArray): ByteArray? {
        val source = NSBitmapImageRep.imageRepWithData(data.toNSData()) as? NSBitmapImageRep ?: return null
        val width = source.pixelsWide
        val height = source.pixelsHigh
        if (width <= 0 || height <= 0) return null

        var scale = 1.0
        while (true) {
            val targetWidth = maxOf((width * scale).toLong(), MIN_DIMENSION)
            val targetHeight = maxOf((height * scale).toLong(), MIN_DIMENSION)
            val rep = if (scale >= 1.0) source else resample(source, targetWidth, targetHeight) ?: return null
            val png = rep.encodePng()
            if (png != null && png.size <= MAX_IMAGE_BYTES) return png
            if (scale <= MIN_SCALE) return null
            scale = maxOf(scale * SCALE_STEP, MIN_SCALE)
        }
    }

    // Resamples into a fresh offscreen bitmap context; independent of the screen or main
    // thread, so it is safe to call from the background clipboard-polling loop.
    private fun resample(source: NSBitmapImageRep, targetWidth: Long, targetHeight: Long): NSBitmapImageRep? {
        val rep = NSBitmapImageRep(
            bitmapDataPlanes = null,
            pixelsWide = targetWidth,
            pixelsHigh = targetHeight,
            bitsPerSample = 8,
            samplesPerPixel = 4,
            hasAlpha = true,
            isPlanar = false,
            colorSpaceName = NSDeviceRGBColorSpace,
            bytesPerRow = 0,
            bitsPerPixel = 0,
        ) ?: return null
        val context = NSGraphicsContext.graphicsContextWithBitmapImageRep(rep) ?: return null
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.setCurrentContext(context)
        source.drawInRect(NSMakeRect(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()))
        NSGraphicsContext.restoreGraphicsState()
        return rep
    }

    private fun NSBitmapImageRep.encodePng(): ByteArray? {
        val image = CGImage ?: return null
        val data = CFDataCreateMutable(null, 0) ?: return null
        try {
            val destination = CGImageDestinationCreateWithData(data, kUTTypePNG, 1u, null) ?: return null
            try {
                CGImageDestinationAddImage(destination, image, null)
                if (!CGImageDestinationFinalize(destination)) return null
                val length = CFDataGetLength(data).toInt()
                val bytes = CFDataGetBytePtr(data) ?: return null
                return ByteArray(length).also { out ->
                    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length.toULong()) }
                }
            } finally {
                CFRelease(destination.reinterpret())
            }
        } finally {
            CFRelease(data.reinterpret())
        }
    }

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

        // Headroom under the 4 MiB wire frame limit (envelope + AEAD overhead).
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        const val MIN_DIMENSION = 64L
        const val MIN_SCALE = 0.0625
        const val SCALE_STEP = 0.75
    }
}
