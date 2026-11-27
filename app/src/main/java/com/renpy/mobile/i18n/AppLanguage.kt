package com.renpy.mobile.i18n

import java.util.Locale

/**
 * 界面语言。
 *
 * 设计要点：
 * - AUTO 表示跟随系统，由 [resolveSystemLanguage] 映射到具体语言；未识别的系统语言回退到简体中文。
 * - 简中作为「源语言」：所有界面的字面文案就是简体中文，字典未命中时原样显示，
 *   因此简中无需翻译包即可工作，也保证了任何翻译包缺失条目时界面永不空白。
 * - SIMPLIFIED / TRADITIONAL 同属中文，分离是为了繁体用户与简体用户的用字习惯。
 */
enum class AppLanguage(
    val code: String,
    /** 用自身语言书写的价格标签，语言选择列表里显示，不需要再翻译。 */
    val nativeName: String,
) {
    AUTO("auto", "跟随系统"),
    SIMPLIFIED("zh-CN", "简体中文"),
    TRADITIONAL("zh-TW", "繁體中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    RUSSIAN("ru", "Русский");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO

        /** 除 AUTO 外、内置了翻译包的语言。 */
        val translatable: List<AppLanguage> =
            listOf(SIMPLIFIED, TRADITIONAL, ENGLISH, JAPANESE, RUSSIAN)
    }
}

/**
 * 根据系统 locale 推断界面语言。
 * - zh + HK/TW/MO 或繁体 script → 繁中；其余 zh → 简中
 * - en / ja / ru 直接匹配
 * - 其他一律回退简中（用户要求「默认支持中文」）
 */
fun resolveSystemLanguage(): AppLanguage {
    val locale = Locale.getDefault()
    return when (locale.language.lowercase(Locale.ROOT)) {
        "zh" -> {
            val region = locale.country.uppercase(Locale.ROOT)
            val script = locale.script
            if (region in setOf("TW", "HK", "MO") || script.contains("Hant", ignoreCase = true)) {
                AppLanguage.TRADITIONAL
            } else {
                AppLanguage.SIMPLIFIED
            }
        }
        "en" -> AppLanguage.ENGLISH
        "ja" -> AppLanguage.JAPANESE
        "ru" -> AppLanguage.RUSSIAN
        else -> AppLanguage.SIMPLIFIED
    }
}
