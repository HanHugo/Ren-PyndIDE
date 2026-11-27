package com.renpy.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.renpy.mobile.R

// 界面字体：Montserrat；代码字体：JetBrains Mono
val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
)

/**
 * 以官方 Typography() 的 30 个样式为骨架（含 15 个 *Emphasized 变体），
 * 统一替换字体族。Emphasized 变体保留其更高字重，用于 hero/CTA 时刻。
 */
private val base = Typography()

private fun TextStyle.withAppFont(): TextStyle = copy(fontFamily = Montserrat)

val Typography = Typography(
    displayLarge = base.displayLarge.withAppFont(),
    displayMedium = base.displayMedium.withAppFont(),
    displaySmall = base.displaySmall.withAppFont(),
    headlineLarge = base.headlineLarge.withAppFont(),
    headlineMedium = base.headlineMedium.withAppFont(),
    headlineSmall = base.headlineSmall.withAppFont(),
    titleLarge = base.titleLarge.withAppFont(),
    titleMedium = base.titleMedium.withAppFont(),
    titleSmall = base.titleSmall.withAppFont(),
    bodyLarge = base.bodyLarge.withAppFont(),
    bodyMedium = base.bodyMedium.withAppFont(),
    bodySmall = base.bodySmall.withAppFont(),
    labelLarge = base.labelLarge.withAppFont(),
    labelMedium = base.labelMedium.withAppFont(),
    labelSmall = base.labelSmall.withAppFont(),

    // 15 个 Expressive emphasized 变体
    displayLargeEmphasized = base.displayLargeEmphasized.withAppFont(),
    displayMediumEmphasized = base.displayMediumEmphasized.withAppFont(),
    displaySmallEmphasized = base.displaySmallEmphasized.withAppFont(),
    headlineLargeEmphasized = base.headlineLargeEmphasized.withAppFont(),
    headlineMediumEmphasized = base.headlineMediumEmphasized.withAppFont(),
    headlineSmallEmphasized = base.headlineSmallEmphasized.withAppFont(),
    titleLargeEmphasized = base.titleLargeEmphasized.withAppFont(),
    titleMediumEmphasized = base.titleMediumEmphasized.withAppFont(),
    titleSmallEmphasized = base.titleSmallEmphasized.withAppFont(),
    bodyLargeEmphasized = base.bodyLargeEmphasized.withAppFont(),
    bodyMediumEmphasized = base.bodyMediumEmphasized.withAppFont(),
    bodySmallEmphasized = base.bodySmallEmphasized.withAppFont(),
    labelLargeEmphasized = base.labelLargeEmphasized.withAppFont(),
    labelMediumEmphasized = base.labelMediumEmphasized.withAppFont(),
    labelSmallEmphasized = base.labelSmallEmphasized.withAppFont(),
)

// 代码编辑区专用：JetBrains Mono
val CodeTextStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)
