package com.ligma67.launcher.ui.theme

import android.graphics.Color.colorToHSV
import android.graphics.Color.HSVToColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import com.ligma67.launcher.data.LabelColor
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.TintMode
import com.ligma67.launcher.glass.BackdropImage
import com.ligma67.launcher.glass.GlassEnv
import com.ligma67.launcher.glass.GlassStyle
import com.ligma67.launcher.glass.LocalGlass
import com.ligma67.launcher.glass.WallpaperTone

@Immutable
data class LauncherColors(
    val label: Color = Color.White,
    val labelShadow: Color = Color(0x99000000),
    val onGlass: Color = Color.White,
    val onGlassDim: Color = Color(0xB3FFFFFF),
    val accent: Color = Color(0xFF5B8CFF),
    val darkGlass: Boolean = true,
    val surface: Color = Color(0xFF15181E),
    val scrim: Color = Color(0x66000000),
)

val LocalLauncherColors = staticCompositionLocalOf { LauncherColors() }
val LocalSettings = staticCompositionLocalOf { LauncherSettings() }

private fun vivid(argb: Int): Int {
    val hsv = FloatArray(3)
    colorToHSV(argb, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = hsv[2].coerceAtLeast(0.78f)
    return HSVToColor(hsv)
}

/** Resolves settings + wallpaper into concrete colors and the pixel-space glass style. */
@Composable
fun LauncherTheme(
    settings: LauncherSettings,
    tone: WallpaperTone,
    backdrop: BackdropImage?,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val env = remember(settings, tone, backdrop, density) {
        val custom = Color(settings.glassCustomTint.toInt())
        val darkGlass = when (settings.glassTintMode) {
            TintMode.AUTO -> tone.isDark
            TintMode.LIGHT -> false
            TintMode.DARK -> true
            TintMode.CUSTOM -> custom.luminance() < 0.5f
        }
        val base = when (settings.glassTintMode) {
            TintMode.AUTO, TintMode.DARK, TintMode.LIGHT -> if (darkGlass) 0x0E1116 else 0xFFFFFF
            TintMode.CUSTOM -> settings.glassCustomTint.toInt() and 0xFFFFFF
        }
        val alpha = (settings.glassTintAlpha * 255).toInt().coerceIn(0, 255)
        val style = GlassStyle(
            enabled = settings.glassEnabled,
            tint = (alpha shl 24) or base,
            refractionPx = settings.glassRefraction * 30f * density.density,
            edgePx = 24f * density.density,
            dispersion = settings.glassDispersion,
            highlight = settings.glassHighlight,
            saturation = settings.glassSaturation,
            borderPx = settings.glassBorderDp * density.density,
            highQuality = settings.glassHighQuality,
            isDark = darkGlass,
        )
        GlassEnv(style, if (settings.glassEnabled) backdrop else null)
    }
    val colors = remember(settings, tone, env) {
        val dark = env.style.isDark
        val accent = if (settings.accentAuto) Color(vivid(tone.primary)) else Color(settings.accentColor.toInt())
        val label = when (settings.labelColor) {
            LabelColor.WHITE -> Color.White
            LabelColor.BLACK -> Color(0xFF111111)
            LabelColor.AUTO -> if (tone.isDark) Color.White else Color(0xFF111111)
        }
        LauncherColors(
            label = label,
            labelShadow = if (label.luminance() > 0.5f) Color(0x99000000) else Color(0x66FFFFFF),
            onGlass = if (dark) Color.White else Color(0xFF101114),
            onGlassDim = if (dark) Color(0xB3FFFFFF) else Color(0x99101114),
            accent = accent,
            darkGlass = dark,
            surface = if (dark) Color(0xFF15181E) else Color(0xFFF4F6FA),
            scrim = if (dark) Color(0x66000000) else Color(0x33000000),
        )
    }
    val scheme = remember(colors) {
        if (colors.darkGlass) {
            darkColorScheme(
                primary = colors.accent,
                onPrimary = Color.White,
                surface = colors.surface,
                onSurface = colors.onGlass,
                onSurfaceVariant = colors.onGlassDim,
                surfaceContainerHigh = Color(0xFF22262E),
            )
        } else {
            lightColorScheme(
                primary = colors.accent,
                onPrimary = Color.White,
                surface = colors.surface,
                onSurface = colors.onGlass,
                onSurfaceVariant = colors.onGlassDim,
                surfaceContainerHigh = Color(0xFFE8EBF2),
            )
        }
    }
    CompositionLocalProvider(
        LocalGlass provides env,
        LocalLauncherColors provides colors,
        LocalSettings provides settings,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
