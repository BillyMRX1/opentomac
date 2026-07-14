package dev.opentomac.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.service.ConnectionService
import dev.opentomac.shared.pairing.TrustedDevice
import dev.opentomac.shared.session.ConnectionState
import kotlinx.coroutines.launch

/** Single-activity host for the dashboard and pairing screens. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startConnectionService()
        setContent { OpentomacApp() }
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
        val snackbar = remember { SnackbarHostState() }
        var screen by remember { mutableStateOf(Screen.DASHBOARD) }

        val notice by AppRuntime.notice.collectAsStateWithLifecycle()
        LaunchedEffect(notice) {
            notice?.let {
                snackbar.showSnackbar(it)
                AppRuntime.clearNotice()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {}
            LaunchedEffect(Unit) { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
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
) {
    val state by AppRuntime.connectionState.collectAsStateWithLifecycle()
    val devices by AppRuntime.pairedDevices.collectAsStateWithLifecycle()

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
        Button(onClick = onPair) { Text("Pair new device") }
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
