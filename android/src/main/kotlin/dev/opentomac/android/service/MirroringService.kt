package dev.opentomac.android.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.opentomac.android.R
import dev.opentomac.android.mirror.ScreenMirrorController
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.ui.MainActivity
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.protocol.MirrorStop
import dev.opentomac.shared.session.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground owner for the MediaProjection consent token and encoder resources. */
class MirroringService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ending = AtomicBoolean(false)
    private var controller: ScreenMirrorController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        AppRuntime.attachMirroringService { reason, notifyPeer -> finish(reason, notifyPeer) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(intent.getStringExtra(EXTRA_REASON) ?: "Stopped on phone", notifyPeer = true)
            return START_NOT_STICKY
        }
        if (controller != null || ending.get()) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.projectionData()
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            finish("Screen capture permission unavailable", notifyPeer = true)
            return START_NOT_STICKY
        }
        if (AppRuntime.connectionState.value !is ConnectionState.Connected) {
            finish("Connection lost", notifyPeer = true)
            return START_NOT_STICKY
        }
        AppRuntime.setMirroringActive(true)
        try {
            // Android 14+ requires this after startForeground declared the
            // mediaProjection service type, which onCreate has done above.
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
            val mirror = ScreenMirrorController(
                context = applicationContext,
                projection = projection,
                send = AppRuntime::sendMirrorMessage,
                onProjectionStopped = {
                    finish("Screen capture stopped", notifyPeer = true)
                },
                onFailure = { cause ->
                    Log.w("opentomac", "screen mirror encoder failed", cause)
                    finish("Screen encoder failed", notifyPeer = true)
                },
            )
            controller = mirror
            mirror.start()
        } catch (cause: Throwable) {
            Log.w("opentomac", "could not start screen mirroring", cause)
            finish("Could not start screen mirroring", notifyPeer = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppRuntime.detachMirroringService()
        AppRuntime.setMirroringActive(false)
        serviceScope.launch {
            controller?.close()
            controller = null
        }.invokeOnCompletion {
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun finish(reason: String, notifyPeer: Boolean) {
        if (!ending.compareAndSet(false, true)) return
        serviceScope.launch {
            controller?.close()
            controller = null
            AppRuntime.setMirroringActive(false)
            if (notifyPeer) AppRuntime.sendMirrorMessage(
                ChannelId.EVENT,
                MirrorStop(reason),
            )
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen mirroring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tile_clipboard)
        .setContentTitle("opentomac")
        .setContentText("Mirroring screen to Mac")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "Stop mirroring",
            PendingIntent.getService(
                this,
                1,
                Intent(this, MirroringService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    @Suppress("DEPRECATION")
    private fun Intent.projectionData(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        getParcelableExtra(EXTRA_RESULT_DATA)
    }

    companion object {
        private const val CHANNEL_ID = "opentomac_mirroring"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "dev.opentomac.android.action.STOP_MIRRORING"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_REASON = "reason"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MirroringService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
