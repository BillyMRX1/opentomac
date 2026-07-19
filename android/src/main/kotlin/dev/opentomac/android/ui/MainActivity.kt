package dev.opentomac.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.runtime.PairingState
import dev.opentomac.android.service.ConnectionService
import dev.opentomac.android.service.OpentomacControlService
import dev.opentomac.android.ui.theme.OpentomacTheme
import dev.opentomac.android.ui.theme.Success
import dev.opentomac.shared.pairing.TrustedDevice
import dev.opentomac.shared.session.ConnectionState
import dev.opentomac.shared.transfer.TransferDirection
import dev.opentomac.shared.transfer.TransferJob
import dev.opentomac.shared.transfer.TransferState
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
    OpentomacTheme {
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
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.SEND_SMS)
                add(Manifest.permission.READ_CALL_LOG)
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
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when (screen) {
                Screen.DASHBOARD -> DashboardScreen(
                    modifier = Modifier.padding(padding),
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
                Screen.PAIR -> PairScreen(
                    modifier = Modifier.padding(padding),
                    onDone = { screen = Screen.DASHBOARD },
                )
            }
        }
    }
}

private val ConnectionShape = RoundedCornerShape(34.dp)
private val PrimaryActionShape = RoundedCornerShape(
    topStart = 34.dp,
    topEnd = 16.dp,
    bottomEnd = 34.dp,
    bottomStart = 34.dp,
)
private val ClipboardActionShape = RoundedCornerShape(
    topStart = 25.dp,
    topEnd = 25.dp,
    bottomEnd = 9.dp,
    bottomStart = 25.dp,
)
private val PairActionShape = RoundedCornerShape(
    topStart = 25.dp,
    topEnd = 9.dp,
    bottomEnd = 25.dp,
    bottomStart = 25.dp,
)
private val WideActionShape = RoundedCornerShape(
    topStart = 25.dp,
    topEnd = 25.dp,
    bottomEnd = 25.dp,
    bottomStart = 10.dp,
)
private val PermissionShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 24.dp,
    bottomEnd = 24.dp,
    bottomStart = 10.dp,
)
private val ReversePermissionShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 10.dp,
    bottomEnd = 24.dp,
    bottomStart = 24.dp,
)

