package com.renpy.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.renpy.mobile.ui.AppPalette

// ── 品牌：靛蓝紫（代码编辑器气质），M3 Expressive 完整 48 色角色 ──

val LightColors = lightColorScheme(
    primary = Color(0xFF4A4AC9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF000069),

    secondary = Color(0xFF5D5D7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E0FA),
    onSecondaryContainer = Color(0xFF1A1A32),

    tertiary = Color(0xFF775574),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8F4),
    onTertiaryContainer = Color(0xFF2D142B),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1B1B20),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF46464F),

    surfaceTint = Color(0xFF4A4AC9),
    inverseSurface = Color(0xFF302F35),
    inverseOnSurface = Color(0xFFF5EFFA),
    inversePrimary = Color(0xFFB9B8FF),

    // 5 级容器层级 —— 替代透明度做层次
    surfaceDim = Color(0xFFDDD9E7),
    surfaceBright = Color(0xFFFDFBFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3FB),
    surfaceContainer = Color(0xFFF1EDF6),
    surfaceContainerHigh = Color(0xFFEBE7F1),
    surfaceContainerHighest = Color(0xFFE5E1EC),

    outline = Color(0xFF777580),
    outlineVariant = Color(0xFFC8C5D0),
    scrim = Color(0xFF000000),

    // 固定强调色（跨深浅一致）
    primaryFixed = Color(0xFFE1E0FF),
    primaryFixedDim = Color(0xFFB9B8FF),
    onPrimaryFixed = Color(0xFF000069),
    onPrimaryFixedVariant = Color(0xFF3E3EAE),
    secondaryFixed = Color(0xFFE1E0FA),
    secondaryFixedDim = Color(0xFFC4C2DD),
    onSecondaryFixed = Color(0xFF1A1A32),
    onSecondaryFixedVariant = Color(0xFF4A4A66),
    tertiaryFixed = Color(0xFFFFD8F4),
    tertiaryFixedDim = Color(0xFFE2BAD9),
    onTertiaryFixed = Color(0xFF2D142B),
    onTertiaryFixedVariant = Color(0xFF6E4D6B),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B8FF),
    onPrimary = Color(0xFF25256D),
    primaryContainer = Color(0xFF3D3D8A),
    onPrimaryContainer = Color(0xFFDEDCFF),

    secondary = Color(0xFFC5C3E3),
    onSecondary = Color(0xFF2E2E46),
    secondaryContainer = Color(0xFF41415E),
    onSecondaryContainer = Color(0xFFE1E0FA),

    tertiary = Color(0xFFE1BEDF),
    onTertiary = Color(0xFF442943),
    tertiaryContainer = Color(0xFF5C3F5A),
    onTertiaryContainer = Color(0xFFFEDAF5),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF141318),
    onBackground = Color(0xFFE6E1EB),
    surface = Color(0xFF141318),
    onSurface = Color(0xFFE6E1EB),
    surfaceVariant = Color(0xFF46454F),
    onSurfaceVariant = Color(0xFFC7C4D0),

    surfaceTint = Color(0xFFB9B8FF),
    inverseSurface = Color(0xFFE6E1EB),
    inverseOnSurface = Color(0xFF302F35),
    inversePrimary = Color(0xFF4A4AC9),

    surfaceDim = Color(0xFF141318),
    surfaceBright = Color(0xFF3A3940),
    surfaceContainerLowest = Color(0xFF0F0E13),
    surfaceContainerLow = Color(0xFF1A191F),
    surfaceContainer = Color(0xFF1E1D24),
    surfaceContainerHigh = Color(0xFF29272F),
    surfaceContainerHighest = Color(0xFF34323A),

    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46454F),
    scrim = Color(0xFF000000),

    primaryFixed = Color(0xFFE1E0FF),
    primaryFixedDim = Color(0xFFB9B8FF),
    onPrimaryFixed = Color(0xFF000069),
    onPrimaryFixedVariant = Color(0xFF3E3EAE),
    secondaryFixed = Color(0xFFE1E0FA),
    secondaryFixedDim = Color(0xFFC4C2DD),
    onSecondaryFixed = Color(0xFF1A1A32),
    onSecondaryFixedVariant = Color(0xFF4A4A66),
    tertiaryFixed = Color(0xFFFFD8F4),
    tertiaryFixedDim = Color(0xFFE2BAD9),
    onTertiaryFixed = Color(0xFF2D142B),
    onTertiaryFixedVariant = Color(0xFF6E4D6B),
)

