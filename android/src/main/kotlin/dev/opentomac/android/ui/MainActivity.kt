package dev.opentomac.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.service.ConnectionService
import dev.opentomac.android.service.OpentomacControlService
import dev.opentomac.shared.pairing.TrustedDevice
import dev.opentomac.shared.session.ConnectionState
import dev.opentomac.shared.transfer.TransferDirection
import dev.opentomac.shared.transfer.TransferJob
import dev.opentomac.shared.transfer.TransferState
import android.net.Uri
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.launch

/** Single-activity host for the dashboard and pairing screens. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startConnectionService()
        setContent { OpentomacApp() }
        if (intent.getBooleanExtra(AppRuntime.EXTRA_REQUEST_MIRROR_CONSENT, false)) {
            requestMirrorConsent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AppRuntime.EXTRA_REQUEST_MIRROR_CONSENT, false)) {
            requestMirrorConsent(intent)
        }
    }

    private fun requestMirrorConsent(intent: Intent) {
        AppRuntime.requestMirrorConsentFromUi(
            maxLongEdge = intent.getIntExtra(AppRuntime.EXTRA_MIRROR_MAX_LONG_EDGE, 1280),
            bitrateBps = intent.getIntExtra(AppRuntime.EXTRA_MIRROR_BITRATE_BPS, 6_000_000),
        )
    }

    private fun startConnectionService() {
        val intent = Intent(this, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

private enum class Screen { DASHBOARD, PAIR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpentomacApp() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        var screen by remember { mutableStateOf(Screen.DASHBOARD) }
        val mirrorConsentRequested by AppRuntime.mirrorConsentRequested.collectAsStateWithLifecycle()

        val projectionManager = remember {
            context.getSystemService(MediaProjectionManager::class.java)
        }
        val mirrorConsent = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                AppRuntime.startMirroring(result.resultCode, data)
            } else {
                AppRuntime.mirrorConsentDenied()
            }
        }
        val requestMirrorConsent = {
            AppRuntime.consumeMirrorConsentRequest()
            val consentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay(),
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            mirrorConsent.launch(consentIntent)
        }
        LaunchedEffect(mirrorConsentRequested) {
            if (mirrorConsentRequested) requestMirrorConsent()
        }

        val notice by AppRuntime.notice.collectAsStateWithLifecycle()
        LaunchedEffect(notice) {
            notice?.let {
                snackbar.showSnackbar(it)
                AppRuntime.clearNotice()
            }
        }

        val permissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {}
        LaunchedEffect(Unit) {
            val wanted = buildList {
                add(Manifest.permission.READ_CONTACTS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            permissions.launch(wanted.toTypedArray())
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = { Text("opentomac") }) },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                when (screen) {
                    Screen.DASHBOARD -> DashboardScreen(
                        onPair = { screen = Screen.PAIR },
                        onSendClipboard = { scope.launch { AppRuntime.sendClipboard() } },
                        onConnect = { device -> scope.launch { AppRuntime.connect(device) } },
                        onForget = { device -> scope.launch { AppRuntime.forget(device) } },
                        onSendFiles = { uris -> scope.launch { AppRuntime.enqueueSharedUris(context, uris) } },
                        onAutoSendScreenshotsChanged = { enabled ->
                            scope.launch { AppRuntime.setAutoSendScreenshots(enabled) }
                        },
                        onMirror = requestMirrorConsent,
                        onStopMirroring = AppRuntime::stopMirroring,
                    )
                    Screen.PAIR -> PairScreen(onDone = { screen = Screen.DASHBOARD })
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    onPair: () -> Unit,
    onSendClipboard: () -> Unit,
    onConnect: (TrustedDevice) -> Unit,
    onForget: (TrustedDevice) -> Unit,
    onSendFiles: (List<Uri>) -> Unit,
    onAutoSendScreenshotsChanged: (Boolean) -> Unit,
    onMirror: () -> Unit,
    onStopMirroring: () -> Unit,
) {
    val state by AppRuntime.connectionState.collectAsStateWithLifecycle()
    val devices by AppRuntime.pairedDevices.collectAsStateWithLifecycle()
    val transfers by AppRuntime.transfers.collectAsStateWithLifecycle()
    val autoSendScreenshots by AppRuntime.autoSendScreenshots.collectAsStateWithLifecycle()
    val ready by AppRuntime.ready.collectAsStateWithLifecycle()
    val mirroring by AppRuntime.mirroring.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onSendFiles(uris) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Connection", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(state.describe(), style = MaterialTheme.typography.titleMedium)
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onSendClipboard) { Text("Send clipboard") }
        Button(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Send file") }
        Button(onClick = onPair) { Text("Pair") }
    }
    Spacer(Modifier.height(8.dp))
    if (mirroring) {
        OutlinedButton(onClick = onStopMirroring) { Text("Stop mirroring") }
    } else {
        Button(
            onClick = onMirror,
            enabled = state is ConnectionState.Connected,
        ) { Text("Mirror to Mac") }
    }

    Spacer(Modifier.height(8.dp))
    NotificationAccessRow()
    ControlAccessRow()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Offer new screenshots to Mac",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = autoSendScreenshots,
            onCheckedChange = onAutoSendScreenshotsChanged,
            enabled = ready,
        )
    }

    if (transfers.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Transfers", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        transfers.sortedByDescending { it.jobId }.take(8).forEach { job ->
            TransferRow(job)
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Paired devices", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    if (devices.isEmpty()) {
        Text("No devices yet. Tap Pair new device to scan a code from your Mac.")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.deviceId }) { device ->
                DeviceCard(device, onConnect = { onConnect(device) }, onForget = { onForget(device) })
            }
        }
    }
}

@Composable
private fun NotificationAccessRow() {
    val context = LocalContext.current
    val enabled = remember {
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
    }
    if (!enabled) {
        OutlinedButton(onClick = {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Enable notification mirroring") }
    }
}

@Composable
private fun ControlAccessRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(OpentomacControlService.isEnabled(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = OpentomacControlService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!enabled) {
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Enable Mac control") }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("$label: $name", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${progress.state.name.lowercase().replaceFirstChar { it.uppercase() }} · $pct%",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!progress.state.let { it == TransferState.DONE || it == TransferState.FAILED || it == TransferState.CANCELLED }) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { scope.launch { AppRuntime.cancelTransfer(job.jobId) } },
                ) { Text("Cancel") }
            }
            if (done && job.direction == TransferDirection.RECEIVE) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { openReceived(context, name) }) { Text("Open") }
            }
        }
    }
}

private fun openReceived(context: android.content.Context, name: String) {
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

@Composable
private fun DeviceCard(device: TrustedDevice, onConnect: () -> Unit, onForget: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
            Text(device.platform, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnect) { Text("Connect") }
                OutlinedButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun PairScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pairing by AppRuntime.pairingState.collectAsStateWithLifecycle()

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            scope.launch { AppRuntime.joinPairing(contents) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val current = pairing) {
            is dev.opentomac.android.runtime.PairingState.Idle -> {
                Text("Scan the QR code shown on your Mac to pair.")
                Button(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan the pairing code on your Mac")
                                .setBeepEnabled(false),
                        )
                    },
                ) { Text("Scan pairing code") }
            }
            is dev.opentomac.android.runtime.PairingState.Working -> Text(current.message + "...")
            is dev.opentomac.android.runtime.PairingState.Verification -> {
                Text("Confirm this code matches your Mac:", style = MaterialTheme.typography.titleMedium)
                Text(current.code, style = MaterialTheme.typography.displaySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { scope.launch { AppRuntime.confirmPairing() } }) { Text("Confirm") }
                    OutlinedButton(onClick = { AppRuntime.rejectPairing() }) { Text("Reject") }
                }
            }
            is dev.opentomac.android.runtime.PairingState.Complete -> {
                Text("Paired with ${current.deviceName}.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { AppRuntime.resetPairing(); onDone() }) { Text("Done") }
            }
            is dev.opentomac.android.runtime.PairingState.Error -> {
                Text("Pairing failed: ${current.message}")
                Button(onClick = { AppRuntime.resetPairing() }) { Text("Try again") }
            }
        }

        OutlinedButton(onClick = { AppRuntime.resetPairing(); onDone() }) { Text("Back") }
    }
}

private fun ConnectionState.describe(): String = when (this) {
    ConnectionState.Unpaired -> "Not paired yet"
    ConnectionState.Idle -> "Ready to connect"
    is ConnectionState.Connecting -> "Connecting (attempt $attempt)"
    is ConnectionState.Connected -> "Connected to ${peer.displayName}"
    is ConnectionState.Degraded -> "Limited: $reason"
}
