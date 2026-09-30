package com.spaceboy.ridebuddy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.spaceboy.ridebuddy.data.ThemeMode

// The app's own colour schemes, used when dynamic colour is off or unavailable. Built
// around a red primary rather than Material's baseline purple.

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
 * The app's Material 3 theme.
 *
 * Three inputs decide the palette, in a fixed order of precedence. High contrast wins
 * outright — an accessibility setting must not be overridden by a preference. Dynamic
 * colour comes next, taking the palette from the system wallpaper. Failing both, the app's
 * own schemes are used.
 *
 * The scheme is remembered against the configuration as well as the flags, because dynamic
 * colour is derived from system state that a configuration change can alter.
 */
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
    MaterialTheme(colorScheme = colorScheme, content = content)
}
