package dev.opentomac.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.opentomac.android.runtime.AppRuntime
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            AppRuntime.awaitReady()
            val sent = AppRuntime.sendText(selected)
            Toast.makeText(
                this@ProcessTextActivity,
                if (sent) "Sent to paired device" else "Could not send text",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }
}
