package dev.opentomac.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.opentomac.android.R
import dev.opentomac.android.camera.CameraStreamController
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.ui.MainActivity
import dev.opentomac.shared.protocol.CameraStop
import dev.opentomac.shared.protocol.ChannelId
import dev.opentomac.shared.session.ConnectionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground owner for phone camera/microphone capture and their encoder resources. */
class CameraService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ending = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Unit>()
    private var controller: CameraStreamController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppRuntime.attachCameraService { reason, notifyPeer -> finish(reason, notifyPeer) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(intent.getStringExtra(EXTRA_REASON) ?: "Stopped on phone", notifyPeer = true)
            return START_NOT_STICKY
        }
        if (controller != null || ending.get()) return START_NOT_STICKY

        val facing = intent?.getStringExtra(EXTRA_FACING) ?: CameraStreamController.FACING_FRONT
        val withAudio = intent?.getBooleanExtra(EXTRA_WITH_AUDIO, true) ?: true
        val foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (withAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(withAudio), foregroundTypes)

        if (AppRuntime.connectionState.value !is ConnectionState.Connected) {
            finish("Connection lost", notifyPeer = true)
            return START_NOT_STICKY
        }
        if (!AppRuntime.activateCameraCapture()) {
            finish("Camera capture ownership lost", notifyPeer = true)
            return START_NOT_STICKY
        }
        try {
            val stream = CameraStreamController(
                context = applicationContext,
                facing = facing,
                withAudio = withAudio,
                send = AppRuntime::sendCameraMessage,
                onFailure = { cause ->
                    Log.w("opentomac", "camera stream failed", cause)
                    finish("Camera encoder failed", notifyPeer = true)
                },
            )
            controller = stream
            stream.start()
        } catch (cause: Throwable) {
            Log.w("opentomac", "could not start camera stream", cause)
            finish("Could not start camera", notifyPeer = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        finish("Camera service stopped", notifyPeer = true).invokeOnCompletion {
            AppRuntime.detachCameraService()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun finish(reason: String, notifyPeer: Boolean): Deferred<Unit> {
        if (!ending.compareAndSet(false, true)) return stopped
        AppRuntime.beginCameraCleanup()
        serviceScope.launch {
            try {
                runCatching { controller?.close() }
                    .onFailure { Log.w("opentomac", "camera cleanup failed", it) }
                controller = null
                runCatching {
                    ServiceCompat.stopForeground(this@CameraService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }.onFailure { Log.w("opentomac", "could not remove camera foreground notification", it) }
                runCatching { stopSelf() }
                    .onFailure { Log.w("opentomac", "could not stop camera service", it) }
                if (notifyPeer) {
                    withTimeoutOrNull(STOP_SEND_TIMEOUT_MS) {
                        AppRuntime.sendCameraMessage(ChannelId.EVENT, CameraStop(reason))
                    }
                }
            } finally {
                AppRuntime.releaseCameraCapture()
                stopped.complete(Unit)
            }
        }
        return stopped
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera streaming",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(withAudio: Boolean) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tile_clipboard)
        .setContentTitle("opentomac")
        .setContentText(if (withAudio) "Camera and microphone streaming to Mac" else "Camera streaming to Mac")
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
            "Stop camera",
            PendingIntent.getService(
                this,
                1,
                Intent(this, CameraService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "opentomac_camera"
        private const val NOTIFICATION_ID = 1004
        private const val ACTION_STOP = "dev.opentomac.android.action.STOP_CAMERA"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_FACING = "facing"
        private const val EXTRA_WITH_AUDIO = "with_audio"
        private const val STOP_SEND_TIMEOUT_MS = 1_000L

        fun start(context: Context, facing: String, withAudio: Boolean) {
            val intent = Intent(context, CameraService::class.java).apply {
                putExtra(EXTRA_FACING, facing)
                putExtra(EXTRA_WITH_AUDIO, withAudio)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
