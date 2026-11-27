package com.renpy.mobile.ai

import com.renpy.mobile.ai.AiConfig.ApiFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import com.renpy.mobile.i18n.t

/**
 * 大模型客户端：纯标准库实现（HttpURLConnection + SSE），不引入 OkHttp。
 *
 * 支持三种协议：
 * - [ApiFormat.OPENAI]：Chat Completions，DeepSeek / 中转站 / Kimi / 通义 / OpenAI
 * - [ApiFormat.ANTHROPIC]：Messages API（x-api-key + anthropic-version）
 * - [ApiFormat.GOOGLE]：Gemini generateContent（x-goog-api-key）
 *
 * 关键修复：
 * 1. 中转站 403 client_not_allowed —— 按 User-Agent 白名单放行，补自定义 UA 头；
 * 2. DeepSeek 日志刷 "null" —— 严格过滤 null/"null"/空 content，并兼容非流式响应；
 * 3. URL 归一化 —— 用户粘贴 .../v1/responses 或 .../chat/completions 都能纠正到 .../v1。
 */
object LlmClient {

    /** 当前正在读取的连接，停止生成时主动 disconnect 以打断阻塞中的 readLine。 */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /** 立即打断当前流式请求（连接会被断开，readLine 抛异常退出）。 */
    fun cancelCurrent() {
        activeConn?.disconnect()
    }

    data class Message(
        val role: String,
        val content: String?,
        val toolCalls: JSONArray? = null,
        val toolCallId: String? = null,
        val name: String? = null,
    )

    data class ToolCall(val index: Int, val id: String, val name: String, val arguments: String)

    data class ModelInfo(val id: String, val ownedBy: String? = null)

    data class BalanceInfo(val ok: Boolean, val display: String, val raw: String = "")

    sealed interface StreamEvent {
        data class TextDelta(val text: String) : StreamEvent
        /** 模型的思考/推理过程（deepseek-reasoner reasoning_content、Claude thinking 等）。 */
        data class ReasoningDelta(val text: String) : StreamEvent
        data class ToolCalls(val calls: List<ToolCall>) : StreamEvent
        data class Done(val finishReason: String) : StreamEvent
        data class Error(val message: String) : StreamEvent
    }

    // ------------------------------------------------------------------
    // 聊天主入口
    // ------------------------------------------------------------------

    suspend fun chatStream(
        config: AiConfig.Config,
        messages: List<Message>,
        tools: List<JSONObject>,
        onEvent: (StreamEvent) -> Unit,
        /** 取消令牌：返回 true 时立即中断网络读取与解析，用于「停止生成」。 */
        shouldStop: () -> Boolean = { false },
    ): Unit = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val endpoint = resolveEndpoint(config)
            val body = when (config.format) {
                ApiFormat.OPENAI -> openAiBody(config, messages, tools)
                ApiFormat.ANTHROPIC -> anthropicBody(config, messages, tools)
                ApiFormat.GOOGLE -> googleBody(config, messages, tools)
            }

