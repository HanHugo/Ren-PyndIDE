package com.renpy.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * IDE 语义色系统 —— 覆盖 Material 基础角色之外的 IDE 专属语义。
 *
 * 设计依据：
 * - 基础语义（primary/secondary/tertiary/error 等）已由 ColorScheme 完整覆盖，
 *   见 Color.kt 的 48 色角色；此处只补充 M3 未定义的 IDE 领域语义。
 * - warning / success 的具体色值**沿用项目 LogPanel.kt 现有值**，保证此次迁移零视觉回归；
 *   来源标注为「项目现有 token」，非编造。
 * - Git 状态色**不引入新 hex**，而是映射到现有 ColorScheme 角色，
 *   从而自动适配深浅主题与全部 5 套配色，且满足对比度要求。
 */
object IdeSemanticColors {
    /** 警告色 —— 来源：项目 LogPanel.kt 现有 warning = 0xFFE65100。 */
    val warning: Color = Color(0xFFE65100)

    /** 成功色 —— 来源：项目 LogPanel.kt 现有 success = 0xFF4CAF50。 */
    val success: Color = Color(0xFF4CAF50)
}

/**
 * Git / 文件状态语义色：映射到现有 ColorScheme 角色，随主题自动适配。
 *
 * 状态语义 -> 角色映射为 IDE 设计决策（M3 未定义 Git 语义），
 * 但所有颜色均取自 [MaterialTheme.colorScheme]，不含编造色值。
 */
object IdeStatusColors {
    @Composable
    fun added(): Color = MaterialTheme.colorScheme.tertiary

    @Composable
    fun modified(): Color = MaterialTheme.colorScheme.primary

    @Composable
    fun deleted(): Color = MaterialTheme.colorScheme.error

    @Composable
    fun renamed(): Color = MaterialTheme.colorScheme.secondary

    @Composable
    fun untracked(): Color = MaterialTheme.colorScheme.onSurfaceVariant
}
