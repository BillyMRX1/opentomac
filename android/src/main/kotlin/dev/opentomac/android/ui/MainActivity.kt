package dev.opentomac.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.service.ConnectionService
import dev.opentomac.android.ui.theme.OpentomacTheme
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

private enum class Tab(val label: String, val glyph: Glyph) {
    HOME("Home", Glyph.Home),
    TRANSFERS("Transfers", Glyph.Transfer),
    SETTINGS("Settings", Glyph.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpentomacApp() {
    OpentomacTheme {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        var screen by remember { mutableStateOf(Screen.DASHBOARD) }
        var tab by remember { mutableStateOf(Tab.HOME) }
        val homeListState = rememberLazyListState()
        val transfersListState = rememberLazyListState()
        val mirrorConsentRequested by AppRuntime.mirrorConsentRequested.collectAsStateWithLifecycle()

        BackHandler(enabled = screen == Screen.PAIR || tab != Tab.HOME) {
            if (screen == Screen.PAIR) {
                AppRuntime.resetPairing()
                screen = Screen.DASHBOARD
            } else {
                tab = Tab.HOME
            }
        }

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
            bottomBar = {
                if (screen == Screen.DASHBOARD) {
                    NavigationBar {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { GlyphIcon(glyph = entry.glyph, contentDescription = null) },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            when (screen) {
                Screen.DASHBOARD -> when (tab) {
                    Tab.HOME -> HomeTab(
                        modifier = Modifier.padding(padding),
                        listState = homeListState,
                        onPair = { screen = Screen.PAIR },
                        onSendClipboard = { scope.launch { AppRuntime.sendClipboard() } },
                        onConnect = { device -> scope.launch { AppRuntime.connect(device) } },
                        onForget = { device -> scope.launch { AppRuntime.forget(device) } },
                        onSendFiles = { uris -> scope.launch { AppRuntime.enqueueSharedUris(context, uris) } },
                        onMirror = requestMirrorConsent,
                        onStopMirroring = AppRuntime::stopMirroring,
                    )
                    Tab.TRANSFERS -> TransfersTab(
                        modifier = Modifier.padding(padding),
                        listState = transfersListState,
                    )
                    Tab.SETTINGS -> SettingsTab(
                        modifier = Modifier.padding(padding),
                        onAutoSendScreenshotsChanged = { enabled ->
                            scope.launch { AppRuntime.setAutoSendScreenshots(enabled) }
                        },
                    )
                }
                Screen.PAIR -> PairScreen(
                    modifier = Modifier.padding(padding),
                    onDone = { screen = Screen.DASHBOARD },
                )
            }
        }
    }
}
