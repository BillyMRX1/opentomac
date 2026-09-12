package dev.opentomac.android.ui

import android.net.Uri
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opentomac.android.service.OpentomacControlService
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.ui.theme.Success
import dev.opentomac.shared.pairing.TrustedDevice
import dev.opentomac.shared.session.ConnectionState

/** Home tab: connection status, quick actions, access setup, and paired devices. */
@Composable
internal fun HomeTab(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    onPair: () -> Unit,
    onSendClipboard: () -> Unit,
    onConnect: (TrustedDevice) -> Unit,
    onForget: (TrustedDevice) -> Unit,
    onSendFiles: (List<Uri>) -> Unit,
    onMirror: () -> Unit,
    onStopMirroring: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by AppRuntime.connectionState.collectAsStateWithLifecycle()
    val wifiAvailable by AppRuntime.wifiAvailable.collectAsStateWithLifecycle()
    val devices by AppRuntime.pairedDevices.collectAsStateWithLifecycle()
    val mirroring by AppRuntime.mirroring.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var controlAccess by remember { mutableStateOf(OpentomacControlService.isEnabled(context)) }
    val hasRequiredSetupAccess = notificationAccess && controlAccess

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = hasNotificationAccess(context)
                controlAccess = OpentomacControlService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onSendFiles(uris) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp),
    ) {
        item {
            DashboardTopBar()
            ConnectionCard(state = state, wifiAvailable = wifiAvailable, onPair = onPair)
            Spacer(Modifier.height(14.dp))
            QuickActions(
                onSendFile = { filePicker.launch(arrayOf("*/*")) },
                onSendClipboard = onSendClipboard,
                onPair = onPair,
                onMirror = if (mirroring) onStopMirroring else onMirror,
                mirrorLabel = if (mirroring) "Stop mirroring" else "Mirror to Mac",
                mirrorEnabled = mirroring || state is ConnectionState.Connected,
            )
            if (!hasRequiredSetupAccess) {
                SectionTitle("Set up access")
                PermissionCard(
                    glyph = Glyph.Settings,
                    title = "Finish setup",
                    body = "Enable notification access and phone control in Settings.",
                    shape = PermissionShape,
                    onClick = onOpenSettings,
                )
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
private fun ConnectionCard(state: ConnectionState, wifiAvailable: Boolean, onPair: () -> Unit) {
    val connected = state is ConnectionState.Connected
    val waitingForWifi = !wifiAvailable && state !is ConnectionState.Connected && state !is ConnectionState.Unpaired
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
                Text(
                    if (waitingForWifi) "Waiting for Wi-Fi" else state.describe(),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = when {
                        waitingForWifi ->
                            "Connect to Wi-Fi to reach your trusted Mac on the local network."
                        state is ConnectionState.Connected ->
                            "Direct on this Wi-Fi. Nothing is routed through the cloud."
                        state is ConnectionState.Connecting ->
                            "Looking for your trusted Mac on this local network."
                        state is ConnectionState.Degraded ->
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
        drawCircle(
            color = ringColor,
            style = Stroke(width = 18.dp.toPx()),
        )
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

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

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
