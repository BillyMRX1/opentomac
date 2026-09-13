package dev.opentomac.android.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryReadingTest {
    @Test
    fun parsesAndClampsBatteryIntent() {
        assertEquals(BatteryReading(100, charging = true, sampledAtMs = 42), BatteryReading.fromValues(150, 100, 5, 42))
    }

    @Test
    fun invalidLevelOrScaleIsUnavailable() {
        assertNull(BatteryReading.fromValues(50, null, null, 42))
        assertNull(BatteryReading.fromValues(50, 0, null, 42))
    }

    @Test
    fun trackerOnlyAcceptsMaterialChanges() {
        val tracker = BatteryReadingTracker()
        val first = BatteryReading(50, charging = false, sampledAtMs = 1)
        assertTrue(tracker.update(first))
        assertFalse(tracker.update(first.copy(sampledAtMs = 2)))
        assertTrue(tracker.update(first.copy(charging = true, sampledAtMs = 3)))
    }
}
