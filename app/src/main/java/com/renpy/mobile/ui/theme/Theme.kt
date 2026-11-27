package com.renpy.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.renpy.mobile.ui.AppPalette
import com.renpy.mobile.ui.FontSize

/**
 * 全局主题：Material 3 Expressive。
 * - Android 12+ 动态颜色（Material You 壁纸取色），低版本用品牌静态配色
 * - 配色方案（palette）可在设置中切换；DYNAMIC 走系统取色
 * - 字体大小（fontSize）通过 Density.fontScale 全局缩放所有 sp 文字
 * - 迁移到 MaterialExpressiveTheme：显式传入 colorScheme 保留品牌 48 色，
 *   同时获得 Expressive 组件语义（默认 expressive colorScheme / shapes 由自定义值覆盖）
 * - 显式使用 expressive motionScheme（spring 物理动效 token）
 * - edge-to-edge + 系统栏图标按明暗适配
 *
 * 依据：官方 androidx.compose.material3.MaterialExpressiveTheme 签名
 * (colorScheme, motionScheme, shapes, typography, content)，标记为
 * @ExperimentalMaterial3ExpressiveApi，需 opt-in。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RenPyMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    fontSize: FontSize = FontSize.NORMAL,
    palette: AppPalette = AppPalette.INDIGO,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && palette == AppPalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> paletteColorScheme(palette, darkTheme)
    }

    // 字体大小：缩放 Density.fontScale，作用于全 App 所有 sp 文本
    val density = LocalDensity.current
    val scaledDensity = androidx.compose.ui.unit.Density(
        density = density.density,
        fontScale = density.fontScale * fontSize.scale,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialExpressiveTheme(
            // 显式传入自定义品牌色，避免回退到 expressiveLightColorScheme() 丢失品牌色
            colorScheme = colorScheme,
            typography = Typography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
