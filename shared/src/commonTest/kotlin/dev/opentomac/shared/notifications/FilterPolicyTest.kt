package dev.opentomac.shared.notifications

import dev.opentomac.shared.protocol.FilterUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterPolicyTest {

    @Test
    fun denyListAllowsEveryPackageNotExplicitlyDenied() {
        val policy = FilterPolicy(deniedPackages = setOf("com.blocked"))

        assertFalse(policy.allows("com.blocked"))
        assertTrue(policy.allows("com.allowed"))
        assertTrue(policy.allows("com.another"))
    }

    @Test
    fun pausedPolicyBlocksEveryPackage() {
        val policy = FilterPolicy(paused = true)

        assertFalse(policy.allows("com.allowed"))
        assertFalse(policy.allows("com.anything"))
    }

    @Test
    fun filterUpdatesRoundTripWithValueSemantics() {
        val update = FilterUpdate(
            deniedPackages = listOf("com.zeta", "com.alpha", "com.alpha"),
            paused = true,
        )

        val policy = FilterPolicy.from(update)

        assertEquals(FilterPolicy(setOf("com.alpha", "com.zeta"), paused = true), policy)
        assertEquals(
            FilterUpdate(listOf("com.alpha", "com.zeta"), paused = true),
            policy.toUpdate(),
        )
    }
}
