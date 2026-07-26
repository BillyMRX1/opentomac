package dev.opentomac.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opentomac.shared.session.ConnectionState

/** Shapes, section chrome, glyphs, and other components shared across the tabs and pairing flow. */
internal val ConnectionShape = RoundedCornerShape(34.dp)
internal val PrimaryActionShape = RoundedCornerShape(
    topStart = 34.dp,
    topEnd = 16.dp,
    bottomEnd = 34.dp,
    bottomStart = 34.dp,
)
internal val ClipboardActionShape = RoundedCornerShape(
    topStart = 25.dp,
    topEnd = 25.dp,
    bottomEnd = 9.dp,
    bottomStart = 25.dp,
)
internal val PairActionShape = RoundedCornerShape(
    topStart = 25.dp,
    topEnd = 9.dp,
    bottomEnd = 25.dp,
    bottomStart = 25.dp,
)
internal val WideActionShape = RoundedCornerShape(
    topStart = 25.dp,
    topEnd = 25.dp,
    bottomEnd = 25.dp,
    bottomStart = 10.dp,
)
internal val PermissionShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 24.dp,
    bottomEnd = 24.dp,
    bottomStart = 10.dp,
)
internal val ReversePermissionShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 10.dp,
    bottomEnd = 24.dp,
    bottomStart = 24.dp,
)

@Composable
internal fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 26.dp, bottom = 10.dp),
        style = MaterialTheme.typography.headlineSmall,
    )
}

@Composable
internal fun PermissionCard(
    glyph: Glyph,
    title: String,
    body: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    hint: String? = null,
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
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
internal fun EmptyStateCard(
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
internal fun TonalGlyph(glyph: Glyph, size: Dp = 44.dp, iconSize: Dp = 24.dp) {
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

internal enum class Glyph {
    Upload,
    Clipboard,
    Pair,
    Mirror,
    Notification,
    Control,
    Transfer,
    Device,
    Back,
    Check,
    Error,
    Home,
    Settings,
}

@Composable
internal fun GlyphIcon(
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
        fun point(x: Float, y: Float) = Offset(w * x, w * y)
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
                    size = Size(w * .54f, w * .68f),
                    cornerRadius = CornerRadius(w * .08f),
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
                    size = Size(w * .44f, w * .84f),
                    cornerRadius = CornerRadius(w * .12f),
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
                    size = Size(w * .76f, w * .52f),
                    cornerRadius = CornerRadius(w * .07f),
                    style = style,
                )
                drawLine(tint, point(.5f, .70f), point(.5f, .84f), stroke, StrokeCap.Round)
                drawLine(tint, point(.31f, .86f), point(.69f, .86f), stroke, StrokeCap.Round)
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
            Glyph.Home -> {
                val roof = Path().apply {
                    moveTo(w * .18f, w * .52f)
                    lineTo(w * .5f, w * .22f)
                    lineTo(w * .82f, w * .52f)
                }
                drawPath(roof, tint, style = style)
                drawLine(tint, point(.28f, .48f), point(.28f, .82f), stroke, StrokeCap.Round)
                drawLine(tint, point(.28f, .82f), point(.72f, .82f), stroke, StrokeCap.Round)
                drawLine(tint, point(.72f, .82f), point(.72f, .48f), stroke, StrokeCap.Round)
                drawLine(tint, point(.5f, .82f), point(.5f, .62f), stroke, StrokeCap.Round)
            }
            Glyph.Settings -> {
                drawLine(tint, point(.18f, .30f), point(.82f, .30f), stroke, StrokeCap.Round)
                drawCircle(tint, radius = w * .07f, center = point(.62f, .30f))
                drawLine(tint, point(.18f, .55f), point(.82f, .55f), stroke, StrokeCap.Round)
                drawCircle(tint, radius = w * .07f, center = point(.35f, .55f))
                drawLine(tint, point(.18f, .80f), point(.82f, .80f), stroke, StrokeCap.Round)
                drawCircle(tint, radius = w * .07f, center = point(.58f, .80f))
            }
        }
    }
}

internal fun ConnectionState.describe(): String = when (this) {
    ConnectionState.Unpaired -> "Not paired yet"
    ConnectionState.Idle -> "Ready to connect"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected to ${peer.displayName}"
    is ConnectionState.Degraded -> "Limited: $reason"
}
