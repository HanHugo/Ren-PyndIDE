package com.renpy.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * IDE 圆角 token —— 对齐 M3 Expressive 的 10 级圆角 scale。
 *
 * 数值来源：参考仓库 abhixv/google-m3-expressive-design-skill
 * `skills/m3-expressive/references/tokens.md` → "Corner radius scale — 10 steps"
 * （对应 Compose `androidx.compose.material3.tokens.ShapeTokens` 的 Corner* 值）。
 *
 * 设计原则（本 IDE 特殊约束）：
 * - 编辑器 / 终端 / 文件树 / 日志等**高密度工作区保持低圆角或矩形**，
 *   圆角 token 只用于容器、面板、按钮、浮动元素，不做全局面包块化。
 * - 嵌套圆角遵循 optical roundness：`inner = outer - padding`，避免同心圆失衡。
 */
object IdeShapes {
    /** 0dp —— 矩形，编辑器 / 终端 / 日志行等核心工作区使用。 */
    val None: Shape = RoundedCornerShape(0.dp)

    /** 4dp —— M3 CornerExtraSmall。 */
    val ExtraSmall: Shape = RoundedCornerShape(4.dp)

    /** 8dp —— M3 CornerSmall。 */
    val Small: Shape = RoundedCornerShape(8.dp)

    /** 12dp —— M3 CornerMedium。 */
    val Medium: Shape = RoundedCornerShape(12.dp)

    /** 16dp —— M3 CornerLarge。 */
    val Large: Shape = RoundedCornerShape(16.dp)

    /** 20dp —— M3 CornerLargeIncreased（Expressive 新增）。 */
    val LargeIncreased: Shape = RoundedCornerShape(20.dp)

    /** 28dp —— M3 CornerExtraLarge。 */
    val ExtraLarge: Shape = RoundedCornerShape(28.dp)

    /** 32dp —— M3 CornerExtraLargeIncreased（Expressive 新增）。 */
    val ExtraLargeIncreased: Shape = RoundedCornerShape(32.dp)

    /** 48dp —— M3 CornerExtraExtraLarge（Expressive 新增）。 */
    val ExtraExtraLarge: Shape = RoundedCornerShape(48.dp)

    /** 仅顶部圆角 28dp —— M3 CornerExtraLargeTop，供 BottomSheet / 抽屉使用。 */
    val SheetTop: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}
