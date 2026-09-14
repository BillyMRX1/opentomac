package dev.opentomac.android.runtime

import java.io.File

/** One file found in the private receive directory, independent of any in-memory transfer job. */
data class ReceivedFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

/**
 * Lists regular files in [directory], newest first. In-progress `.part` files are excluded so a
 * receive still mid-flight never shows up as a finished file. A missing/unreadable directory
 * yields an empty list rather than throwing.
 */
internal fun scanReceivedFiles(directory: File): List<ReceivedFile> {
    val files = directory.listFiles { file -> file.isFile && !file.name.endsWith(".part") }
        ?: return emptyList()
    return files
        .map { ReceivedFile(name = it.name, sizeBytes = it.length(), modifiedAtMs = it.lastModified()) }
        .sortedByDescending { it.modifiedAtMs }
}
