package dev.opentomac.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.opentomac.android.R
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.ui.MainActivity
import dev.opentomac.shared.session.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConnectionService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(ConnectionState.Idle),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        serviceScope.launch {
            AppRuntime.initialize(applicationContext, serviceScope)
            AppRuntime.connectionState.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        AppRuntime.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.connection_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.connection_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tile_clipboard)
        .setContentTitle("opentomac")
        .setContentText(
            when (state) {
                ConnectionState.Unpaired -> "Ready to pair"
                ConnectionState.Idle -> "Ready to connect"
                is ConnectionState.Connecting -> "Connecting, attempt ${state.attempt}"
                is ConnectionState.Connected -> "Connected to ${state.peer.displayName}"
                is ConnectionState.Degraded -> "Connection limited: ${state.reason}"
            },
        )
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
        .build()

    inner class LocalBinder : Binder() {
        val runtime: AppRuntime
            get() = AppRuntime
    }

    companion object {
        const val CHANNEL_ID = "opentomac_connection"
        const val NOTIFICATION_ID = 1001
    }
}
