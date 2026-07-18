package dev.opentomac.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.service.ConnectionService
import dev.opentomac.shared.session.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Receives ACTION_SEND / ACTION_SEND_MULTIPLE and sends text, links, or staged files. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        val text = extractPlainText(intent)
        if (uris.isEmpty() && text == null) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        lifecycleScope.launch {
            val result = if (uris.isNotEmpty()) {
                AppRuntime.awaitReady()
                val count = AppRuntime.enqueueSharedUris(applicationContext, uris)
                if (count > 0) {
                    "Shared $count item${if (count == 1) "" else "s"}"
                } else {
                    "Could not read shared items"
                }
            } else {
                val connected = withTimeoutOrNull(5_000) {
                    AppRuntime.awaitReady()
                    AppRuntime.connectionState.first { it is ConnectionState.Connected }
                } != null
                val value = requireNotNull(text)
                val isUrl = AppRuntime.isHttpUrl(value)
                val sent = connected && if (isUrl) {
                    AppRuntime.openUrlOnPeer(value)
                } else {
                    AppRuntime.sendText(value)
                }
                if (sent) {
                    if (isUrl) "Link sent to Mac" else "Text sent to Mac"
                } else {
                    "Could not send. Open opentomac to connect."
                }
            }
            Toast.makeText(this@ShareActivity, result, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun extractPlainText(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    private inline fun <reified T> Intent.parcelableExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }

    private inline fun <reified T> Intent.parcelableArrayListExtra(name: String): List<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(name)
        }
}
