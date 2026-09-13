package dev.opentomac.android.runtime

import android.content.Intent
import android.os.BatteryManager

data class BatteryReading(
    val percentage: Int,
    val charging: Boolean,
    val sampledAtMs: Long,
) {
    companion object {
        fun fromIntent(intent: Intent, sampledAtMs: Long): BatteryReading? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return fromValues(level, scale, status, sampledAtMs)
        }

        fun fromValues(level: Int?, scale: Int?, status: Int?, sampledAtMs: Long): BatteryReading? {
            if (level == null || scale == null || level < 0 || scale <= 0) return null
            val percentage = (level * 100L / scale).coerceIn(0L, 100L).toInt()
            return BatteryReading(
                percentage = percentage,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
                sampledAtMs = sampledAtMs,
            )
        }
    }
}

class BatteryReadingTracker {
    private var last: BatteryReading? = null

    fun update(reading: BatteryReading): Boolean {
        val changed = last?.let { it.percentage != reading.percentage || it.charging != reading.charging } ?: true
        last = reading
        return changed
    }
}
