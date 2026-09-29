// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Tentacle's green theme, built from the logo (branding/tentacle-logo.png), whose greens are
 * deep #02341B, mid #088C32, bright #5EDA2F and lime #ACF753.
 *
 * The app always uses these colours (no wallpaper-based dynamic colour) so it looks like Tentacle
 * everywhere. Light and dark follow the system setting. Text/background pairs are chosen for
 * WCAG AA contrast (e.g. white on the light primary is about 5.4:1).
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A7A2E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9F59A),
    onPrimaryContainer = Color(0xFF00210A),
    inversePrimary = Color(0xFF7EE24D),
    secondary = Color(0xFF3F6A36),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6EFB6),
    onSecondaryContainer = Color(0xFF0B2106),
    tertiary = Color(0xFF3E6A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFF77B),
    onTertiaryContainer = Color(0xFF0F2000),
    background = Color(0xFFF6FBF2),
    onBackground = Color(0xFF171D16),
    surface = Color(0xFFF6FBF2),
    onSurface = Color(0xFF171D16),
    surfaceVariant = Color(0xFFDDE6D6),
    onSurfaceVariant = Color(0xFF414A3E),
    surfaceTint = Color(0xFF0A7A2E),
    inverseSurface = Color(0xFF2C322B),
    inverseOnSurface = Color(0xFFEDF2E8),
    outline = Color(0xFF71796D),
    outlineVariant = Color(0xFFC1C9BB),
    surfaceBright = Color(0xFFF6FBF2),
    surfaceDim = Color(0xFFD6DCD2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F6EC),
    surfaceContainer = Color(0xFFEAF0E6),
    surfaceContainerHigh = Color(0xFFE4EAE0),
    surfaceContainerHighest = Color(0xFFDFE5DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EE24D),
    onPrimary = Color(0xFF053900),
    primaryContainer = Color(0xFF0B5E22),
    onPrimaryContainer = Color(0xFFB9F59A),
    inversePrimary = Color(0xFF0A7A2E),
    secondary = Color(0xFFABD39B),
    onSecondary = Color(0xFF17370F),
    secondaryContainer = Color(0xFF2B4E22),
    onSecondaryContainer = Color(0xFFC6EFB6),
    tertiary = Color(0xFFACF753),
    onTertiary = Color(0xFF1E3700),
    tertiaryContainer = Color(0xFF2E5000),
    onTertiaryContainer = Color(0xFFBFF77B),
    background = Color(0xFF0D150F),
    onBackground = Color(0xFFDDE5D8),
    surface = Color(0xFF0D150F),
    onSurface = Color(0xFFDDE5D8),
    surfaceVariant = Color(0xFF414A3E),
    onSurfaceVariant = Color(0xFFC1C9BB),
    surfaceTint = Color(0xFF7EE24D),
    inverseSurface = Color(0xFFDDE5D8),
    inverseOnSurface = Color(0xFF2C322B),
    outline = Color(0xFF8B9386),
    outlineVariant = Color(0xFF414A3E),
    surfaceBright = Color(0xFF333B32),
    surfaceDim = Color(0xFF0D150F),
    surfaceContainerLowest = Color(0xFF08100A),
    surfaceContainerLow = Color(0xFF151D16),
    surfaceContainer = Color(0xFF19221A),
    surfaceContainerHigh = Color(0xFF232C24),
    surfaceContainerHighest = Color(0xFF2E372E),
)

/** Material 3 theme in Tentacle green; follows the system light/dark setting. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
