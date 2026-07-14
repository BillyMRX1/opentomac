package dev.opentomac.shared.notifications

import dev.opentomac.shared.protocol.FilterUpdate

/** Agent-side notification policy. A deny-list blocks only the named packages. */
data class FilterPolicy(
    val deniedPackages: Set<String> = emptySet(),
    val paused: Boolean = false,
) {
    fun allows(packageId: String): Boolean = !paused && packageId !in deniedPackages

    /** Sorts the set so equivalent policies produce deterministic wire updates. */
    fun toUpdate(): FilterUpdate = FilterUpdate(
        deniedPackages = deniedPackages.sorted(),
        paused = paused,
    )

    companion object {
        fun from(update: FilterUpdate): FilterPolicy = FilterPolicy(
            deniedPackages = update.deniedPackages.toSet(),
            paused = update.paused,
        )
    }
}
