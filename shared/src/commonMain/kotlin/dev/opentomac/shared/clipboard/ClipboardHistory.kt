package dev.opentomac.shared.clipboard

/** Optional, bounded clipboard history. Entries are returned newest-first. */
class ClipboardHistory(limit: Int? = null) {
    private var limit: Int? = validateLimit(limit)
    private val entries = mutableListOf<ClipItem>()

    /** Records a non-sensitive item when history is enabled. */
    fun record(item: ClipItem) {
        if (item.sensitive) return
        val currentLimit = limit ?: return
        entries += item
        while (entries.size > currentLimit) {
            entries.removeAt(0)
        }
    }

    /** Returns a snapshot ordered from newest to oldest. */
    fun items(): List<ClipItem> = entries.asReversed().toList()

    fun clear() {
        entries.clear()
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
