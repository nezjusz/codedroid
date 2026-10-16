package io.github.nezjusz.codedroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Mint40,
    onPrimary = Mint10,
    primaryContainer = Mint30,
    onPrimaryContainer = Mint90,
    inversePrimary = Mint60,

    secondary = Slate90,
    onSecondary = Slate10,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate95,

    tertiary = Amber80,
    onTertiary = Amber10,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,

    error = Rose80,
    onError = Color(0xFF690005),
    errorContainer = Rose30,
    onErrorContainer = Rose90,

    background = NeutralDarkSurface,
    onBackground = NeutralDarkOnSurface,
    surface = NeutralDarkSurface,
    onSurface = NeutralDarkOnSurface,
    surfaceVariant = Slate30,
    onSurfaceVariant = Slate90,
    surfaceContainerLowest = Color(0xFF090F0D),
    surfaceContainerLow = Color(0xFF161D1A),
    surfaceContainer = NeutralDarkSurfaceContainer,
    surfaceContainerHigh = NeutralDarkSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF2E3632),
    surfaceTint = Mint40,
    inverseSurface = NeutralLightSurface,
    inverseOnSurface = NeutralLightOnSurface,

    outline = Color(0xFF7C9188),
    outlineVariant = Color(0xFF3C4F47),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Mint30,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Mint90,
    onPrimaryContainer = Mint10,
    inversePrimary = Mint60,

    secondary = Slate40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate10,

    tertiary = Amber40,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,

    error = Rose40,
    onError = Color(0xFFFFFFFF),
    errorContainer = Rose90,
    onErrorContainer = Rose10,

    background = NeutralLightSurface,
    onBackground = NeutralLightOnSurface,
    surface = NeutralLightSurface,
    onSurface = NeutralLightOnSurface,
    surfaceVariant = Slate90,
    onSurfaceVariant = Slate30,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = NeutralLightSurfaceContainer,
    surfaceContainerHigh = Color(0xFFE3EAE5),
    surfaceContainerHighest = Color(0xFFDDE4DF),
    surfaceTint = Mint30,
    inverseSurface = NeutralDarkSurface,
    inverseOnSurface = NeutralDarkOnSurface,

    outline = Color(0xFF6C817A),
    outlineVariant = Slate90,
    scrim = Color(0xFF000000),
)

/** Semantic colours the Material scheme has no slot for. */
data class StatusColors(
    val running: Color,
    val waiting: Color,
    val idle: Color,
    val codeBackground: Color,
    val codeForeground: Color,
    val reasoning: Color,
)

private val DarkStatus = StatusColors(
    running = Mint40,
    waiting = Amber80,
    idle = Color(0xFF7C9188),
    codeBackground = CodeBlockDark,
    codeForeground = Color(0xFFCFE8DC),
    reasoning = Color(0xFF9DB3A9),
)

private val LightStatus = StatusColors(
    running = Mint30,
    waiting = Amber40,
    idle = Color(0xFF6C817A),
    codeBackground = CodeBlockLight,
    codeForeground = Color(0xFF1B2A23),
    reasoning = Color(0xFF5A6F67),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

val MonoTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

val MonoSmallStyle: TextStyle = MonoTextStyle.copy(fontSize = 11.sp, lineHeight = 15.sp)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
)

/**
 * Material 3 Expressive theme.
 *
 * `MaterialExpressiveTheme` applies the expressive motion scheme used by every Material 3
 * Expressive component in the app ([androidx.compose.material3.LoadingIndicator] and friends
 * animate against `MaterialTheme.motionScheme`).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CodeDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
