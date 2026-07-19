package dev.opentomac.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = androidx.compose.ui.graphics.Color(0xFFF8FBFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E9FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001C38),
    secondary = androidx.compose.ui.graphics.Color(0xFF535F70),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFF8FBFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E3F7),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF101C2B),
    tertiary = androidx.compose.ui.graphics.Color(0xFF33658A),
    onTertiary = androidx.compose.ui.graphics.Color(0xFFF8FBFF),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFCBE6FF),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF001E2D),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFC3C7CF),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF00315C),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF004A8E),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E8FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFBBC7DB),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF263141),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF3C4758),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFD7E3F7),
    tertiary = androidx.compose.ui.graphics.Color(0xFF93CDF5),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF00344C),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF164C68),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFCBE6FF),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF43474E),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
)

private val OpentomacShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun OpentomacTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = OpentomacTypography,
        shapes = OpentomacShapes,
        content = content,
    )
}
