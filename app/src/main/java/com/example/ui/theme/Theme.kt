package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorTokensOutlineVariantLight = androidx.compose.ui.graphics.Color(0xFFE7EFFB)

private val GolocalLightColorScheme = lightColorScheme(
    primary = GolocalPrimaryBlue,
    onPrimary = GolocalPureWhite,
    primaryContainer = GolocalLightBlue,
    onPrimaryContainer = GolocalDeepNavy,
    secondary = GolocalSecondaryBlue,
    onSecondary = GolocalPureWhite,
    secondaryContainer = GolocalSoftBlue,
    onSecondaryContainer = GolocalPrimaryText,
    tertiary = GolocalDeepNavy,
    onTertiary = GolocalPureWhite,
    background = GolocalSoftBlue,
    onBackground = GolocalPrimaryText,
    surface = GolocalPureWhite,
    onSurface = GolocalPrimaryText,
    surfaceVariant = GolocalLightBlue,
    onSurfaceVariant = GolocalSecondaryText,
    outline = GolocalBorderLight,
    outlineVariant = ColorTokensOutlineVariantLight,
    error = GolocalError,
    onError = GolocalPureWhite,
    errorContainer = GolocalErrorContainer,
    onErrorContainer = GolocalError
)

private val GolocalDarkColorScheme = darkColorScheme(
    primary = GolocalDarkPrimary,
    onPrimary = GolocalDarkOnPrimary,
    primaryContainer = GolocalDeepNavy,
    onPrimaryContainer = GolocalLightBlue,
    secondary = GolocalSecondaryBlue,
    onSecondary = GolocalDarkOnPrimary,
    secondaryContainer = GolocalDarkSurfaceVariant,
    onSecondaryContainer = GolocalDarkTextPrimary,
    tertiary = GolocalSecondaryBlue,
    onTertiary = GolocalDarkOnPrimary,
    background = GolocalDarkBackground,
    onBackground = GolocalDarkTextPrimary,
    surface = GolocalDarkSurface,
    onSurface = GolocalDarkTextPrimary,
    surfaceVariant = GolocalDarkSurfaceVariant,
    onSurfaceVariant = GolocalDarkTextSecondary,
    outline = GolocalDarkBorder,
    outlineVariant = GolocalDarkSurfaceVariant,
    error = GolocalError,
    onError = GolocalPureWhite,
    errorContainer = androidx.compose.ui.graphics.Color(0xFF47181B),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFDADA)
)

val GolocalShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun GolocalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GolocalDarkColorScheme else GolocalLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GolocalTypography,
        shapes = GolocalShapes,
        content = content
    )
}

@Composable
fun rememberPrefersReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        val animatorsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled()
        val durationScale = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        !animatorsEnabled || durationScale <= 0f
    }
}

