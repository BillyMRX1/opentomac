package dev.opentomac.shared.clipboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional, bounded clipboard history. Entries are returned newest-first. */
class ClipboardHistory(limit: Int? = null) {
    private var limit: Int? = validateLimit(limit)
    private val entries = mutableListOf<ClipItem>()
    private val mutableSnapshot = MutableStateFlow<List<ClipItem>>(emptyList())

    /** Newest-first snapshot that updates on every record/clear/setLimit, for reactive UI. */
    val snapshot: StateFlow<List<ClipItem>> = mutableSnapshot.asStateFlow()

    /** Records a non-sensitive item when history is enabled. */
    fun record(item: ClipItem) {
        if (item.sensitive) return
        val currentLimit = limit ?: return
        entries += item
        while (entries.size > currentLimit) {
            entries.removeAt(0)
        }
        publish()
    }

    /** Returns a snapshot ordered from newest to oldest. */
    fun items(): List<ClipItem> = entries.asReversed().toList()

    fun clear() {
        entries.clear()
        publish()
    }

    /** Changes the cap, dropping oldest entries; null disables and clears history. */
    fun setLimit(newLimit: Int?) {
        limit = validateLimit(newLimit)
        if (newLimit == null) {
            clear()
            return
        }
        while (entries.size > newLimit) {
            entries.removeAt(0)
        }
        publish()
    }

    private fun publish() {
        mutableSnapshot.value = items()
    }

    private companion object {
        val SUPPORTED_LIMITS = setOf(10, 20, 50)

        fun validateLimit(limit: Int?): Int? {
            require(limit == null || limit in SUPPORTED_LIMITS) {
                "Clipboard history limit must be 10, 20, 50, or null (disabled); got $limit"
            }
            return limit
        }
    }
}
