package dev.opentomac.android.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceivedFilesTest {
    @Test
    fun listsRegularFilesNewestFirstAndSkipsInProgressParts() {
        val dir = Files.createTempDirectory("received-files-test").toFile()
        try {
            val older = File(dir, "older.txt").apply { writeText("a") }
            older.setLastModified(1_000L)
            val newer = File(dir, "newer.txt").apply { writeText("bb") }
            newer.setLastModified(2_000L)
            File(dir, "incoming.txt.part").writeText("still downloading")
            File(dir, "subfolder").mkdir()

            val result = scanReceivedFiles(dir)

            assertEquals(listOf("newer.txt", "older.txt"), result.map { it.name })
            assertEquals(2L, result[0].sizeBytes)
            assertEquals(1L, result[1].sizeBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryYieldsEmptyList() {
        val missing = File(Files.createTempDirectory("received-files-test").toFile(), "does-not-exist")
        assertTrue(scanReceivedFiles(missing).isEmpty())
    }
}
