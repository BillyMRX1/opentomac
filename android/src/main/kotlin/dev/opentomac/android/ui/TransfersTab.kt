package dev.opentomac.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.shared.transfer.TransferDirection
import dev.opentomac.shared.transfer.TransferJob
import dev.opentomac.shared.transfer.TransferState
import java.io.File
import kotlinx.coroutines.launch

/** Transfers tab: the full transfer list (send and receive), unrestricted by count. */
@Composable
internal fun TransfersTab(modifier: Modifier = Modifier, listState: LazyListState) {
    val transfers by AppRuntime.transfers.collectAsStateWithLifecycle()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp),
    ) {
        item {
            SectionTitle("Transfers")
            if (transfers.isEmpty()) {
                EmptyStateCard(
                    glyph = Glyph.Transfer,
                    title = "No transfers yet",
                    body = "Sent and received files appear here.",
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    transfers.sortedByDescending { it.jobId }.forEach { job ->
                        TransferRow(job)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(job: TransferJob) {
    val progress by job.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = job.files.firstOrNull()?.name ?: "files"
    val label = if (job.direction == TransferDirection.RECEIVE) "Received" else "Sent"
    val pct = if (progress.totalBytes > 0) {
        (progress.completedBytes * 100 / progress.totalBytes).toInt()
    } else {
        0
    }
    val done = progress.state == TransferState.DONE
    val active = !progress.state.let {
        it == TransferState.DONE || it == TransferState.FAILED || it == TransferState.CANCELLED
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TonalGlyph(Glyph.Transfer)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "$label: $name",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${progress.state.name.lowercase().replaceFirstChar { it.uppercase() }} · $pct%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (active) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    )
                }
            }
            when {
                active -> FilledTonalButton(
                    onClick = { scope.launch { AppRuntime.cancelTransfer(job.jobId) } },
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) { Text("Cancel") }
                done && job.direction == TransferDirection.RECEIVE -> FilledTonalButton(
                    onClick = { openReceived(context, name) },
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) { Text("Open") }
            }
        }
    }
}

private fun openReceived(context: Context, name: String) {
    val dir = AppRuntime.receiveDirectoryFile() ?: return
    val file = File(dir, name)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
