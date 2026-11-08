package art.argan.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens: the single source of truth for colour and dimension.
 *
 * P9 requires zero hardcoded colours or dimensions outside `:design` and
 * resources. Establishing that from P0 avoids a migration later. Every UI module
 * reads colours from [ArganTheme.colors] and never from `Color(0x…)`.
 */

/**
 * Semantic colours.
 *
 * Named for meaning, not hue, so a palette change never requires a call-site
 * change. The four canvas layer colours exist now because the ink renderer (P1)
 * treats layers as visually distinct by contract, not by taste.
 */
@Immutable
public data class ArganColors(
    val canvasBackground: Color,
    val canvasGrid: Color,
    /** Committed artwork (P1). */
    val ink: Color,
    /** In-progress stroke (P1). */
    val inkInProgress: Color,
    /** Predicted stroke tail, visually identical to ink, own layer (P1). */
    val inkPredicted: Color,
    /** Machine-suggested portion of a generative stroke, accent (P4). */
    val ghostSuggestion: Color,
    /** The motion ribbon: deliberately neither ink nor UI chrome (P2). */
    val ribbon: Color,
    /** Ribbon highlight where a node sits; warmer and lighter than [ribbon]. */
    val ribbonNode: Color,
    /** The element a ribbon drives, so motion reads before the user knows what it is. */
    val ribbonElement: Color,
    /** Editorial text on paper: the welcome card's heading and body. */
    val paperInk: Color,
    val paperInkMuted: Color,
    /** Spatial objects: the "object" language, subtle, distinct from ink (P3). */
    val objectChrome: Color,
    val chromeSurface: Color,
    val chromeContent: Color,
    val chromeContentMuted: Color,
)

/** Spacing and sizing scale. */
@Immutable
public data class ArganDimensions(
    val hairline: Dp = 1.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 40.dp,
    /** Minimum touch target; P9 accessibility requirement, applied from P0. */
    val minTouchTarget: Dp = 48.dp,
)

private val LightChrome = Color(0xFFF4F1EC)
private val LightContent = Color(0xFF1C1A17)
private val LightContentMuted = Color(0xFF6B665E)

private val DarkChrome = Color(0xFF1A1917)
private val DarkContent = Color(0xFFEDE9E3)
private val DarkContentMuted = Color(0xFF9A948A)

private val ArganLightColors = ArganColors(
    canvasBackground = Color(0xFFFBF9F6),
    canvasGrid = Color(0x14000000),
    ink = Color(0xFF141210),
    inkInProgress = Color(0xFF141210),
    inkPredicted = Color(0xFF141210),
    ghostSuggestion = Color(0xFF3D6BE0),
    ribbon = Color(0xFFB4531F),
    ribbonNode = Color(0xFFE0733A),
    ribbonElement = Color(0xFF8C3F1C),
    paperInk = Color(0xFF2A2621),
    paperInkMuted = Color(0xFF8A8378),
    objectChrome = Color(0x33403A33),
    chromeSurface = LightChrome,
    chromeContent = LightContent,
    chromeContentMuted = LightContentMuted,
)

private val ArganDarkColors = ArganColors(
    canvasBackground = Color(0xFF121110),
    canvasGrid = Color(0x1AFFFFFF),
    ink = Color(0xFFF2EEE8),
    inkInProgress = Color(0xFFF2EEE8),
    inkPredicted = Color(0xFFF2EEE8),
    ghostSuggestion = Color(0xFF7E9CFF),
    ribbon = Color(0xFFE08A4F),
    ribbonNode = Color(0xFFF5A96B),
    ribbonElement = Color(0xFFFFC79B),
    paperInk = Color(0xFFEDE9E3),
    paperInkMuted = Color(0xFF9A948A),
    objectChrome = Color(0x33D8D2C8),
    chromeSurface = DarkChrome,
    chromeContent = DarkContent,
    chromeContentMuted = DarkContentMuted,
)

private val ArganTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
)

private val LocalArganColors = staticCompositionLocalOf { ArganLightColors }
private val LocalArganDimensions = staticCompositionLocalOf { ArganDimensions() }

/** Token access point. Use `ArganTheme.colors.ink`, never a literal colour. */
public object ArganTheme {
    public val colors: ArganColors
        @Composable get() = LocalArganColors.current

    public val dimensions: ArganDimensions
        @Composable get() = LocalArganDimensions.current
}

/**
 * Applies the Argan token set and a Material 3 scheme derived from it.
 *
 * @param darkTheme follows the system by default; P9 exposes the explicit toggle.
 */
@Composable
public fun ArganTheme(
    /**
     * Defaults to the PAPER theme, not the system's.
     *
     * The canvas is the product; a drawing surface should look like somewhere you would
     * want to draw. Following the system by default meant most devices opened to a
     * near-black rectangle with no visible structure, which reads as an unfinished tool
     * rather than as a canvas. The dark palette still exists — P9 exposes the toggle, and
     * it is the right choice for a dark room — but it is a choice, not the default.
     */
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) ArganDarkColors else ArganLightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            background = colors.canvasBackground,
            surface = colors.chromeSurface,
            onSurface = colors.chromeContent,
            onBackground = colors.chromeContent,
        )
    } else {
        lightColorScheme(
            background = colors.canvasBackground,
            surface = colors.chromeSurface,
            onSurface = colors.chromeContent,
            onBackground = colors.chromeContent,
        )
    }

    CompositionLocalProvider(
        LocalArganColors provides colors,
        LocalArganDimensions provides ArganDimensions(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = ArganTypography, content = content)
    }
}
