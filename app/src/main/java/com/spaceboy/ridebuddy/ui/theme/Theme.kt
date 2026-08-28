package com.spaceboy.ridebuddy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spaceboy.ridebuddy.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCE1A6),
    onTertiaryContainer = Color(0xFF251A00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231A19),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231A19),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857371),
    outlineVariant = Color(0xFFD8C2BF),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF392E2D),
    inverseOnSurface = Color(0xFFFCEEEB),
    inversePrimary = Color(0xFFFFB4AB),
    surfaceDim = Color(0xFFE8D6D4),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E1),
    surfaceContainerHighest = Color(0xFFF0DEDC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFDEC48C),
    onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF564419),
    onTertiaryContainer = Color(0xFFFCE1A6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF0DEDC),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF0DEDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BF),
    outline = Color(0xFFA08C8A),
    outlineVariant = Color(0xFF534341),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF0DEDC),
    inverseOnSurface = Color(0xFF392E2D),
    inversePrimary = Color(0xFFB3261E),
    surfaceDim = Color(0xFF1A1110),
    surfaceBright = Color(0xFF423735),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231A19),
    surfaceContainer = Color(0xFF271E1D),
    surfaceContainerHigh = Color(0xFF322827),
    surfaceContainerHighest = Color(0xFF3D3331),
)

/**
 * High contrast raises contrast; it must not change the brand.
 *
 * These are derived from the standard schemes rather than built from
 * `lightColorScheme()` / `darkColorScheme()`, because every role left unnamed by a fresh scheme
 * falls back to Material's baseline purple. Building them that way turned on an accessibility
 * setting and recoloured secondary, tertiary, error and half the surfaces to a different palette.
 */
private val HighContrastLightColors = LightColors.copy(
    primary = Color(0xFF700007),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0E4E2),
    onSurfaceVariant = Color(0xFF211A19),
    outline = Color(0xFF3A2D2B),
    outlineVariant = Color(0xFF5A4B49),
    surfaceContainer = Color(0xFFF5E8E6),
    surfaceContainerHigh = Color(0xFFEBE0DE),
)

private val HighContrastDarkColors = DarkColors.copy(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF292222),
    onSurfaceVariant = Color.White,
    outline = Color(0xFFC0A6A3),
    outlineVariant = Color(0xFF907875),
    surfaceContainer = Color(0xFF1F1817),
    surfaceContainerHigh = Color(0xFF2A2221),
)

/**
 * Semantic status colors that are not part of the standard M3 colorScheme.
 *
 * Use `MaterialTheme.statusColors.connected / inProgress / error` from screens to
 * avoid hardcoded `Color(0xFF...)` values that bypass the theme tokens.
 * The palette is dynamic-color-friendly (deeper greens / oranges for light, softened
 * tones for dark, and WCAG-AA contrast for high-contrast).
 */
@Immutable
data class Rs457StatusColors(
    val connected: Color,
    val inProgress: Color,
    val error: Color,
)

private val LightStatusColors = Rs457StatusColors(
    connected = Color(0xFF2E7D32),
    inProgress = Color(0xFFE65100),
    error = Color(0xFFBA1A1A),
)

private val DarkStatusColors = Rs457StatusColors(
    connected = Color(0xFF81C784),
    inProgress = Color(0xFFFFB74D),
    error = Color(0xFFFFB4AB),
)

private val HighContrastLightStatusColors = Rs457StatusColors(
    connected = Color(0xFF1B5E20),
    inProgress = Color(0xFFA03A00),
    error = Color(0xFF690005),
)

private val HighContrastDarkStatusColors = Rs457StatusColors(
    connected = Color(0xFFA5D6A7),
    inProgress = Color(0xFFFFCC80),
    error = Color(0xFFFFB4AB),
)

val LocalRs457StatusColors = staticCompositionLocalOf { LightStatusColors }

val MaterialTheme.statusColors: Rs457StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalRs457StatusColors.current

val Rs457Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Hero-style typography for the live telemetry speed / RPM readout.
 * Defined as a dedicated M3 token so the value lives in the theme rather than as an
 * ad-hoc `displayLarge.copy(fontSize = 72.sp)` override at the call site.
 */
val TelemetryHero = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Light,
    fontSize = 72.sp,
    lineHeight = 80.sp,
    letterSpacing = 0.sp,
)

val Rs457Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun Rs457Theme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val colorScheme = remember(context, configuration, darkTheme, dynamicColor, highContrast) {
        when {
            highContrast && darkTheme -> HighContrastDarkColors
            highContrast -> HighContrastLightColors
            dynamicColor -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    }
    val statusColors = when {
        highContrast && darkTheme -> HighContrastDarkStatusColors
        highContrast -> HighContrastLightStatusColors
        darkTheme -> DarkStatusColors
        else -> LightStatusColors
    }

    CompositionLocalProvider(
        LocalRs457StatusColors provides statusColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Rs457Typography,
            shapes = Rs457Shapes,
            content = content,
        )
    }
}