@Composable
private fun DashboardScreen(
    modifier: Modifier = Modifier,
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp),
    ) {
        item {
            DashboardTopBar()
            ConnectionCard(state = state, onPair = onPair)
            Spacer(Modifier.height(14.dp))
            QuickActions(
                onSendFile = { filePicker.launch(arrayOf("*/*")) },
                onSendClipboard = onSendClipboard,
                onPair = onPair,
                onMirror = if (mirroring) onStopMirroring else onMirror,
                mirrorLabel = if (mirroring) "Stop mirroring" else "Mirror to Mac",
                mirrorEnabled = mirroring || state is ConnectionState.Connected,
            )
            SectionTitle("Set up access")
            NotificationAccessRow()
            ControlAccessRow()
            ScreenshotSwitchRow(
                checked = autoSendScreenshots,
                enabled = ready,
                onCheckedChange = onAutoSendScreenshotsChanged,
            )
            SectionTitle("Transfers")
            if (transfers.isEmpty()) {
                EmptyStateCard(
                    glyph = Glyph.Transfer,
                    title = "No transfers yet",
                    body = "Sent and received files appear here.",
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    transfers.sortedByDescending { it.jobId }.take(8).forEach { job ->
                        TransferRow(job)
                    }
                }
            }
            SectionTitle("Paired devices")
            if (devices.isEmpty()) {
                EmptyStateCard(
                    glyph = Glyph.Device,
                    title = "No trusted Macs",
                    body = "Scan the code shown by opentomac on a Mac.",
                    actionLabel = "Scan code",
                    onAction = onPair,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        DeviceCard(
                            device = device,
                            onConnect = { onConnect(device) },
                            onForget = { onForget(device) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTopBar() {
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("opentomac", style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun ConnectionCard(state: ConnectionState, onPair: () -> Unit) {
    val connected = state is ConnectionState.Connected
    Surface(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 198.dp),
        shape = ConnectionShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box {
            DecorativeConnectionRing()
            Column(
                modifier = Modifier.padding(24.dp).widthIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "LOCAL CONNECTION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.66f),
                )
                Text(state.describe(), style = MaterialTheme.typography.displaySmall)
                Text(
                    text = when (state) {
                        is ConnectionState.Connected ->
                            "Direct on this Wi-Fi. Nothing is routed through the cloud."
                        is ConnectionState.Connecting ->
                            "Looking for your trusted Mac on this local network."
                        is ConnectionState.Degraded ->
                            "The local link is active, but some features may be unavailable."
                        else ->
                            "Pair this phone with your Mac, or reconnect to a trusted Mac nearby."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                if (connected) {
                    ConnectedPill()
                } else if (state is ConnectionState.Unpaired || state is ConnectionState.Idle) {
                    FilledTonalButton(
                        onClick = onPair,
                        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 7.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Pair with a Mac")
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.DecorativeConnectionRing() {
    val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Canvas(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(132.dp)
            .graphicsLayer { translationX = 42.dp.toPx(); translationY = 38.dp.toPx() },
    ) {
        drawCircle(color = ringColor, style = Stroke(width = 18.dp.toPx()))
    }
}

@Composable
private fun ConnectedPill() {
    Surface(
        modifier = Modifier.padding(top = 4.dp),
        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 7.dp),
        color = Success.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = Success) {}
            Text("Encrypted · active", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun QuickActions(
    onSendFile: () -> Unit,
    onSendClipboard: () -> Unit,
    onPair: () -> Unit,
    onMirror: () -> Unit,
    mirrorLabel: String,
    mirrorEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(174.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExpressiveAction(
                label = "Send file",
                glyph = Glyph.Upload,
                onClick = onSendFile,
                modifier = Modifier.weight(1.12f).fillMaxHeight(),
                shape = PrimaryActionShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            Column(
                modifier = Modifier.weight(0.88f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExpressiveAction(
                    label = "Send clipboard",
                    glyph = Glyph.Clipboard,
                    onClick = onSendClipboard,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = ClipboardActionShape,
                )
                ExpressiveAction(
                    label = "Pair",
                    glyph = Glyph.Pair,
                    onClick = onPair,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = PairActionShape,
                )
            }
        }
        ExpressiveAction(
            label = mirrorLabel,
            glyph = Glyph.Mirror,
            onClick = onMirror,
            enabled = mirrorEnabled,
            modifier = Modifier.fillMaxWidth().height(70.dp),
            shape = WideActionShape,
            horizontal = true,
        )
    }
}

@Composable
private fun ExpressiveAction(
    label: String,
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: RoundedCornerShape,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    horizontal: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "action press",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource,
    ) {
        if (horizontal) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                GlyphIcon(glyph = glyph, contentDescription = null)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start,
            ) {
                GlyphIcon(glyph = glyph, contentDescription = null)
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 26.dp, bottom = 10.dp),
        style = MaterialTheme.typography.headlineSmall,
    )
}

@Composable
private fun NotificationAccessRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember {
        mutableStateOf(
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName),
        )
    }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(context)
                    .contains(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!enabled) {
        PermissionCard(
            glyph = Glyph.Notification,
            title = "Show phone notifications on Mac",
            body = "Choose which apps can appear on your Mac.",
            shape = PermissionShape,
            onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
        Spacer(Modifier.height(10.dp))
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
        PermissionCard(
            glyph = Glyph.Control,
            title = "Control the phone from Mac",
            body = "Allow taps and typing during a mirror session.",
            shape = ReversePermissionShape,
            onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}

@Composable
private fun PermissionCard(
    glyph: Glyph,
    title: String,
    body: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TonalGlyph(glyph)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onClick,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("Set up")
            }
        }
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
            TonalGlyph(Glyph.Device)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    device.platform,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                FilledTonalButton(
                    onClick = onConnect,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = onForget,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    glyph: Glyph,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TonalGlyph(glyph)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun PairScreen(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pairing by AppRuntime.pairingState.collectAsStateWithLifecycle()

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            scope.launch { AppRuntime.joinPairing(contents) }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp),
    ) {
        item {
            PairTopBar(onBack = { AppRuntime.resetPairing(); onDone() })
            when (val current = pairing) {
                is PairingState.Idle -> {
                    PairingCodeGraphic()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Scan the pairing code shown on your Mac",
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "The Mac and phone must be on the same local network.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                scanner.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Scan the pairing code on your Mac")
                                        .setBeepEnabled(false),
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 8.dp),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
                        ) { Text("Scan pairing code") }
                    }
                }
                is PairingState.Working -> PairStatePanel {
                    CircularProgressIndicator(modifier = Modifier.size(58.dp))
                    Text(current.message + "...", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Keep opentomac open on both devices while the secure local link is prepared.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                is PairingState.Verification -> PairStatePanel {
                    TonalGlyph(Glyph.Pair, size = 64.dp, iconSize = 30.dp)
                    Text("Confirm this code", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Make sure these digits match the code on your Mac.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        current.code,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 4.sp,
                        ),
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { AppRuntime.rejectPairing() },
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Reject") }
                        Button(
                            onClick = { scope.launch { AppRuntime.confirmPairing() } },
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Confirm") }
                    }
                }
                is PairingState.Complete -> PairStatePanel {
                    SuccessOrb()
                    Text("Mac paired", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${current.deviceName} can reconnect on this local network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = { AppRuntime.resetPairing(); onDone() },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Done") }
                }
                is PairingState.Error -> PairStatePanel {
                    TonalGlyph(Glyph.Error, size = 64.dp, iconSize = 30.dp)
                    Text("Pairing failed", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = { AppRuntime.resetPairing() },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun PairTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Back, "Back")
            }
        }
        Text("Pair with Mac", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PairingCodeGraphic() {
    val flourish = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    Surface(
        modifier = Modifier.fillMaxWidth().height(210.dp),
        shape = ConnectionShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = flourish,
                    radius = 86.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.92f),
                    style = Stroke(width = 16.dp.toPx()),
                )
                drawCircle(
                    color = flourish,
                    radius = 60.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.90f, size.height * 0.08f),
                    style = Stroke(width = 12.dp.toPx()),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = RoundedCornerShape(30.dp, 18.dp, 30.dp, 30.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        GlyphIcon(
                            glyph = Glyph.PairingCode,
                            contentDescription = "Pairing code illustration",
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
                Text(
                    "SECURE LOCAL PAIRING",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
                )
            }
        }
    }
}

@Composable
private fun PairStatePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 520.dp),
        shape = ConnectionShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            content = content,
        )
    }
}

@Composable
private fun SuccessOrb() {
    Surface(
        modifier = Modifier.size(88.dp),
        shape = RoundedCornerShape(32.dp, 18.dp, 32.dp, 32.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Check, "Pairing complete", modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun TonalGlyph(glyph: Glyph, size: androidx.compose.ui.unit.Dp = 44.dp, iconSize: androidx.compose.ui.unit.Dp = 24.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(size * 0.36f),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            GlyphIcon(glyph = glyph, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

private enum class Glyph {
    Upload,
    Clipboard,
    Pair,
    Mirror,
    Notification,
    Control,
    Transfer,
    Device,
    PairingCode,
    Back,
    Check,
    Error,
}

@Composable
private fun GlyphIcon(
    glyph: Glyph,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(24.dp),
) {
    val resolvedColor = LocalContentColor.current
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.then(semanticsModifier)) {
        val tint = resolvedColor
        val w = size.minDimension
        val stroke = w * 0.075f
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(w * x, w * y)
        when (glyph) {
            Glyph.Upload -> {
                drawLine(tint, point(.5f, .68f), point(.5f, .16f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .16f), point(.30f, .36f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .16f), point(.70f, .36f), stroke, StrokeCap.Round)
                drawLine(tint, point(.18f, .62f), point(.18f, .83f), stroke, StrokeCap.Round)
                drawLine(tint, point(.18f, .83f), point(.82f, .83f), stroke, StrokeCap.Round)
                drawLine(tint, point(.82f, .83f), point(.82f, .62f), stroke, StrokeCap.Round)
            }
            Glyph.Clipboard -> {
                drawRoundRect(
                    tint,
                    topLeft = point(.23f, .19f),
                    size = androidx.compose.ui.geometry.Size(w * .54f, w * .68f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f),
                    style = style,
                )
                drawLine(tint, point(.37f, .18f), point(.37f, .10f), stroke, StrokeCap.Round)
                drawLine(tint, point(.37f, .10f), point(.63f, .10f), stroke, StrokeCap.Round)
                drawLine(tint, point(.63f, .10f), point(.63f, .18f), stroke, StrokeCap.Round)
            }
            Glyph.Pair -> {
                drawLine(tint, point(.5f, .12f), point(.5f, .88f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .12f), point(.30f, .32f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .12f), point(.70f, .32f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .88f), point(.30f, .68f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .88f), point(.70f, .68f), stroke, StrokeCap.Round)
            }
            Glyph.Mirror -> {
                drawRoundRect(
                    tint,
                    topLeft = point(.28f, .08f),
                    size = androidx.compose.ui.geometry.Size(w * .44f, w * .84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f),
                    style = style,
                )
                drawLine(tint, point(.43f, .79f), point(.57f, .79f), stroke, StrokeCap.Round)
            }
            Glyph.Notification -> {
                val path = Path().apply {
                    moveTo(w * .27f, w * .68f)
                    lineTo(w * .33f, w * .58f)
                    lineTo(w * .33f, w * .39f)
                    cubicTo(w * .33f, w * .20f, w * .67f, w * .20f, w * .67f, w * .39f)
                    lineTo(w * .67f, w * .58f)
                    lineTo(w * .73f, w * .68f)
                    close()
                }
                drawPath(path, tint, style = style)
                drawLine(tint, point(.44f, .78f), point(.56f, .78f), stroke, StrokeCap.Round)
            }
            Glyph.Control -> {
                drawCircle(tint, radius = w * .30f, style = style)
                drawCircle(tint, radius = w * .08f)
                drawLine(tint, point(.5f, .08f), point(.5f, .25f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .75f), point(.5f, .92f), stroke, StrokeCap.Round)
                drawLine(tint, point(.08f, .5f), point(.25f, .5f), stroke, StrokeCap.Round)
                drawLine(tint, point(.75f, .5f), point(.92f, .5f), stroke, StrokeCap.Round)
            }
            Glyph.Transfer -> {
                drawLine(tint, point(.5f, .14f), point(.5f, .76f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .76f), point(.29f, .55f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .76f), point(.71f, .55f), stroke, StrokeCap.Round)
                drawLine(tint, point(.22f, .87f), point(.78f, .87f), stroke, StrokeCap.Round)
            }
            Glyph.Device -> {
                drawRoundRect(
                    tint,
                    topLeft = point(.12f, .18f),
                    size = androidx.compose.ui.geometry.Size(w * .76f, w * .52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .07f),
                    style = style,
                )
                drawLine(tint, point(.5f, .70f), point(.5f, .84f), stroke, StrokeCap.Round)
                drawLine(tint, point(.31f, .86f), point(.69f, .86f), stroke, StrokeCap.Round)
            }
            Glyph.PairingCode -> {
                fun finder(x: Float, y: Float) {
                    drawRoundRect(
                        color = tint,
                        topLeft = point(x, y),
                        size = androidx.compose.ui.geometry.Size(w * .28f, w * .28f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .035f),
                        style = style,
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = point(x + .09f, y + .09f),
                        size = androidx.compose.ui.geometry.Size(w * .10f, w * .10f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .02f),
                    )
                }
                finder(.10f, .10f)
                finder(.62f, .10f)
                finder(.10f, .62f)
                drawRoundRect(
                    color = tint,
                    topLeft = point(.56f, .56f),
                    size = androidx.compose.ui.geometry.Size(w * .12f, w * .12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .025f),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = point(.74f, .56f),
                    size = androidx.compose.ui.geometry.Size(w * .16f, w * .10f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .025f),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = point(.56f, .74f),
                    size = androidx.compose.ui.geometry.Size(w * .10f, w * .16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .025f),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = point(.74f, .74f),
                    size = androidx.compose.ui.geometry.Size(w * .16f, w * .16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .025f),
                )
            }
            Glyph.Back -> {
                drawLine(tint, point(.78f, .5f), point(.22f, .5f), stroke, StrokeCap.Round)
                drawLine(tint, point(.22f, .5f), point(.45f, .27f), stroke, StrokeCap.Round)
                drawLine(tint, point(.22f, .5f), point(.45f, .73f), stroke, StrokeCap.Round)
            }
            Glyph.Check -> {
                drawLine(tint, point(.20f, .52f), point(.42f, .73f), stroke, StrokeCap.Round)
                drawLine(tint, point(.42f, .73f), point(.82f, .28f), stroke, StrokeCap.Round)
            }
            Glyph.Error -> {
                drawCircle(tint, radius = w * .38f, style = style)
                drawLine(tint, point(.5f, .28f), point(.5f, .57f), stroke, StrokeCap.Round)
                drawCircle(tint, radius = w * .045f, center = point(.5f, .72f))
            }
        }
    }
}

private fun ConnectionState.describe(): String = when (this) {
    ConnectionState.Unpaired -> "Not paired yet"
    ConnectionState.Idle -> "Ready to connect"
    is ConnectionState.Connecting -> "Connecting (attempt $attempt)"
    is ConnectionState.Connected -> "Connected to ${peer.displayName}"
    is ConnectionState.Degraded -> "Limited: $reason"
}
