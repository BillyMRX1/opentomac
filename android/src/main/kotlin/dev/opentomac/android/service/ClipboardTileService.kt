package dev.opentomac.android.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.shared.session.ConnectionState

class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            val connected = AppRuntime.connectionState.value is ConnectionState.Connected
            state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (connected) "Tap to send clipboard" else "Tap to open"
            }
            updateTile()
        }
    }

    // Android 10+ only lets the foreground app read the clipboard, so the tile can
    // never read it here: it opens the app, whose foreground sync sends the clipboard.
    override fun onClick() {
        super.onClick()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
