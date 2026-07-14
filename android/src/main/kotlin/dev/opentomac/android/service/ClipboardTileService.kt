package dev.opentomac.android.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.shared.session.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipboardTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (AppRuntime.connectionState.value is ConnectionState.Connected) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        scope.launch { AppRuntime.sendClipboard() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
