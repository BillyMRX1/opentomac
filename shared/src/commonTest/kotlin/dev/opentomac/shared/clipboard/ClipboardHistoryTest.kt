package dev.opentomac.shared.clipboard

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClipboardHistoryTest {

    @Test
    fun supportedLimitsCapHistoryAndReturnNewestFirst() = runTest {
        for (limit in listOf(10, 20, 50)) {
            val history = ClipboardHistory(limit)
            repeat(limit + 3) { history.record(historyClip("item-$it")) }

            assertEquals(limit, history.items().size)
            assertEquals("item-${limit + 2}", history.items().first().historyText())
            assertEquals("item-3", history.items().last().historyText())
        }
    }

    @Test
    fun constructorAndSetLimitRejectUnsupportedLimits() {
        for (limit in listOf(-1, 0, 1, 9, 11, 21, 49, 51)) {
            assertFailsWith<IllegalArgumentException> { ClipboardHistory(limit) }
        }

        val history = ClipboardHistory(10)
        assertFailsWith<IllegalArgumentException> { history.setLimit(30) }
    }

    @Test
    fun clearRemovesAllItems() = runTest {
        val history = ClipboardHistory(10)
        history.record(historyClip("first"))
        history.record(historyClip("second"))

        history.clear()

        assertTrue(history.items().isEmpty())
    }

    @Test
    fun setLimitTruncatesOldestItems() = runTest {
        val history = ClipboardHistory(50)
        repeat(30) { history.record(historyClip("item-$it")) }

        history.setLimit(10)

        assertEquals((29 downTo 20).map { "item-$it" }, history.items().map { it.historyText() })
    }

    @Test
    fun sensitiveItemsAreNeverRecorded() = runTest {
        val history = ClipboardHistory(10)

        history.record(historyClip("safe"))
        history.record(historyClip("secret", sensitive = true))

        assertEquals(listOf("safe"), history.items().map { it.historyText() })
    }

    @Test
    fun nullLimitDisablesRecordingAndCanBeChangedAtRuntime() = runTest {
        val history = ClipboardHistory(null)
        history.record(historyClip("disabled"))
        assertTrue(history.items().isEmpty())

        history.setLimit(10)
        history.record(historyClip("enabled"))
        assertEquals(listOf("enabled"), history.items().map { it.historyText() })

        history.setLimit(null)
        assertTrue(history.items().isEmpty())
    }
}

private suspend fun historyClip(value: String, sensitive: Boolean = false): ClipItem =
    ClipItem.create(ClipType.TEXT, value.encodeToByteArray(), sensitive)

private fun ClipItem.historyText(): String = payload.decodeToString()
