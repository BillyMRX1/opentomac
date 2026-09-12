package dev.opentomac.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opentomac.android.BuildConfig
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.service.OpentomacControlService

/** Settings tab: permissions, screenshot auto-send preference, and the app version footer. */
@Composable
internal fun SettingsTab(
    modifier: Modifier = Modifier,
    onAutoSendScreenshotsChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissions by remember(context) { mutableStateOf(readPermissionStates(context)) }
    var restrictedAccess by remember { mutableStateOf<RestrictedAccess?>(null) }
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions = readPermissionStates(context) }
    val autoSendScreenshots by AppRuntime.autoSendScreenshots.collectAsStateWithLifecycle()
    val ready by AppRuntime.ready.collectAsStateWithLifecycle()

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = readPermissionStates(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openAccess(access: RestrictedAccess) {
        if (isRestrictedInstall(context)) {
            restrictedAccess = access
        } else {
            openAccessSettings(context, access)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("Settings")
        SectionTitle("Permissions")
        PermissionRow(
            glyph = Glyph.Notification,
            title = "Notification access",
            body = "Show phone notifications on Mac.",
            granted = permissions.notificationAccess,
            onClick = { openAccess(RestrictedAccess.NOTIFICATIONS) },
        )
        PermissionRow(
            glyph = Glyph.Control,
            title = "Phone control",
            body = "Allow taps and typing during mirror sessions.",
            granted = permissions.phoneControl,
            onClick = { openAccess(RestrictedAccess.CONTROL) },
        )
        PermissionRow(
            glyph = Glyph.Device,
            title = "Contacts",
            body = "Find contacts when starting a conversation.",
            granted = permissions.contacts,
            onClick = { requestPermissions.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
        )
        PermissionRow(
            glyph = Glyph.Clipboard,
            title = "SMS",
            body = "Send and receive text messages from Mac.",
            granted = permissions.sms,
            onClick = {
                requestPermissions.launch(
                    arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS),
                )
            },
        )
        PermissionRow(
            glyph = Glyph.Transfer,
            title = "Call history",
            body = "Show recent calls on Mac.",
            granted = permissions.callHistory,
            onClick = { requestPermissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG)) },
        )
        PermissionRow(
            glyph = Glyph.Upload,
            title = "Photos and videos",
            body = "Send photos and videos to your Mac.",
            granted = permissions.photosAndVideos,
            onClick = {
                requestPermissions.launch(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                    } else {
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    },
                )
            },
        )
        PermissionRow(
            glyph = Glyph.Pair,
            title = "Camera",
            body = "Scan a pairing code.",
            granted = permissions.camera,
            onClick = { requestPermissions.launch(arrayOf(Manifest.permission.CAMERA)) },
        )
        PermissionRow(
            glyph = Glyph.Notification,
            title = "App notifications",
            body = "Show connection and transfer updates.",
            granted = permissions.appNotifications,
            onClick = { requestPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
        )
        ScreenshotSwitchRow(
            checked = autoSendScreenshots,
            enabled = ready,
            onCheckedChange = onAutoSendScreenshotsChanged,
        )
        VersionFooter()
    }

    restrictedAccess?.let { access ->
        RestrictedSettingsDialog(
            access = access,
            onDismiss = { restrictedAccess = null },
            onOpenAppInfo = {
                restrictedAccess = null
                context.startActivity(appInfoIntent(context))
            },
            onOpenAccessSettings = {
                restrictedAccess = null
                openAccessSettings(context, access)
            },
        )
    }
}

@Composable
private fun PermissionRow(
    glyph: Glyph,
    title: String,
    body: String,
    granted: Boolean,
    onClick: () -> Unit,
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
            TonalGlyph(glyph)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (granted) "Allowed" else "Needed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onClick,
                enabled = !granted,
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(if (granted) "Allowed" else "Set up")
            }
        }
    }
}

@Composable
private fun RestrictedSettingsDialog(
    access: RestrictedAccess,
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAccessSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow restricted settings") },
        text = {
            Text(
                "Android blocks this access for downloaded-file installs. Open App info, " +
                    "tap the three-dot menu, choose Allow restricted settings, return to " +
                    "opentomac, then enable the requested access (${access.label}).",
            )
        },
        confirmButton = { TextButton(onClick = onOpenAppInfo) { Text("Open App info") } },
        dismissButton = {
            TextButton(onClick = onOpenAccessSettings) { Text("Open access settings") }
        },
    )
}

private enum class RestrictedAccess(val label: String, val action: String) {
    NOTIFICATIONS("notification access", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    CONTROL("phone control", Settings.ACTION_ACCESSIBILITY_SETTINGS),
}

private data class PermissionStates(
    val notificationAccess: Boolean,
    val phoneControl: Boolean,
    val contacts: Boolean,
    val sms: Boolean,
    val callHistory: Boolean,
    val photosAndVideos: Boolean,
    val camera: Boolean,
    val appNotifications: Boolean,
)

private fun readPermissionStates(context: Context): PermissionStates {
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val mediaGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return PermissionStates(
        notificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName),
        phoneControl = OpentomacControlService.isEnabled(context),
        contacts = granted(Manifest.permission.READ_CONTACTS),
        sms = granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS),
        callHistory = granted(Manifest.permission.READ_CALL_LOG),
        photosAndVideos = mediaGranted,
        camera = granted(Manifest.permission.CAMERA),
        appNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS),
    )
}

internal fun isRestrictedInstall(sdkInt: Int, packageSource: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE

private fun isRestrictedInstall(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val packageSource = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).packageSource
    }.getOrNull() ?: return false
    return isRestrictedInstall(Build.VERSION.SDK_INT, packageSource)
}

private fun openAccessSettings(context: Context, access: RestrictedAccess) {
    context.startActivity(Intent(access.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun appInfoIntent(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:${context.packageName}"),
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
