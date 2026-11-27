package com.renpy.mobile.ai

import com.renpy.mobile.i18n.t

/**
 * 对话上下文压缩器（零联网、纯本地抽取式摘要）。
 *
 * 背景：Agent 多轮执行工具后，messages 里堆积大量 tool 结果与助手文本，
 * 轻易超出模型上下文窗口，导致截断/丢上下文/费用飙升。本类在每轮开始前
 * 估算体积，超过阈值时把「较早的完整轮次」压成一条抽取式摘要消息，
 * 保留系统提示、用户目标、改动文件与最近若干条消息。
 *
 * 关键约束：OpenAI/Claude 要求 assistant(tool_calls) 与其后的 tool 消息
 * 成对出现，压缩必须按「轮次」整体替换，不能拆散，否则 API 报错。
 */
object ContextCompressor {

    /** 触发压缩的体积阈值（按字符粗估，中文约 1.5 字/token，留出安全余量）。 */
    private const val COMPRESS_CHARS = 18_000
    /** 压缩后至少保留的最近消息条数。 */
    private const val RECENT_KEEP = 8

    data class CompressResult(
        val messages: MutableList<LlmClient.Message>,
        val compressed: Boolean,
        val removedTurns: Int,
    )

    /**
     * 在发起新一轮 LLM 请求前调用。若体积超阈值则压缩。
     * @param messages 当前完整消息列表（会被就地修改）
     */
    fun maybeCompress(messages: MutableList<LlmClient.Message>): CompressResult {
        val total = messages.sumOf { (it.content?.length ?: 0) + (it.toolCalls?.toString()?.length ?: 0) }
        if (total <= COMPRESS_CHARS || messages.size <= RECENT_KEEP + 2) {
            return CompressResult(messages, false, 0)
        }

        // find system prompt (role=system, index 0 by convention)
        val systemMsgs = messages.filter { it.role == "system" }
        val rest = messages.filter { it.role != "system" }.toMutableList()

        // 把非 system 消息按「user 起始」切成轮次
        val turns = mutableListOf<MutableList<LlmClient.Message>>()
        var current = mutableListOf<LlmClient.Message>()
        for (m in rest) {
            if (m.role == "user" && current.isNotEmpty()) {
                turns += current
                current = mutableListOf()
            }
            current += m
        }
        if (current.isNotEmpty()) turns += current

        if (turns.size <= 2) return CompressResult(messages, false, 0)

        // 保留最后 RECENT_KEEP 条消息所在轮次及之后，压缩更早的轮次
        var keptCount = 0
        var keepFrom = turns.lastIndex
        for (i in turns.lastIndex downTo 0) {
            keptCount += turns[i].size
            keepFrom = i
            if (keptCount >= RECENT_KEEP) break
        }
        val toCompress = turns.subList(0, keepFrom)
        if (toCompress.isEmpty()) return CompressResult(messages, false, 0)

        val summary = buildSummary(toCompress)
        val result = mutableListOf<LlmClient.Message>()
        result += systemMsgs
        // 摘要作为一条 user 消息插入（跨协议兼容：不作为 system，避免多 system 冲突）
        result += LlmClient.Message("user", summary)
        for (i in keepFrom until turns.size) result += turns[i]

        messages.clear()
        messages += result
        return CompressResult(messages, true, toCompress.size)
    }

    /** 抽取式摘要：保留每轮用户目标、助手结论、改动文件，丢弃冗长 tool 原文。 */
    private fun buildSummary(turns: List<List<LlmClient.Message>>): String {
        val sb = StringBuilder()
        sb.appendLine(t("[对话历史摘要 · 以下内容因上下文长度被压缩]"))
        sb.appendLine(t("以下是此前对话的关键信息，请据此继续当前任务，不要重复询问已明确的需求。"))
        sb.appendLine()
        for ((idx, turn) in turns.withIndex()) {
            val goal = turn.firstOrNull { it.role == "user" }?.content?.trim()
            if (!goal.isNullOrBlank()) {
                sb.appendLine("${idx + 1}. 用户目标：${goal.take(160).replace('\n', ' ')}")
            }
            // 助手结论：取该轮最后一条非空 assistant 文本
            val conclusion = turn.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content?.trim()
            if (!conclusion.isNullOrBlank()) {
                sb.appendLine("   结论：${conclusion.take(200).replace('\n', ' ')}")
            }
            // 工具改动文件（从 tool 消息里提取 changed 字段线索）
            val changed = turn.filter { it.role == "tool" }
                .mapNotNull { extractChanged(it.content) }
                .distinct()
            if (changed.isNotEmpty()) {
                sb.appendLine("   改动文件：${changed.take(20).joinToString(", ")}")
            }
        }
        sb.appendLine()
        sb.appendLine(t("[摘要结束]"))
        return sb.toString()
    }

    private fun extractChanged(content: String?): String? {
        if (content.isNullOrBlank()) return null
        return try {
            val o = org.json.JSONObject(content)
            o.optString("changed").takeIf { it.isNotBlank() }
                ?: o.optJSONObject("result")?.optString("changed")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }
}
