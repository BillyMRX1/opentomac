package dev.opentomac.shared.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityTest {
    @Test
    fun legacyPeersAllowKnownFeatures() {
        assertTrue(peerSupports(null, Capability.CLIPBOARD))
        assertTrue(peerSupports(emptySet(), Capability.CLIPBOARD))
    }

    @Test
    fun nonEmptyPeerListIsAuthoritative() {
        assertTrue(peerSupports(setOf(Capability.CLIPBOARD), Capability.CLIPBOARD))
        assertFalse(peerSupports(setOf(Capability.FILE_TRANSFER), Capability.CLIPBOARD))
    }

    @Test
    fun unknownValuesDoNotImplyKnownSupport() {
        assertFalse(peerSupports(setOf("future.feature"), Capability.CLIPBOARD))
    }
}
