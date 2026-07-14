package dev.opentomac.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.opentomac.android.runtime.AppRuntime
import kotlinx.coroutines.launch

/** Receives ACTION_SEND / ACTION_SEND_MULTIPLE and stages the shared items for transfer. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            AppRuntime.awaitReady()
            val count = AppRuntime.enqueueSharedUris(applicationContext, uris)
            Toast.makeText(
                this@ShareActivity,
                if (count > 0) "Shared $count item${if (count == 1) "" else "s"}" else "Could not read shared items",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
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
