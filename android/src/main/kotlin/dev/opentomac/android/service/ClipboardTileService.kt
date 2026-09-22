package dev.opentomac.android.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.ui.SendClipboardActivity
import dev.opentomac.shared.session.ConnectionState

class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            val connected = AppRuntime.connectionState.value is ConnectionState.Connected
            state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (connected) "Tap to send clipboard" else "Not connected"
            }
            updateTile()
        }
    }

    // Android 10+ requires the foreground app to read the clipboard. Launching
    // SendClipboardActivity gives the app a transient foreground window so the
    // read succeeds without leaving the full app open.
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
