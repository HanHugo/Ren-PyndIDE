package com.renpy.mobile.i18n

import org.json.JSONObject

/**
 * 一套界面翻译包。
 *
 * 格式（UTF-8 JSON）：
 * {
 *   "code": "en",               // 语言代码，与 AppLanguage.code 对应；自定义包可用任意唯一 code
 *   "name": "English",          // 包显示名
 *   "entries": {
 *     "设置": "Settings",
 *     "编译 {0} 个文件": "Build {0} files"   // {0}{1}... 为占位符，顺序与调用处参数一致
 *   }
 * }
 *
 * key 就是源码里的简体中文原文（t("...") 的参数），未命中的 key 原样显示中文。
 */
data class TranslationPack(
    val code: String,
    val name: String,
    val entries: Map<String, String>,
) {
    companion object {
        const val ASSETS_DIR = "translations"
        const val USER_DIR = "translations"

        /** 从 JSON 字符串解析；格式非法时抛 IllegalArgumentException。 */
        fun fromJson(json: String): TranslationPack {
            val obj = JSONObject(json)
            val code = obj.optString("code", "").trim()
            require(code.isNotEmpty()) { "缺少 code 字段" }
            val name = obj.optString("name", code).trim().ifEmpty { code }
            val entriesObj = obj.optJSONObject("entries") ?: JSONObject()
            val map = LinkedHashMap<String, String>()
            val keys = entriesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = entriesObj.optString(k, k)
            }
            return TranslationPack(code, name, map)
        }

        /** 导出为 JSON（导出模板 / 分享自己做的包）。 */
        fun toJson(pack: TranslationPack, indent: Int = 2): String {
            val obj = JSONObject()
            obj.put("code", pack.code)
            obj.put("name", pack.name)
            val entriesObj = JSONObject()
            // 按 key 排序，便于人工编辑
            pack.entries.toSortedMap().forEach { (k, v) -> entriesObj.put(k, v) }
            obj.put("entries", entriesObj)
            return obj.toString(indent)
        }
    }
}
