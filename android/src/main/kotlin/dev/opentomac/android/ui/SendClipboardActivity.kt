package dev.opentomac.android.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.service.ConnectionService
import dev.opentomac.shared.session.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lightweight, transparent, no-history activity that sends the current clipboard to the paired
 * Mac.
 *
 * Android 10+ only lets the foreground app read the clipboard. Launching this activity brings the
 * app to the foreground temporarily. That is what grants clipboard access. The Quick Settings
 * tile and the persistent-connection notification action both point here so users can send without
 * keeping the full app open.
 *
 * Flow:
 *  1. Start (or reconnect to an already-running) ConnectionService so the runtime initialises.
 *  2. Wait up to 5 seconds for the runtime to be ready and a connection to be established.
 *  3. Call AppRuntime.sendClipboard() and show a Toast with the result.
 *  4. Finish immediately, leaving no UI on screen.
 */
class SendClipboardActivity : ComponentActivity() {
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, android.content.Intent(this, ConnectionService::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (sending) return
        sending = true
        lifecycleScope.launch {
            // Android can deny a clipboard read during the brief focus transition into this activity.
            delay(250)
            val connected = withTimeoutOrNull(5_000) {
                AppRuntime.awaitReady()
                AppRuntime.connectionState.first { it is ConnectionState.Connected }
            } != null
            val message = if (!connected) {
                "Not connected. Open opentomac to connect to your Mac."
            } else {
                val sent = AppRuntime.sendClipboard()
                if (sent) "Clipboard sent to Mac" else "Could not send clipboard"
            }
            Toast.makeText(this@SendClipboardActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