// ── 语法高亮配色（Syntax Scheme）────────────────────────────
object SyntaxDark {
    val keyword = Color(0xFFC792EA)
    val string = Color(0xFFC3E88D)
    val comment = Color(0xFF6B8096)
    val number = Color(0xFFF78C6C)
    val function = Color(0xFF82AAFF)
    val variable = Color(0xFFFFCB6B)
    val plain = Color(0xFFE6E1EB)
    val gutter = Color(0xFF6B6975)
    val currentLine = Color(0xFF22212B)
}

object SyntaxLight {
    val keyword = Color(0xFF7C3AED)
    val string = Color(0xFF2E7D32)
    val comment = Color(0xFF8A8A94)
    val number = Color(0xFFE65100)
    val function = Color(0xFF1565C0)
    val variable = Color(0xFFB8860B)
    val plain = Color(0xFF1B1B20)
    val gutter = Color(0xFF9E9E9E)
    val currentLine = Color(0xFFF0EDF7)
}

// ── 个性化配色方案 ─────────────────────────────────────────────
// 每套配色基于「主色种子」生成一组协调的 primary / secondary / tertiary。
// 这里给出浅色 / 深色的关键角色值，其余容器层级沿用品牌中性色，保证 48 色完整。

private data class SeedTriple(
    val primary: Color, val onPrimary: Color,
    val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color,
    val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color,
    val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val inversePrimary: Color,
)

private object Seeds {
    val indigoLight = SeedTriple(
        Color(0xFF4A4AC9), Color(0xFFFFFFFF), Color(0xFFE1E0FF), Color(0xFF000069),
        Color(0xFF5D5D7A), Color(0xFFFFFFFF), Color(0xFFE1E0FA), Color(0xFF1A1A32),
        Color(0xFF775574), Color(0xFFFFFFFF), Color(0xFFFFD8F4), Color(0xFF2D142B),
        Color(0xFFB9B8FF),
    )
    val indigoDark = SeedTriple(
        Color(0xFFB9B8FF), Color(0xFF25256D), Color(0xFF3D3D8A), Color(0xFFDEDCFF),
        Color(0xFFC5C3E3), Color(0xFF2E2E46), Color(0xFF41415E), Color(0xFFE1E0FA),
        Color(0xFFE1BEDF), Color(0xFF442943), Color(0xFF5C3F5A), Color(0xFFFEDAF5),
        Color(0xFF4A4AC9),
    )
    val oceanLight = SeedTriple(
        Color(0xFF0B57D0), Color(0xFFFFFFFF), Color(0xFFD7E3FF), Color(0xFF001B3E),
        Color(0xFF40607E), Color(0xFFFFFFFF), Color(0xFFD7E4F8), Color(0xFF101C2B),
        Color(0xFF006D63), Color(0xFFFFFFFF), Color(0xFFAEEDE3), Color(0xFF00201D),
        Color(0xFFAAC7FF),
    )
    val oceanDark = SeedTriple(
        Color(0xFFAAC7FF), Color(0xFF0A305F), Color(0xFF23458A), Color(0xFFD7E3FF),
        Color(0xFFAEC7E2), Color(0xFF1E3045), Color(0xFF35475F), Color(0xFFD7E4F8),
        Color(0xFF78DACA), Color(0xFF003832), Color(0xFF005048), Color(0xFFAEEDE3),
        Color(0xFF0B57D0),
    )
    val forestLight = SeedTriple(
        Color(0xFF1B7D4F), Color(0xFFFFFFFF), Color(0xFFC6EFD3), Color(0xFF002111),
        Color(0xFF4B6355), Color(0xFFFFFFFF), Color(0xFFD2E8D8), Color(0xFF0D1F14),
        Color(0xFF6D5E2E), Color(0xFFFFFFFF), Color(0xFFF1E4B5), Color(0xFF241C00),
        Color(0xFF84D4A6),
    )
    val forestDark = SeedTriple(
        Color(0xFF84D4A6), Color(0xFF0A3B23), Color(0xFF155C3A), Color(0xFFC6EFD3),
        Color(0xFFB0C7B7), Color(0xFF1C3024), Color(0xFF32483A), Color(0xFFD2E8D8),
        Color(0xFFD7C684), Color(0xFF372E08), Color(0xFF51451A), Color(0xFFF1E4B5),
        Color(0xFF1B7D4F),
    )
    val sunsetLight = SeedTriple(
        Color(0xFFB0410A), Color(0xFFFFFFFF), Color(0xFFFFDBCC), Color(0xFF3D0E00),
        Color(0xFF765647), Color(0xFFFFFFFF), Color(0xFFF0DED5), Color(0xFF2A1710),
        Color(0xFF8A5A00), Color(0xFFFFFFFF), Color(0xFFFFE0A3), Color(0xFF2C1B00),
        Color(0xFFFFB599),
    )
    val sunsetDark = SeedTriple(
        Color(0xFFFFB599), Color(0xFF5E1A00), Color(0xFF8C330C), Color(0xFFFFDBCC),
        Color(0xFFDEC1B4), Color(0xFF3E281F), Color(0xFF573F34), Color(0xFFF0DED5),
        Color(0xFFE7C477), Color(0xFF3E2900), Color(0xFF5A3F00), Color(0xFFFFE0A3),
        Color(0xFFB0410A),
    )
}

