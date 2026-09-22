package dev.opentomac.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.shared.clipboard.ClipItem
import dev.opentomac.shared.clipboard.ClipType
import dev.opentomac.shared.session.Capability
import dev.opentomac.shared.session.ConnectionState
import dev.opentomac.shared.session.peerSupports
import kotlinx.coroutines.launch

private val HistoryLimitOptions: List<Int?> = listOf(null, 10, 20, 50)

/** Clipboard tab: history limit control, past entries, and per-row copy/resend actions. */
@Composable
internal fun ClipboardTab(modifier: Modifier = Modifier, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val limit by AppRuntime.clipboardHistoryLimit.collectAsStateWithLifecycle()
    val history by AppRuntime.clipboardHistory.collectAsStateWithLifecycle()
    val connectionState by AppRuntime.connectionState.collectAsStateWithLifecycle()
    val peerCapabilities by AppRuntime.peerCapabilities.collectAsStateWithLifecycle()
    val connected = connectionState is ConnectionState.Connected
    val canResend = connected && peerSupports(peerCapabilities, Capability.CLIPBOARD)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp),
    ) {
        item {
            SectionTitle("Clipboard History")
            Text(
                "Android only lets apps read the clipboard while they're in the foreground, so " +
                    "opentomac can't send new copies automatically in the background. To send " +
                    "explicitly: add the Send clipboard tile from Quick Settings edit, use the Send clipboard " +
                    "action on the connection notification, the share sheet from any app, or " +
                    "select text and choose Send with opentomac.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            HistoryLimitPicker(
                selected = limit,
                onSelect = { newLimit -> scope.launch { AppRuntime.setClipboardHistoryLimit(newLimit) } },
            )
            if (!connected) {
                Text(
                    "Not connected. Resending a clipboard item requires a connected Mac.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            when {
                limit == null -> EmptyStateCard(
                    glyph = Glyph.Clipboard,
                    title = "History is disabled",
                    body = "Choose 10, 20, or 50 above to start saving clipboard items on this phone.",
                )
                history.isEmpty() -> EmptyStateCard(
                    glyph = Glyph.Clipboard,
                    title = "No clipboard history yet",
                    body = "Items you copy or receive while history is on will appear here.",
                )
                else -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                        OutlinedButton(onClick = { AppRuntime.clearClipboardHistory() }) {
                            Text("Clear History")
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { entry ->
                            ClipboardHistoryRow(
                                item = entry,
                                resendEnabled = canResend,
                                onCopy = { scope.launch { AppRuntime.copyClipboardHistoryItem(entry) } },
                                onResend = { scope.launch { AppRuntime.resendClipboardHistoryItem(entry) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryLimitPicker(selected: Int?, onSelect: (Int?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryLimitOptions.forEach { option ->
            val isSelected = option == selected
            FilledTonalButton(
                onClick = { onSelect(option) },
                shape = RoundedCornerShape(18.dp),
                colors = if (isSelected) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(option?.toString() ?: "Disabled")
            }
        }
    }
}

@Composable
private fun ClipboardHistoryRow(
    item: ClipItem,
    resendEnabled: Boolean,
    onCopy: () -> Unit,
    onResend: () -> Unit,
) {
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
            TonalGlyph(Glyph.Clipboard)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.previewText(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = onCopy,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = onResend,
                    enabled = resendEnabled,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("Resend") }
            }
        }
    }
}

private fun ClipItem.previewText(): String = when (type) {
    ClipType.IMAGE -> "Image (${formatBytes(payload.size)})"
    else -> payload.decodeToString().ifBlank { "(empty)" }
}

private fun formatBytes(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
