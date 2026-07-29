package dev.opentomac.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opentomac.android.BuildConfig
import dev.opentomac.android.runtime.AppRuntime

/** Settings tab: screenshot auto-send preference and the app version footer. */
@Composable
internal fun SettingsTab(
    modifier: Modifier = Modifier,
    onAutoSendScreenshotsChanged: (Boolean) -> Unit,
) {
    val autoSendScreenshots by AppRuntime.autoSendScreenshots.collectAsStateWithLifecycle()
    val ready by AppRuntime.ready.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 44.dp),
    ) {
        SectionTitle("Settings")
        ScreenshotSwitchRow(
            checked = autoSendScreenshots,
            enabled = ready,
            onCheckedChange = onAutoSendScreenshotsChanged,
        )
        VersionFooter()
    }
}

@Composable
private fun ScreenshotSwitchRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Offer new screenshots to Mac", style = MaterialTheme.typography.titleSmall)
            Text(
                if (enabled) "You choose whether to send each one." else "Finishing app setup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun VersionFooter() {
    Text(
        text = "opentomac ${BuildConfig.VERSION_NAME}",
        modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