            conn = openConnection(endpoint, config)
            activeConn = conn
            if (shouldStop()) { onEvent(StreamEvent.Error(t("已停止生成"))); return@withContext }
            applyHeaders(conn, config)
            conn.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readError(conn)
                onEvent(StreamEvent.Error(describeHttpError(code, err)))
                return@withContext
            }

            // 先按 SSE 逐行解析；若整段响应没有 data: 行，视为非流式 JSON 兜底解析。
            val parser = StreamParser(config.format, onEvent)
            var sawDataLine = false
            val rawBuffer = StringBuilder()
            BufferedReader(
                InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)
            ).use { reader ->
                var line: String? = null
                while (!shouldStop() && reader.readLine().also { line = it } != null) {
                    val ln = line ?: break
                    rawBuffer.append(ln).append('\n')
                    val payload = sseData(ln) ?: continue
                    if (payload == "[DONE]") continue
                    sawDataLine = true
                    val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    parser.accept(chunk)
                    if (shouldStop()) break
                }
            }
            // 用户主动停止：不触发 finish()（避免误发射工具调用/正常 Done），直接收尾。
            if (shouldStop()) {
                onEvent(StreamEvent.Error(t("已停止生成")))
                return@withContext
            }

            if (!sawDataLine) {
                // 非流式：整段响应是一个 JSON 对象
                val whole = runCatching { JSONObject(rawBuffer.toString().trim()) }.getOrNull()
                if (whole != null) {
                    if (whole.has("error")) {
                        onEvent(StreamEvent.Error(t("接口返回错误：") + whole.optJSONObject("error")?.optString("message", whole.toString())))
                        return@withContext
                    }
                    parser.acceptWhole(whole)
                } else if (rawBuffer.isNotBlank()) {
                    onEvent(StreamEvent.Error(t("无法解析响应：") + rawBuffer.toString().take(200)))
                    return@withContext
                }
            }

            parser.finish()
        } catch (e: Exception) {
            if (shouldStop()) onEvent(StreamEvent.Error(t("已停止生成")))
            else onEvent(StreamEvent.Error(e.message?.take(300) ?: t("网络请求失败")))
        } finally {
            activeConn = null
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 模型列表
    // ------------------------------------------------------------------

    suspend fun listModels(config: AiConfig.Config): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val base = normalizeBase(config.baseUrl, config.format)
                val url = when (config.format) {
                    ApiFormat.OPENAI, ApiFormat.ANTHROPIC -> base + "/models"
                    ApiFormat.GOOGLE -> base + "/models"
                }
                conn = openConnection(url, config)
                conn.requestMethod = "GET"
                applyHeaders(conn, config, forChat = false)
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext Result.failure(Exception(describeHttpError(code, readError(conn))))
                }
                val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                val root = JSONObject(text)
                val ids = LinkedHashSet<String>()
                when (config.format) {
                    ApiFormat.GOOGLE -> {
                        val arr = root.optJSONArray("models") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val name = str(arr.getJSONObject(i), "name") ?: continue
                            ids += name.removePrefix("models/")
                        }
                    }
                    else -> {
                        val arr = root.optJSONArray("data") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = str(o, "id") ?: continue
                            ids += id
                        }
                    }
                }
                if (ids.isEmpty()) {
                    Result.failure(Exception(t("接口未返回模型列表（可能无权限或格式不同）")))
                } else {
                    Result.success(ids.map { ModelInfo(it) })
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }

    // ------------------------------------------------------------------
    // 余额查询：多端点探测 + JSON 递归提取
    // ------------------------------------------------------------------

    suspend fun fetchBalance(config: AiConfig.Config): BalanceInfo =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(config.baseUrl, config.format)
            val origin = originOf(base)
            // 不同服务商余额端点差异很大，逐一探测。DeepSeek 用 /user/balance；
            // 新 API 中转站常用 /api/user/self；OpenAI 官方用 billing/subscription。
            val candidates = linkedMapOf<String, String>().apply {
                put("$origin/user/balance", "DeepSeek")
                put("$origin/api/user/self", t("中转站"))
                put("$base/dashboard/billing/subscription", "OpenAI")
                put("$origin/v1/dashboard/billing/subscription", "OpenAI")
            }
            val tried = mutableListOf<String>()
            for ((url, kind) in candidates) {
                var conn: HttpURLConnection? = null
                try {
                    conn = openConnection(url, config)
                    conn.requestMethod = "GET"
                    applyHeaders(conn, config, forChat = false)
                    if (conn.responseCode !in 200..299) {
                        tried += "$kind(${conn.responseCode})"
                        continue
                    }
                    val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                    if (text.isBlank()) continue
                    val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    // 有些接口把数据放在 data 字段里
                    val data = root.optJSONObject("data") ?: root
                    val found = findBalanceFields(data)
                    if (found.isNotEmpty()) {
                        val lines = found.entries.take(6).map { (k, v) -> "$k：$v" }
                        return@withContext BalanceInfo(true, lines.joinToString("\n"), text.take(400))
                    }
                    // 兜底：直接展示关键数值字段
                    val fallback = findNumbers(data)
                    if (fallback.isNotEmpty()) {
                        return@withContext BalanceInfo(
                            true,
                            fallback.entries.take(6).map { (k, v) -> "$k：$v" }.joinToString("\n"),
                            text.take(400),
                        )
                    }
                    tried += "$kind(无余额字段)"
                } catch (_: Exception) {
                    tried += "$kind(失败)"
                } finally {
                    conn?.disconnect()
                }
            }
            BalanceInfo(
                false,
                "未能查询到余额。\n该服务商可能不提供公开余额接口，或密钥无相关权限。\n已尝试：${tried.joinToString("、")}",
            )
        }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    private fun openAiBody(config: AiConfig.Config, messages: List<Message>, tools: List<JSONObject>): JSONObject {
        val msgArr = JSONArray()
        for (m in messages) {
            val o = JSONObject().put("role", m.role)
            if (m.content != null) o.put("content", m.content)
            if (m.toolCalls != null) o.put("tool_calls", m.toolCalls)
            if (m.toolCallId != null) o.put("tool_call_id", m.toolCallId)
            if (m.name != null) o.put("name", m.name)
            msgArr.put(o)
        }
        return JSONObject().apply {
            put("model", config.model)
            put("messages", msgArr)
            put("stream", true)
            if (tools.isNotEmpty()) put("tools", JSONArray().apply { tools.forEach { put(it) } })
            put("temperature", 0.3)
            // 思考强度：仅当用户显式选择档位（非 OFF）时下发 reasoning_effort。
            // OpenAI o 系列原生支持；DeepSeek / 多数中转站会忽略该字段，不影响普通模型。
            config.reasoningEffort.openAiValue?.let { put("reasoning_effort", it) }
        }
    }

    private fun anthropicBody(config: AiConfig.Config, messages: List<Message>, tools: List<JSONObject>): JSONObject {
        val system = StringBuilder()
        val msgArr = JSONArray()
        for (m in messages) {
            when (m.role) {
                "system" -> system.append(m.content ?: "").append('\n')
                "tool" -> {
                    // 工具结果在 Anthropic 里必须是 user 消息里的 tool_result 块
                    val block = JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.toolCallId ?: "")
                        .put("content", m.content ?: "")
                    msgArr.put(JSONObject().put("role", "user").put("content", JSONArray().put(block)))
                }
                "assistant" -> {
                    val contentArr = JSONArray()
                    m.content?.takeIf { it.isNotEmpty() }?.let {
                        contentArr.put(JSONObject().put("type", "text").put("text", it))
                    }
                    m.toolCalls?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val tc = arr.getJSONObject(i)
                            val fn = tc.optJSONObject("function") ?: continue
                            contentArr.put(JSONObject()
                                .put("type", "tool_use")
                                .put("id", str(tc, "id") ?: "tool_$i")
                                .put("name", str(fn, "name") ?: "")
                                .put("input", runCatching { JSONObject(str(fn, "arguments") ?: "{}") }.getOrDefault(JSONObject())))
                        }
                    }
                    msgArr.put(JSONObject().put("role", "assistant").put("content", contentArr))
                }
                else -> msgArr.put(JSONObject().put("role", "user").put("content", m.content ?: ""))
            }
        }
        return JSONObject().apply {
            put("model", config.model)
            // Claude 扩展思考：budget_tokens 必须 < max_tokens，且 >= 1024。
            // 按思考强度动态抬高 max_tokens，避免开启思考后预算超限报错。
            val effort = config.reasoningEffort
            val budget = effort.budgetTokens
            val maxTokens = if (budget > 0) maxOf(4096, budget + 2048) else 4096
            put("max_tokens", maxTokens)
            put("stream", true)
            if (budget > 0) {
                put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
            }
            if (system.isNotBlank()) put("system", system.toString().trim())
            put("messages", msgArr)
            if (tools.isNotEmpty()) {
                val t = JSONArray()
                for (tool in tools) {
                    val fn = tool.optJSONObject("function") ?: continue
                    t.put(JSONObject()
                        .put("name", str(fn, "name") ?: "")
                        .put("description", str(fn, "description") ?: "")
                        .put("input_schema", fn.opt("parameters") ?: JSONObject()))
                }
                put("tools", t)
            }
        }
    }

    private fun googleBody(config: AiConfig.Config, messages: List<Message>, tools: List<JSONObject>): JSONObject {
        val system = StringBuilder()
        val contents = JSONArray()
        for (m in messages) {
            when (m.role) {
                "system" -> system.append(m.content ?: "").append('\n')
                "tool" -> {
                    val resp = JSONObject().put("functionResponse", JSONObject()
                        .put("name", m.name ?: "tool")
                        .put("response", JSONObject().put("result", m.content ?: "")))
                    contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(resp)))
                }
                "assistant" -> {
                    val parts = JSONArray()
                    m.content?.takeIf { it.isNotEmpty() }?.let { parts.put(JSONObject().put("text", it)) }
                    m.toolCalls?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val fn = arr.getJSONObject(i).optJSONObject("function") ?: continue
                            parts.put(JSONObject().put("functionCall", JSONObject()
                                .put("name", str(fn, "name") ?: "")
                                .put("args", runCatching { JSONObject(str(fn, "arguments") ?: "{}") }.getOrDefault(JSONObject()))))
                        }
                    }
                    if (parts.length() > 0) contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                else -> contents.put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.content ?: ""))))
            }
        }
        return JSONObject().apply {
            put("contents", contents)
            if (system.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system.toString().trim()))))
            }
            if (tools.isNotEmpty()) {
                val decls = JSONArray()
                for (tool in tools) {
                    val fn = tool.optJSONObject("function") ?: continue
                    decls.put(JSONObject()
                        .put("name", str(fn, "name") ?: "")
                        .put("description", str(fn, "description") ?: "")
                        .put("parameters", fn.opt("parameters") ?: JSONObject()))
                }
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
            }
            val genConfig = JSONObject().put("temperature", 0.3)
            // Gemini 2.5 系列用 thinkingConfig.thinkingBudget 控制思考预算。
            // OFF（默认）档不下发该字段，保持模型原有行为、兼容旧配置；选档后才下发预算。
            // 若需彻底关闭 Gemini 思考，属 2.5 特有行为，未来可另加开关。
            val effort = config.reasoningEffort
            if (effort != AiConfig.ReasoningEffort.OFF) {
                genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", effort.budgetTokens))
            }
            put("generationConfig", genConfig)
        }
    }

    // ------------------------------------------------------------------
    // 流解析
    // ------------------------------------------------------------------

    private class StreamParser(
        private val format: ApiFormat,
        private val onEvent: (StreamEvent) -> Unit,
    ) {
        private val tcAccum = mutableMapOf<Int, MutableToolCall>()
        private var finishReason = "stop"
        /** 本轮已输出的正文，用于拦截"流式吐完又补一整段完整文本"导致的重复。 */
        private val emitted = StringBuilder()

        /**
         * 发射正文片段，带去重。
         * 某些兼容接口（/responses、部分中转站）会在逐字流结束后，再发一个
         * 含完整 message.content 的 chunk；若直接追加，整段话就出现两遍。
         * 规则：长度≥16 且已完整出现在历史里 → 判为重放，丢弃。
         * 逐字流式的小片段（1-10 字）不会命中，正常内容不受影响。
         */
        private fun emitText(t: String) {
            if (t.length >= 16 && emitted.contains(t)) return
            // 整段重放的另一种形态：新片段包含已输出全文且更长（补齐式）
            if (t.length >= 24 && emitted.length >= 16 && t.startsWith(emitted.toString())) {
                // 只追加新增的尾部，而不是整段重放
                val tail = t.substring(emitted.length)
                if (tail.isNotBlank()) {
                    emitted.append(tail)
                    onEvent(StreamEvent.TextDelta(tail))
                }
                return
            }
            emitted.append(t)
            onEvent(StreamEvent.TextDelta(t))
        }

        fun accept(chunk: JSONObject) {
            // 接口错误对象（部分中转站用 200 + error 体）
            if (chunk.has("error")) {
                val msg = chunk.optJSONObject("error")?.optString("message", chunk.toString()) ?: chunk.toString()
                onEvent(StreamEvent.Error("接口错误：$msg"))
                return
            }
            when (format) {
                ApiFormat.OPENAI -> acceptOpenAi(chunk)
                ApiFormat.ANTHROPIC -> acceptAnthropic(chunk)
                ApiFormat.GOOGLE -> acceptGoogle(chunk)
            }
        }

        fun acceptWhole(root: JSONObject) = accept(root)

        private fun acceptOpenAi(chunk: JSONObject) {
            val choices = chunk.optJSONArray("choices") ?: return
            if (choices.length() == 0) return
            val c0 = choices.optJSONObject(0) ?: return
            val delta = c0.optJSONObject("delta") ?: c0.optJSONObject("message") ?: JSONObject()
            cleanText(delta.opt("content"))?.let { emitText(it) }
            // 推理/思考过程：deepseek-reasoner 用 reasoning_content，
            // 部分中转站用 reasoning / reasoning_content。
            val reasoning = cleanText(delta.opt("reasoning_content"))
                ?: cleanText(delta.opt("reasoning"))
            reasoning?.let { onEvent(StreamEvent.ReasoningDelta(it)) }
            delta.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val tc = arr.optJSONObject(i) ?: continue
                    val idx = tc.optInt("index", i)
                    val acc = tcAccum.getOrPut(idx) { MutableToolCall(idx) }
                    str(tc, "id")?.let { acc.id = it }
                    tc.optJSONObject("function")?.let { fn ->
                        str(fn, "name")?.let { acc.name += it }
                        str(fn, "arguments")?.let { acc.arguments += it }
                    }
                }
            }
            str(c0, "finish_reason")?.let { finishReason = it }
        }

        private fun acceptAnthropic(chunk: JSONObject) {
            when (str(chunk, "type")) {
                "content_block_start" -> {
                    val cb = chunk.optJSONObject("content_block") ?: return
                    if (str(cb, "type") == "tool_use") {
                        val idx = chunk.optInt("index", 0)
                        val acc = tcAccum.getOrPut(idx) { MutableToolCall(idx) }
                        str(cb, "id")?.let { acc.id = it }
                        str(cb, "name")?.let { acc.name = it }
                    }
                }
                "content_block_delta" -> {
                    val d = chunk.optJSONObject("delta") ?: return
                    when (str(d, "type")) {
                        "text_delta" -> cleanText(d.opt("text"))?.let { emitText(it) }
                        "thinking_delta", "signature_delta" ->
                            cleanText(d.opt("thinking"))?.let { onEvent(StreamEvent.ReasoningDelta(it)) }
                        "input_json_delta" -> {
                            val idx = chunk.optInt("index", 0)
                            val acc = tcAccum.getOrPut(idx) { MutableToolCall(idx) }
                            str(d, "partial_json")?.let { acc.arguments += it }
                        }
                    }
                }
                "message_delta" -> {
                    chunk.optJSONObject("delta")?.let { str(it, "stop_reason")?.let { r -> finishReason = r } }
                }
                "message_start", "message_stop", "content_block_stop" -> Unit
                else -> {
                    // 非流式兜底：content 数组
                    chunk.optJSONArray("content")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val b = arr.optJSONObject(i) ?: continue
                            when (str(b, "type")) {
                                "text" -> cleanText(b.opt("text"))?.let { emitText(it) }
                                "thinking" -> cleanText(b.opt("thinking"))?.let { onEvent(StreamEvent.ReasoningDelta(it)) }
                                "tool_use" -> {
                                    val acc = tcAccum.getOrPut(i) { MutableToolCall(i) }
                                    str(b, "id")?.let { acc.id = it }
                                    str(b, "name")?.let { acc.name = it }
                                    b.opt("input")?.let { acc.arguments = it.toString() }
                                }
                            }
                        }
                    }
                    str(chunk, "stop_reason")?.let { finishReason = it }
                }
            }
        }

        private fun acceptGoogle(chunk: JSONObject) {
            val candidates = chunk.optJSONArray("candidates") ?: return
            if (candidates.length() == 0) return
            val c0 = candidates.optJSONObject(0) ?: return
            str(c0, "finishReason")?.let { finishReason = it }
            val parts = c0.optJSONObject("content")?.optJSONArray("parts") ?: return
            var idx = tcAccum.size
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                // Gemini 2.5 系列把思考放在 thought=true 的 part 里，不能进正文
                if (p.optBoolean("thought", false)) {
                    cleanText(p.opt("text"))?.let { onEvent(StreamEvent.ReasoningDelta(it)) }
                } else {
                    cleanText(p.opt("text"))?.let { emitText(it) }
                }
                p.optJSONObject("functionCall")?.let { fc ->
                    val acc = tcAccum.getOrPut(idx++) { MutableToolCall(idx - 1) }
                    str(fc, "name")?.let { acc.name = it }
                    fc.opt("args")?.let { acc.arguments = it.toString() }
                }
            }
        }

        fun finish() {
            if (tcAccum.isNotEmpty()) {
                onEvent(StreamEvent.ToolCalls(
                    tcAccum.values.sortedBy { it.index }.map { ToolCall(it.index, it.id, it.name, it.arguments) }
                ))
            }
            onEvent(StreamEvent.Done(finishReason))
        }
    }

    private class MutableToolCall(val index: Int) {
        var id: String = ""
        var name: String = ""
        var arguments: String = ""
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 模型输出里常见的特殊结束符 / 控制 token，需从展示文本里剔除。 */
    private val SPECIAL_TOKENS = listOf(
        "<｜end▁of▁sentence｜>", "<|end▁of▁sentence|>", "<|endoftext|>",
        "<|im_end|>", "<|im_start|>", "<|eot_id|>", "<|end|>",
        "</s>", "<s>", "<pad>", "<unk>", "<mask>",
        "end of text", "[END]", "<END_OF_TEXT>",
    )

    /** 去掉 null / "null" / 空白 / 特殊 token，避免刷屏与乱码。 */
    private fun cleanText(value: Any?): String? {
        if (value == null || value === JSONObject.NULL) return null
        var s = value.toString()
        if (s.isEmpty() || s.equals("null", ignoreCase = true) || s == "none") return null
        for (tok in SPECIAL_TOKENS) {
            s = s.replace(tok, "", ignoreCase = false)
        }
        // 去掉独立的 <think> / </think> / <reasoning> 包裹标签（保留其中文本）
        s = s.replace(Regex("</?think>"), "").replace(Regex("</?reasoning>"), "")
        return s.ifEmpty { null }
    }

    private fun str(o: JSONObject, key: String): String? = cleanText(o.opt(key))

    private fun sseData(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim().ifEmpty { null }
    }

    private fun openConnection(url: String, config: AiConfig.Config): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
        }

    private fun applyHeaders(conn: HttpURLConnection, config: AiConfig.Config, forChat: Boolean = true) {
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("User-Agent", config.userAgent)
        conn.setRequestProperty("Accept", if (forChat) "text/event-stream" else "application/json")
        when (config.format) {
            ApiFormat.OPENAI -> conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            ApiFormat.ANTHROPIC -> {
                conn.setRequestProperty("x-api-key", config.apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            }
            ApiFormat.GOOGLE -> conn.setRequestProperty("x-goog-api-key", config.apiKey)
        }
    }

    /**
     * 解析最终聊天端点 —— 尊重用户输入的地址。
     * - 若用户填的是完整端点（已含 /chat/completions、/responses、/messages、
     *   :generateContent 等路径），原样使用，不再偷偷剥掉再拼 /chat/completions。
     * - 只填了 base（如 https://api.deepseek.com/v1）时，才按协议补默认端点。
     */
    fun resolveEndpoint(config: AiConfig.Config): String {
        val raw = config.baseUrl.trim().trimEnd('/')
        val fullMarkers = listOf(
            "/chat/completions", "/responses", "/messages",
            ":streamGenerateContent", ":generateContent", "/streamGenerateContent",
        )
        // 用户填了完整端点 → 原样返回（保留其大小写与查询串）
        if (fullMarkers.any { raw.contains(it, ignoreCase = true) }) return raw
        // 仅填 base → 按协议补默认端点
        val base = normalizeBase(raw, config.format)
        return endpointFor(config.format, base, config.model)
    }

    /** 供 UI 预览"最终请求地址"，与真实请求完全一致。 */
    fun previewEndpoint(config: AiConfig.Config): String = resolveEndpoint(config)

    private fun normalizeBase(raw: String, format: ApiFormat): String {
        var s = raw.trim().trimEnd('/')
        val suffixes = listOf(
            "/chat/completions", "/responses", "/messages",
            ":streamGenerateContent", ":generateContent", "/streamGenerateContent",
            "/models",
        )
        var changed = true
        while (changed) {
            changed = false
            for (suf in suffixes) {
                if (s.endsWith(suf, ignoreCase = true)) {
                    s = s.removeSuffix(suf).removeSuffix(suf.replaceFirstChar { it.uppercase() }).trimEnd('/')
                    changed = true
                }
            }
        }
        // Google 需要 v1beta 结尾，其他保持 v1
        if (format == ApiFormat.GOOGLE && !s.contains("/v1beta")) {
            if (s.endsWith("/v1")) s = s.removeSuffix("/v1") + "/v1beta"
        }
        return s
    }

    private fun endpointFor(format: ApiFormat, base: String, model: String): String = when (format) {
        ApiFormat.OPENAI -> "$base/chat/completions"
        ApiFormat.ANTHROPIC -> "$base/messages"
        ApiFormat.GOOGLE -> "$base/models/$model:streamGenerateContent?alt=sse"
    }

    private fun originOf(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val slash = noScheme.indexOf('/')
        val hostPart = if (slash >= 0) noScheme.substring(0, slash) else noScheme
        val scheme = if (url.contains("://")) url.substringBefore("://") + "://" else "https://"
        return scheme + hostPart
    }

    private fun readError(conn: HttpURLConnection): String = try {
        conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText()?.take(500) ?: ""
    } catch (_: Exception) { "" }

    private fun describeHttpError(code: Int, body: String): String {
        val msg = runCatching {
            val o = JSONObject(body)
            cleanText(o.optJSONObject("error")?.opt("message"))
                ?: cleanText(o.opt("message"))
                ?: cleanText(o.opt("detail"))
        }.getOrNull()
        val lower = msg?.lowercase() ?: ""
        val hint = when {
            // 余额 / 分组额度不足：UA 已通过白名单，是账户侧问题。
            lower.contains("insufficient") || lower.contains("balance") ||
            lower.contains("余额") || lower.contains("quota") || lower.contains("credit") ->
                "\n（这是中转站账户/分组余额不足，不是客户端问题。请到服务商后台为该分组充值，或改用有余额的模型/分组。）"
            // 白名单拦截：UA 不被认可。
            lower.contains("client") || lower.contains("编程客户端") ||
            lower.contains("not allowed") || lower.contains("ua") ->
                "\n（UA 未被白名单接受：请在服务商配置页打开「编程客户端标识」开关，逐个切换标识后测试。）"
            else -> ""
        }
        return when {
            code == 403 && msg != null -> "HTTP 403：${msg.take(280)}$hint"
            msg != null -> "HTTP $code：$msg"
            body.isNotBlank() -> "HTTP $code：$body${if (code == 403) hint else ""}"
            else -> "HTTP $code"
        }
    }

    private val BALANCE_KEYS = listOf(
        "total_balance", "balance", "remaining_balance", "available_balance",
        "credit", "quota", "remaining_quota", "total_quota", "hard_limit_usd",
        "total", "amount", "money", "balance_infos",
    )

    private fun findBalanceFields(obj: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(o: JSONObject, depth: Int) {
            if (depth > 4) return
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.opt(k) ?: continue
                if (k.contains("balance", true) || k.contains("quota", true) ||
                    k.contains("credit", true) || k.contains("money", true) ||
                    k.contains("remaining", true) || k == "total" || k == "amount"
                ) {
                    val display = when (v) {
                        is JSONObject, is JSONArray -> v.toString().take(160)
                        else -> v.toString().take(160)
                    }
                    if (display.isNotBlank() && display != "null") out[k] = display
                }
                if (v is JSONObject) walk(v, depth + 1)
                if (v is JSONArray) {
                    for (i in 0 until v.length()) if (v.opt(i) is JSONObject) walk(v.getJSONObject(i), depth + 1)
                }
            }
        }
        walk(obj, 0)
        return out
    }

    private fun findNumbers(obj: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k) ?: continue
            if (v is Number && BALANCE_KEYS.any { k.contains(it, true) }) out[k] = v.toString()
        }
        return out
    }
}
