package com.gameocr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Apple-inspired neutral palette: quiet whites, graphite text, one restrained system-blue accent.
val AppleWhite = Color(0xFFFFFFFF)
val AppleMist = Color(0xFFF7F8FA)
val AppleCloud = Color(0xFFEFF2F6)
val AppleLine = Color(0xFFDCE2EA)
val AppleSecondary = Color(0xFF6E7783)
val AppleGraphite = Color(0xFF1D1D1F)
val AppleInk = Color(0xFF0B0F17)
val AppleBlue = Color(0xFF007AFF)
val AppleBlueSoft = Color(0xFFE7F1FF)
val AppleDarkBg = Color(0xFF090B10)
val AppleDarkPanel = Color(0xFF151820)
val AppleDarkPanelHigh = Color(0xFF20242E)

/**
 * Apple-style 极简主题。覆盖 Material3 surface 体系，避免默认紫调，保持单一蓝色点缀。
 */
private val AppleLight = lightColorScheme(
    primary = AppleBlue,
    onPrimary = AppleWhite,
    primaryContainer = AppleBlueSoft,
    onPrimaryContainer = AppleInk,

    secondary = AppleGraphite,
    onSecondary = AppleWhite,
    secondaryContainer = AppleCloud,
    onSecondaryContainer = AppleGraphite,

    tertiary = AppleSecondary,
    onTertiary = AppleWhite,
    tertiaryContainer = AppleCloud,
    onTertiaryContainer = AppleGraphite,

    background = AppleMist,
    onBackground = AppleGraphite,
    surface = AppleWhite,
    onSurface = AppleGraphite,
    surfaceVariant = AppleCloud,
    onSurfaceVariant = AppleSecondary,

    surfaceTint = AppleBlue,
    surfaceBright = AppleWhite,
    surfaceDim = AppleCloud,
    surfaceContainer = Color(0xFFF3F5F8),
    surfaceContainerLowest = AppleWhite,
    surfaceContainerLow = AppleMist,
    surfaceContainerHigh = AppleCloud,
    surfaceContainerHighest = AppleLine,

    inverseSurface = AppleInk,
    inverseOnSurface = AppleWhite,
    inversePrimary = Color(0xFF8CC8FF),

    outline = AppleLine,
    outlineVariant = Color(0xBFFFFFFF),
    scrim = Color(0x66000000),

    error = Color(0xFFFF3B30),
    onError = AppleWhite,
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFF7A1711),
)

private val AppleDark = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = AppleWhite,
    primaryContainer = Color(0xFF103B66),
    onPrimaryContainer = Color(0xFFE8F3FF),

    secondary = Color(0xFFE8EAEE),
    onSecondary = AppleInk,
    secondaryContainer = AppleDarkPanelHigh,
    onSecondaryContainer = Color(0xFFE8EAEE),

    tertiary = Color(0xFFB8C0CC),
    onTertiary = AppleInk,
    tertiaryContainer = AppleDarkPanel,
    onTertiaryContainer = Color(0xFFE8EAEE),

    background = AppleDarkBg,
    onBackground = Color(0xFFF5F7FA),
    surface = AppleDarkPanel,
    onSurface = Color(0xFFF5F7FA),
    surfaceVariant = AppleDarkPanelHigh,
    onSurfaceVariant = Color(0xFFB4BBC6),

    surfaceTint = Color(0xFF0A84FF),
    surfaceBright = AppleDarkPanelHigh,
    surfaceDim = AppleDarkBg,
    surfaceContainer = AppleDarkPanel,
    surfaceContainerLowest = AppleDarkBg,
    surfaceContainerLow = Color(0xFF10131A),
    surfaceContainerHigh = AppleDarkPanelHigh,
    surfaceContainerHighest = Color(0xFF2C313D),

    inverseSurface = Color(0xFFF5F7FA),
    inverseOnSurface = AppleInk,
    inversePrimary = AppleBlue,

    outline = Color(0xFF343A46),
    outlineVariant = Color(0x22FFFFFF),
    scrim = Color(0x99000000),

    error = Color(0xFFFF453A),
    onError = AppleInk,
    errorContainer = Color(0xFF5A1712),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 主题模式：值与 [com.gameocr.app.data.ThemeModePrefs] 中的常量对应。 */
object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

@Composable
fun GameOcrTheme(
    themeMode: Int = ThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) AppleDark else AppleLight,
        content = content
    )
}
