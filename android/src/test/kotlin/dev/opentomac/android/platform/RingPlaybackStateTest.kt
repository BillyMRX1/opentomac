package dev.opentomac.android.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RingPlaybackStateTest {
    @Test
    fun duplicateStartsRemainOneLogicalPlaybackAndStopsAreIdempotent() {
        val state = RingPlaybackState()
        state.started()
        state.started()
        assertTrue(state.ringing)
        state.stopped()
        state.stopped()
        assertFalse(state.ringing)
    }

    @Test
    fun timeoutStopClearsRingingState() {
        val state = RingPlaybackState()
        state.started()
        state.stopped()
        assertFalse(state.ringing)
    }
}
