package dev.opentomac.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.service.ConnectionService
import dev.opentomac.shared.session.Capability
import dev.opentomac.shared.session.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Text-selection action (CLIP-005): sends the selected text to the paired device. */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (selected.isBlank()) {
            Toast.makeText(this, "No text selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // This activity can be the process's first entry point: boot the runtime
        // (which also auto-connects) and bound-wait for the session instead of
        // hanging forever on a runtime nothing else would initialize.
        ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        lifecycleScope.launch {
            val connected = withTimeoutOrNull(5_000) {
                AppRuntime.awaitReady()
                AppRuntime.connectionState.first { it is ConnectionState.Connected }
            } != null
            val supported = AppRuntime.peerSupports(Capability.CLIPBOARD)
            val sent = connected && supported && AppRuntime.sendText(selected)
            Toast.makeText(
                this@ProcessTextActivity,
                when {
                    !supported -> "Connected peer needs an update for clipboard sharing"
                    sent -> "Sent to paired device"
                    else -> "Could not send. Open opentomac to connect."
                },
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }
}
