package dev.opentomac.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import dev.opentomac.android.runtime.AppRuntime
import dev.opentomac.android.runtime.PairingState
import kotlinx.coroutines.launch

/** Full-screen pairing flow: camera QR scan, verification code, and outcome states. */
@Composable
internal fun PairScreen(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val pairing by AppRuntime.pairingState.collectAsStateWithLifecycle()
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var cameraPermissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        cameraPermissionPermanentlyDenied = !granted && activity?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } == true
    }

    LaunchedEffect(pairing, cameraPermissionGranted) {
        if (pairing is PairingState.Idle && !cameraPermissionGranted && !cameraPermissionRequested) {
            cameraPermissionRequested = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                cameraPermissionGranted = granted
                if (granted) cameraPermissionPermanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp),
    ) {
        item {
            PairTopBar(onBack = { AppRuntime.resetPairing(); onDone() })
            when (val current = pairing) {
                is PairingState.Idle -> {
                    if (cameraPermissionGranted) {
                        LivePairingScanner(
                            onBarcode = { contents ->
                                scope.launch { AppRuntime.joinPairing(contents) }
                            },
                        )
                    } else {
                        CameraPermissionCard(
                            onGrantCameraAccess = {
                                if (cameraPermissionPermanentlyDenied) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ),
                                    )
                                } else {
                                    cameraPermissionRequested = true
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                        )
                    }
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
private fun LivePairingScanner(onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val barcodeView = remember(context) {
        BarcodeView(context).apply {
            setUseTextureView(true)
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        }
    }
    val frameColor = MaterialTheme.colorScheme.primaryContainer
    val frameShadow = MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f)

    DisposableEffect(barcodeView, lifecycleOwner) {
        var handled = false
        barcodeView.decodeContinuous(
            BarcodeCallback { result ->
                val contents = result.text ?: return@BarcodeCallback
                if (!handled) {
                    handled = true
                    barcodeView.pause()
                    currentOnBarcode(contents)
                }
            },
        )

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!handled) barcodeView.resume()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> barcodeView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            barcodeView.resume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            barcodeView.stopDecoding()
            barcodeView.pause()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Live camera preview for scanning the pairing code" },
        shape = ConnectionShape,
        color = MaterialTheme.colorScheme.scrim,
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(ConnectionShape)) {
            AndroidView(
                factory = { barcodeView },
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(modifier = Modifier.fillMaxSize().padding(28.dp)) {
                val bracketLength = 34.dp.toPx()
                val shadowWidth = 6.dp.toPx()
                val frameWidth = 3.dp.toPx()
                val maxX = size.width
                val maxY = size.height
                val segments = listOf(
                    Pair(Offset(0f, bracketLength), Offset(0f, 0f)),
                    Pair(Offset(0f, 0f), Offset(bracketLength, 0f)),
                    Pair(Offset(maxX - bracketLength, 0f), Offset(maxX, 0f)),
                    Pair(Offset(maxX, 0f), Offset(maxX, bracketLength)),
                    Pair(Offset(0f, maxY - bracketLength), Offset(0f, maxY)),
                    Pair(Offset(0f, maxY), Offset(bracketLength, maxY)),
                    Pair(Offset(maxX - bracketLength, maxY), Offset(maxX, maxY)),
                    Pair(Offset(maxX, maxY - bracketLength), Offset(maxX, maxY)),
                )
                segments.forEach { (start, end) ->
                    drawLine(frameShadow, start, end, shadowWidth, StrokeCap.Round)
                    drawLine(frameColor, start, end, frameWidth, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionCard(onGrantCameraAccess: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 260.dp),
        shape = ConnectionShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                "Camera access is needed to scan the pairing code",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(
                onClick = onGrantCameraAccess,
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Grant camera access")
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
