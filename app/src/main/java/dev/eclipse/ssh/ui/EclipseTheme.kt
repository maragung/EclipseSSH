package dev.eclipse.ssh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

private val EclipseBlue = Color(0xFF8EAFFF)
private val EclipseBlueBright = Color(0xFFB7C9FF)
private val EclipseInk = Color(0xFF0A0D16)
private val EclipseSurface = Color(0xFF121724)
private val EclipseSurfaceHigh = Color(0xFF1B2232)
private val EclipseText = Color(0xFFE8ECF7)
private val EclipseMuted = Color(0xFF9BA7BC)
private val EclipseGreen = Color(0xFF6DE0B0)
private val EclipseOrange = Color(0xFFFFB86B)

private val DarkColors = darkColorScheme(
    primary = EclipseBlue,
    onPrimary = EclipseInk,
    primaryContainer = Color(0xFF283B70),
    onPrimaryContainer = EclipseBlueBright,
    secondary = Color(0xFFB9C6E7),
    background = EclipseInk,
    onBackground = EclipseText,
    surface = EclipseSurface,
    onSurface = EclipseText,
    surfaceVariant = EclipseSurfaceHigh,
    onSurfaceVariant = EclipseMuted,
    outline = Color(0xFF34405A),
    tertiary = EclipseGreen,
    error = Color(0xFFFF8E9E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3559B7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF07164A),
    secondary = Color(0xFF53617D),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171A22),
    surface = Color.White,
    onSurface = Color(0xFF171A22),
    surfaceVariant = Color(0xFFECEFF7),
    onSurfaceVariant = Color(0xFF596174),
    outline = Color(0xFFC5CAD8),
    tertiary = Color(0xFF087A52),
    error = Color(0xFFB3263E),
)

private val EclipseTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun EclipseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    // Edge-to-edge draws the status and navigation bars over a transparent background, so their
    // icons have to contrast with whatever this app painted behind them. That background follows
    // the app's own darkTheme setting, which is deliberately independent of the system night mode -
    // but enableEdgeToEdge()'s default styling reads the *system* uiMode, and nothing else in the
    // app sets the icon appearance. So when the two disagree, which is the ordinary case of the
    // default dark app on a light-mode device, the framework leaves dark icons on a dark app
    // surface and the battery, clock and signal glyphs disappear into it. Drive the appearance
    // from the same flag that drives the surface: dark app -> light (white) icons, and vice versa.
    //
    // A SideEffect rather than a DisposableEffect(darkTheme): it re-applies after each successful
    // recomposition, so toggling "Dark appearance" in Settings flips the icons on the same frame
    // the surface changes instead of a frame later.
    //
    // Guarded the way the other two window effects in this app are (MainActivity's FLAG_SECURE and
    // terminal-immersive effects): the context is an Activity only where there is a real window, so
    // the cast is what makes this a no-op in a preview or a bare-JVM composition, and an OEM insets
    // implementation that refuses the call must not take the process down - these icons are
    // decoration, and every screen behind them stays perfectly usable without them.
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) runCatching {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = EclipseTypography,
        content = content,
    )
}

/**
 * Status accents, used as icon tints and text colours for connection and transfer state.
 *
 * Both keep their bright forms on the dark scheme and switch to darker variants on the light
 * one. [EclipseGreen] and [EclipseOrange] sit at roughly 1.6:1 and 1.7:1 against the light
 * scheme's white surface — well under the WCAG AA minimum of 4.5:1 for text and 3:1 for
 * icons — so anyone using the light theme could barely see them. The light values below clear
 * 5:1. The scheme is detected from the installed surface colour rather than a parameter so
 * every existing call site keeps working, previews included.
 */
val EclipseSuccess: Color
    @Composable @ReadOnlyComposable
    get() = if (isDarkScheme) EclipseGreen else Color(0xFF087A52)

val EclipseWarning: Color
    @Composable @ReadOnlyComposable
    get() = if (isDarkScheme) EclipseOrange else Color(0xFF8A5200)

private val isDarkScheme: Boolean
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f
