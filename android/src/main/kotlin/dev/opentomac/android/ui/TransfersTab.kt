package dev.opentomac.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.runtime.ReceivedFile
import dev.opentomac.android.ui.theme.Success
import dev.opentomac.shared.transfer.TransferDirection
import dev.opentomac.shared.transfer.TransferJob
import dev.opentomac.shared.transfer.TransferState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Transfers tab: active/recent transfer jobs, plus a Received Files section backed by disk. */
@Composable
internal fun TransfersTab(modifier: Modifier = Modifier, listState: LazyListState) {
    val transfers by AppRuntime.transfers.collectAsStateWithLifecycle()
    val receivedFiles by AppRuntime.receivedFiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var pendingDeleteFile by remember { mutableStateOf<String?>(null) }
    var pendingDeleteAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AppRuntime.refreshReceivedFiles() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { AppRuntime.refreshReceivedFiles() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                val transferStates = transfers.map { job -> job.progress.collectAsStateWithLifecycle().value }
                val hasCompletedTransfers = transferStates.any { transferStatusTone(it.state) != TransferStatusTone.ACTIVE }
                if (hasCompletedTransfers) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                        OutlinedButton(onClick = { AppRuntime.clearCompletedTransfers() }) {
                            Text("Clear completed")
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    transfers.sortedByDescending { it.jobId }.forEach { job ->
                        TransferRow(job)
                    }
                }
            }
        }
        item {
            SectionTitle("Received Files")
            if (receivedFiles.isEmpty()) {
                EmptyStateCard(
                    glyph = Glyph.Transfer,
                    title = "No received files",
                    body = "Files received from your Mac appear here.",
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                    OutlinedButton(onClick = { pendingDeleteAll = true }) {
                        Text("Delete all")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    receivedFiles.forEach { file ->
                        ReceivedFileRow(
                            file = file,
                            onOpen = {
                                scope.launch {
                                    when (tryOpenReceivedFile(context, file.name)) {
                                        OpenReceivedResult.MISSING -> AppRuntime.reportMissingReceivedFile(file.name)
                                        OpenReceivedResult.NO_HANDLER -> AppRuntime.reportReceivedFileOpenFailed(file.name)
                                        OpenReceivedResult.OPENED -> Unit
                                    }
                                }
                            },
                            onDelete = { pendingDeleteFile = file.name },
                        )
                    }
                }
            }
        }
    }

    pendingDeleteFile?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            title = { Text("Delete file") },
            text = {
                Text("This permanently deletes \"$name\" from this phone, not just from transfer history. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteFile = null
                        scope.launch { AppRuntime.deleteReceivedFile(name) }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFile = null }) { Text("Cancel") }
            },
        )
    }
    if (pendingDeleteAll) {
        AlertDialog(
            onDismissRequest = { pendingDeleteAll = false },
            title = { Text("Delete all received files") },
            text = {
                Text(
                    "This permanently deletes all ${receivedFiles.size} received files from this phone, " +
                        "not just from transfer history. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteAll = false
                        scope.launch { AppRuntime.deleteAllReceivedFiles() }
                    },
                ) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAll = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TransferRow(job: TransferJob) {
    val progress by job.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val summary = when (job.files.size) {
        0 -> "files"
        1 -> job.files[0].name
        else -> "${job.files.size} files"
    }
    val label = if (job.direction == TransferDirection.RECEIVE) "Received" else "Sent"
    val pct = if (progress.totalBytes > 0) {
        (progress.completedBytes * 100 / progress.totalBytes).toInt()
    } else {
        0
    }
    val tone = transferStatusTone(progress.state)
    val active = tone == TransferStatusTone.ACTIVE
    val statusColor = when (tone) {
        TransferStatusTone.SUCCESS -> Success
        TransferStatusTone.FAILURE -> MaterialTheme.colorScheme.error
        TransferStatusTone.ACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
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
                    "$label: $summary",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${progress.state.name.lowercase().replaceFirstChar { it.uppercase() }} · $pct%",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
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
            }
        }
    }
}

@Composable
private fun ReceivedFileRow(file: ReceivedFile, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
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
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatFileSize(file.sizeBytes)} · ${formatReceivedTimestamp(file.modifiedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onDelete,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) { Text("Delete") }
        }
    }
}

private enum class OpenReceivedResult { OPENED, MISSING, NO_HANDLER }

private fun tryOpenReceivedFile(context: Context, name: String): OpenReceivedResult {
    val dir = AppRuntime.receiveDirectoryFile() ?: return OpenReceivedResult.MISSING
    val file = File(dir, name)
    if (!file.exists()) return OpenReceivedResult.MISSING
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return if (runCatching { context.startActivity(intent) }.isSuccess) {
        OpenReceivedResult.OPENED
    } else {
        OpenReceivedResult.NO_HANDLER
    }
}

internal enum class TransferStatusTone { ACTIVE, SUCCESS, FAILURE }

internal fun transferStatusTone(state: TransferState): TransferStatusTone = when (state) {
    TransferState.DONE -> TransferStatusTone.SUCCESS
    TransferState.FAILED, TransferState.CANCELLED -> TransferStatusTone.FAILURE
    TransferState.OFFERED, TransferState.ACTIVE -> TransferStatusTone.ACTIVE
}

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val pattern = if (value < 10) "%.1f %s" else "%.0f %s"
    return String.format(Locale.US, pattern, value, units[unitIndex])
}

private val receivedTimestampFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)

internal fun formatReceivedTimestamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(receivedTimestampFormatter)
