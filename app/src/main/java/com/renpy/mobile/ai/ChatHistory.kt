package com.renpy.mobile.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.renpy.mobile.i18n.t

/**
 * AI 聊天历史持久化。
 *
 * - 多会话：每个会话有 id / 标题 / 消息列表 / 更新时间。
 * - 消息只保存对用户有意义的类型（user / assistant / reasoning / tool 摘要），
 *   临时状态（STATUS / DONE）不入库。
 * - 存 SharedPreferences（org.json 手写，不引入新依赖）；引用内容随消息一起保存。
 */
object ChatHistory {
    enum class Kind { USER, ASSISTANT, REASONING, TOOL, TOOL_DONE, ERROR }

    data class Msg(
        val kind: Kind,
        val text: String,
        val quote: String? = null,
        val ts: Long = System.currentTimeMillis(),
        /** 思考卡片累计耗时（毫秒），随消息持久化。 */
        val thinkMs: Long = 0L,
    )

    data class Session(
        val id: String,
        var title: String,
        val msgs: MutableList<Msg> = mutableListOf(),
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private const val PREF = "ai_chat_history"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_ACTIVE = "active_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<Session> {
        val raw = prefs(context).getString(KEY_SESSIONS, null) ?: return mutableListOf()
        val result = mutableListOf<Session>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val msgs = mutableListOf<Msg>()
                o.optJSONArray("msgs")?.let { ma ->
                    for (j in 0 until ma.length()) {
                        val m = ma.getJSONObject(j)
                        val kind = runCatching { Kind.valueOf(m.optString("kind")) }
                            .getOrDefault(Kind.ASSISTANT)
                        msgs += Msg(
                            kind = kind,
                            text = m.optString("text"),
                            quote = m.optString("quote").takeIf { it.isNotEmpty() },
                            ts = m.optLong("ts"),
                            thinkMs = m.optLong("thinkMs"),
                        )
                    }
                }
                result += Session(
                    id = o.optString("id"),
                    title = o.optString("title").ifEmpty { t("新会话") },
                    msgs = msgs,
                    updatedAt = o.optLong("updatedAt"),
                )
            }
        }
        return result
    }

    fun save(context: Context, sessions: List<Session>) {
        val arr = JSONArray()
        for (s in sessions) {
            val ma = JSONArray()
            for (m in s.msgs) {
                ma.put(JSONObject().apply {
                    put("kind", m.kind.name)
                    put("text", m.text)
                    if (!m.quote.isNullOrEmpty()) put("quote", m.quote)
                    put("ts", m.ts)
                    if (m.thinkMs > 0) put("thinkMs", m.thinkMs)
                })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("msgs", ma)
                put("updatedAt", s.updatedAt)
            })
        }
        prefs(context).edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    fun getActiveId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)

    fun setActiveId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun newSession(): Session = Session(
        id = "s_${System.currentTimeMillis()}_${(0..9999).random()}",
        title = t("新会话"),
    )

    /** 用首条用户消息生成标题。 */
    fun titleFrom(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(18).let { if (it.isEmpty()) t("新会话") else it }
}
