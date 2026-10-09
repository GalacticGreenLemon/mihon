package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Remon theme: red on black, with a lemon yellow accent for the downloaded badge.
 *
 * Key colors:
 * Primary #FF5A5F (dark) / #C1121B (light)
 * Tertiary #FFD54F (dark) / #7A6100 (light)
 * Neutral #000000 (dark) / #FFFBFB (light)
 */
internal object RemonColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFF5A5F),
        onPrimary = Color(0xFF2B0003),
        primaryContainer = Color(0xFFB3121B),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFFF3B3B), // Unread badge
        onSecondary = Color(0xFF000000), // Unread badge text
        secondaryContainer = Color(0xFF5C0A0E), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFFFDADA), // Navigation bar selector icon
        tertiary = Color(0xFFFFD54F), // Downloaded badge
        onTertiary = Color(0xFF1F1A00), // Downloaded badge text
        tertiaryContainer = Color(0xFF6B5600),
        onTertiaryContainer = Color(0xFFFFF3C4),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF2E7E7),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFF2E7E7),
        surfaceVariant = Color(0xFF1C1212), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFD8C2C2),
        outline = Color(0xFF9E8A8A),
        outlineVariant = Color(0xFF4A3434),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF2E7E7),
        inverseOnSurface = Color(0xFF2A1F1F),
        inversePrimary = Color(0xFFB3121B),
        surfaceDim = Color(0xFF000000),
        surfaceBright = Color(0xFF2E2222),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0D0808),
        surfaceContainer = Color(0xFF140C0C), // Navigation bar background
        surfaceContainerHigh = Color(0xFF1E1414),
        surfaceContainerHighest = Color(0xFF281B1B),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFC1121B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE53935),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFC1121B), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFFFD9D7), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF410003), // Navigation bar selector icon
        tertiary = Color(0xFF7A6100), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFFFFE17A),
        onTertiaryContainer = Color(0xFF241A00),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFFBFB),
        onBackground = Color(0xFF1F1A1A),
        surface = Color(0xFFFFFBFB),
        onSurface = Color(0xFF1F1A1A),
        surfaceVariant = Color(0xFFF7E9E8), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFF534343),
        outline = Color(0xFF857373),
        outlineVariant = Color(0xFFD8C2C1),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF362F2F),
        inverseOnSurface = Color(0xFFFBEEED),
        inversePrimary = Color(0xFFFFB3AE),
        surfaceDim = Color(0xFFE8D6D5),
        surfaceBright = Color(0xFFFFFBFB),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF0EF),
        surfaceContainer = Color(0xFFFAEAE9), // Navigation bar background
        surfaceContainerHigh = Color(0xFFF4E4E3),
        surfaceContainerHighest = Color(0xFFEEDEDD),
    )
}
