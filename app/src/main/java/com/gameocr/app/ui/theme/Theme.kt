package com.gameocr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// shadcn/UI Zinc palette
val Zinc50 = Color(0xFFFAFAFA)
val Zinc100 = Color(0xFFF4F4F5)
val Zinc200 = Color(0xFFE4E4E7)
val Zinc300 = Color(0xFFD4D4D8)
val Zinc400 = Color(0xFFA1A1AA)
val Zinc500 = Color(0xFF71717A)
val Zinc600 = Color(0xFF52525B)
val Zinc700 = Color(0xFF3F3F46)
val Zinc800 = Color(0xFF27272A)
val Zinc900 = Color(0xFF18181B)
val Zinc950 = Color(0xFF09090B)

/**
 * Zinc 中性灰主题。覆盖所有 Material3 surface 体系，避免默认 surfaceTint=primary
 * 导致 Card / Surface 染上紫调。
 */
private val ZincLight = lightColorScheme(
    primary = Zinc900,
    onPrimary = Zinc50,
    primaryContainer = Zinc200,
    onPrimaryContainer = Zinc900,

    secondary = Zinc700,
    onSecondary = Zinc50,
    secondaryContainer = Zinc200,
    onSecondaryContainer = Zinc800,

    tertiary = Zinc600,
    onTertiary = Zinc50,
    tertiaryContainer = Zinc100,
    onTertiaryContainer = Zinc800,

    background = Zinc50,
    onBackground = Zinc900,
    surface = Color.White,
    onSurface = Zinc900,
    surfaceVariant = Zinc100,
    onSurfaceVariant = Zinc600,

    // 完整 surface tones —— 消除紫调
    surfaceTint = Zinc500,
    surfaceBright = Color.White,
    surfaceDim = Zinc200,
    surfaceContainer = Zinc100,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Zinc50,
    surfaceContainerHigh = Zinc200,
    surfaceContainerHighest = Zinc300,

    inverseSurface = Zinc900,
    inverseOnSurface = Zinc50,
    inversePrimary = Zinc300,

    outline = Zinc300,
    outlineVariant = Zinc200,
    scrim = Color(0x66000000),

    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val ZincDark = darkColorScheme(
    primary = Zinc50,
    onPrimary = Zinc900,
    primaryContainer = Zinc800,
    onPrimaryContainer = Zinc50,

    secondary = Zinc300,
    onSecondary = Zinc900,
    secondaryContainer = Zinc800,
    onSecondaryContainer = Zinc200,

    tertiary = Zinc400,
    onTertiary = Zinc900,
    tertiaryContainer = Zinc800,
    onTertiaryContainer = Zinc100,

    background = Zinc950,
    onBackground = Zinc50,
    surface = Zinc900,
    onSurface = Zinc50,
    surfaceVariant = Zinc800,
    onSurfaceVariant = Zinc400,

    surfaceTint = Zinc500,
    surfaceBright = Zinc800,
    surfaceDim = Zinc950,
    surfaceContainer = Zinc900,
    surfaceContainerLowest = Zinc950,
    surfaceContainerLow = Zinc900,
    surfaceContainerHigh = Zinc800,
    surfaceContainerHighest = Zinc700,

    inverseSurface = Zinc100,
    inverseOnSurface = Zinc900,
    inversePrimary = Zinc700,

    outline = Zinc700,
    outlineVariant = Zinc800,
    scrim = Color(0x99000000),

    error = Color(0xFFEF4444),
    onError = Zinc950,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
)

@Composable
fun GameOcrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ZincDark else ZincLight,
        content = content
    )
}
