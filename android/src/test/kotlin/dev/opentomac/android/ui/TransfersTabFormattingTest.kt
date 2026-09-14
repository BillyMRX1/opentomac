package dev.opentomac.android.ui

import dev.opentomac.shared.transfer.TransferState
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TransfersTabFormattingTest {
    @Test
    fun classifiesTransferStatusTone() {
        assertEquals(TransferStatusTone.SUCCESS, transferStatusTone(TransferState.DONE))
        assertEquals(TransferStatusTone.FAILURE, transferStatusTone(TransferState.FAILED))
        assertEquals(TransferStatusTone.FAILURE, transferStatusTone(TransferState.CANCELLED))
        assertEquals(TransferStatusTone.ACTIVE, transferStatusTone(TransferState.ACTIVE))
        assertEquals(TransferStatusTone.ACTIVE, transferStatusTone(TransferState.OFFERED))
    }

    @Test
    fun formatsFileSizesByMagnitude() {
        assertEquals("512 B", formatFileSize(512L))
        assertEquals("1.0 KB", formatFileSize(1024L))
        assertEquals("1.5 KB", formatFileSize(1536L))
        assertEquals("2.0 MB", formatFileSize(2L * 1024 * 1024))
        assertEquals("10 MB", formatFileSize(10L * 1024 * 1024))
        assertEquals("1.0 GB", formatFileSize(1024L * 1024 * 1024))
    }

    @Test
    fun formatsReceivedTimestampInGivenZone() {
        val utc = ZoneId.of("UTC")
        val epochMs = Instant.parse("2026-01-02T03:04:00Z").toEpochMilli()
        assertEquals("Jan 2, 2026 3:04 AM", formatReceivedTimestamp(epochMs, utc))
    }
}