/** 用种子三元组覆盖品牌方案的主色角色，保留完整中性色层级。 */
private fun applySeed(base: ColorScheme, s: SeedTriple): ColorScheme = base.copy(
    primary = s.primary,
    onPrimary = s.onPrimary,
    primaryContainer = s.primaryContainer,
    onPrimaryContainer = s.onPrimaryContainer,
    secondary = s.secondary,
    onSecondary = s.onSecondary,
    secondaryContainer = s.secondaryContainer,
    onSecondaryContainer = s.onSecondaryContainer,
    tertiary = s.tertiary,
    onTertiary = s.onTertiary,
    tertiaryContainer = s.tertiaryContainer,
    onTertiaryContainer = s.onTertiaryContainer,
    inversePrimary = s.inversePrimary,
    surfaceTint = s.primary,
    primaryFixed = s.primaryContainer,
    primaryFixedDim = s.primary,
    onPrimaryFixed = s.onPrimaryContainer,
    onPrimaryFixedVariant = s.primary,
    secondaryFixed = s.secondaryContainer,
    secondaryFixedDim = s.secondary,
    onSecondaryFixed = s.onSecondaryContainer,
    onSecondaryFixedVariant = s.secondary,
    tertiaryFixed = s.tertiaryContainer,
    tertiaryFixedDim = s.tertiary,
    onTertiaryFixed = s.onTertiaryContainer,
    onTertiaryFixedVariant = s.tertiary,
)

/** 根据配色方案与明暗模式返回完整 ColorScheme。INDIGO 直接返回品牌原方案。 */
internal fun paletteColorScheme(palette: AppPalette, dark: Boolean): ColorScheme {
    if (palette == AppPalette.INDIGO) return if (dark) DarkColors else LightColors
    val base = if (dark) DarkColors else LightColors
    val seed = when (palette) {
        AppPalette.OCEAN -> if (dark) Seeds.oceanDark else Seeds.oceanLight
        AppPalette.FOREST -> if (dark) Seeds.forestDark else Seeds.forestLight
        AppPalette.SUNSET -> if (dark) Seeds.sunsetDark else Seeds.sunsetLight
        else -> if (dark) Seeds.indigoDark else Seeds.indigoLight
    }
    return applySeed(base, seed)
}
