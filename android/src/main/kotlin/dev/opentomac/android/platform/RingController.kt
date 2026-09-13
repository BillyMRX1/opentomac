package dev.opentomac.android.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import dev.opentomac.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Framework-free state boundary for the single ring playback session. */
internal class RingPlaybackState {
    var ringing: Boolean = false
        private set

    fun started() { ringing = true }
    fun stopped() { ringing = false }
}

class RingController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendStatus: suspend (Boolean, String) -> Unit,
) {
    private val mutex = Mutex()
    private val state = RingPlaybackState()
    private var player: MediaPlayer? = null
    private var timeoutJob: Job? = null

    init { active = this }

    suspend fun start() = mutex.withLock {
        stopPlaybackLocked()
        val next = MediaPlayer()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: error("No default alarm sound is available")
            next.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context, uri)
                prepare()
                isLooping = true
                start()
            }
            player = next
            context.getSystemService(Vibrator::class.java).vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            state.started()
            postNotification()
            timeoutJob = scope.launch {
                delay(MAX_RINGING_MS)
                mutex.withLock {
                    stopPlaybackLocked(cancelTimeout = false)
                    sendStatus(false, "")
                }
            }
            sendStatus(true, "")
        } catch (t: Throwable) {
            if (player !== next) next.release()
            stopPlaybackLocked()
            sendStatus(false, t.message ?: "Could not play the alarm sound")
        }
    }

    suspend fun stopAndReport() = mutex.withLock {
        stopPlaybackLocked()
        sendStatus(false, "")
    }

    val isRinging: Boolean get() = state.ringing

    fun shutdown() {
        stopPlaybackLocked()
        if (active === this) active = null
    }

    private fun stopPlaybackLocked(cancelTimeout: Boolean = true) {
        if (cancelTimeout) timeoutJob?.cancel()
        timeoutJob = null
        player?.runCatching { stop() }
        player?.release()
        player = null
        context.getSystemService(Vibrator::class.java).cancel()
        if (state.ringing) {
            state.stopped()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun postNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ring phone", NotificationManager.IMPORTANCE_HIGH),
        )
        val stopIntent = Intent(context, RingStopReceiver::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_clipboard)
                .setContentTitle("Phone is ringing")
                .setContentText("Ringing from your Mac")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .addAction(0, "Stop", stopPendingIntent)
                .build(),
        )
    }

    companion object {
        const val ACTION_STOP = "dev.opentomac.android.action.STOP_RING"
        private const val CHANNEL_ID = "ring_phone"
        private const val NOTIFICATION_ID = 0x52494E47
        private const val MAX_RINGING_MS = 60_000L
        @Volatile private var active: RingController? = null
        fun stopFromNotification() {
            active?.let { controller -> controller.scope.launch { controller.stopAndReport() } }
        }
    }
}

class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RingController.ACTION_STOP) RingController.stopFromNotification()
    }
}
