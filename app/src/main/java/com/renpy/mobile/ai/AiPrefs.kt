package com.renpy.mobile.ai

import android.content.Context
import com.renpy.mobile.i18n.t

/**
 * AI 全局偏好：与「服务商档案」无关的显示/行为设置，统一托管在这里。
 *
 * - 正文字号、思考卡片透明度：纯显示偏好；
 * - 思考强度：用户层面的推理深度偏好，对所有服务商生效（AgentLoop 构造 Config 时覆盖）。
 *
 * 之所以把思考强度从档案里抽出来：它是「我想要多深的推理」这一用户意图，
 * 而不是服务商的连接属性；集中到一处后，切换服务商不再需要重复设置。
 *
 * 兼容：旧的 ai_display_prefs 里可能存过 think_card_alpha / font_scale，首次读取时迁移。
 */
object AiPrefs {
    private const val PREFS = "ai_global_prefs"
    private const val LEGACY_PREFS = "ai_display_prefs"
    private const val K_FONT_SCALE = "font_scale"
    private const val K_THINK_ALPHA = "think_card_alpha"
    private const val K_EFFORT = "reasoning_effort"

    val FONT_OPTIONS = listOf(t("小") to 0.8f, t("标准") to 1f, t("大") to 1.2f, t("特大") to 1.4f)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun legacy(c: Context) = c.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    fun getFontScale(c: Context): Float {
        val p = prefs(c)
        val value = if (p.contains(K_FONT_SCALE)) p.getFloat(K_FONT_SCALE, 1f)
        else legacy(c).getFloat("font_scale", 1f)
        return value.coerceIn(0.8f, 1.4f)
    }

    fun setFontScale(c: Context, value: Float) {
        prefs(c).edit().putFloat(K_FONT_SCALE, value.coerceIn(0.8f, 1.4f)).apply()
    }

    fun getThinkAlpha(c: Context): Float {
        val p = prefs(c)
        val value = if (p.contains(K_THINK_ALPHA)) p.getFloat(K_THINK_ALPHA, 0.5f)
        else legacy(c).getFloat("think_card_alpha", 0.5f)
        return value.coerceIn(0.25f, 1f)
    }

    fun setThinkAlpha(c: Context, value: Float) {
        prefs(c).edit().putFloat(K_THINK_ALPHA, value.coerceIn(0.25f, 1f)).apply()
    }

    fun getEffort(c: Context): AiConfig.ReasoningEffort =
        AiConfig.ReasoningEffort.fromName(prefs(c).getString(K_EFFORT, AiConfig.ReasoningEffort.OFF.name))

    fun setEffort(c: Context, effort: AiConfig.ReasoningEffort) {
        prefs(c).edit().putString(K_EFFORT, effort.name).apply()
    }
}
