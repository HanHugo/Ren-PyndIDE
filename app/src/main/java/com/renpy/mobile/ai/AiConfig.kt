package com.renpy.mobile.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.renpy.mobile.i18n.t

/**
 * AI 助手配置持久化 —— 多服务商独立档案。
 *
 * 每个服务商（预设或自定义）拥有独立档案，各自记住自己的 baseUrl / model / format /
 * userAgent / enabled / API Key，互不覆盖；另有「当前激活档案」决定 AgentLoop 用哪一家。
 * 这样解决了「切换服务商后 Key 被共用/覆盖」的问题。
 *
 * 存储：
 * - 档案元信息（name/url/model/format/ua/enabled）明文存私有 SharedPreferences；
 * - 每个档案的 API Key 以「alias + 档案 id」派生的 AES-GCM 密钥加密后存 Base64，
 *   密钥不出安全硬件；不同档案使用不同 alias，物理隔离。
 * - 兼容迁移：检测到旧的单份 KEY_BASE_URL 时，自动转成名为「旧配置」的默认档案。
 */
object AiConfig {
    private const val PREFS = "renpyndide_ai_prefs"
    private const val KEY_PROFILE_IDS = "profile_ids"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    // 旧版单份配置 key（仅用于迁移）
    private const val OLD_BASE_URL = "base_url"
    private const val OLD_MODEL = "model"
    private const val OLD_KEY_ENC = "api_key_enc"
    private const val OLD_ENABLE = "enabled"
    private const val OLD_FORMAT = "api_format"
    private const val OLD_USER_AGENT = "user_agent"

    private const val ALIAS_PREFIX = "renpyndide_ai_key_"

    /** 大模型接入协议。不同协议的端点、请求头、消息/工具结构都不同。 */
    enum class ApiFormat(val label: String, val hint: String) {
        OPENAI(t("OpenAI 兼容"), t("含 DeepSeek / 中转站 / Kimi / 通义 / OpenAI …")),
        ANTHROPIC("Claude", "Anthropic Messages API（x-api-key）"),
        GOOGLE("Gemini", "Google generateContent API"),
    }

    /**
     * 思考强度（推理深度）。不同协议映射不同请求参数：
     * - OpenAI 兼容：reasoning_effort = low/medium/high
     * - Anthropic Claude：thinking.enabled + budget_tokens（思考预算）
     * - Google Gemini：generationConfig.thinkingConfig.thinkingBudget
     *
     * [OFF] 为默认值——不主动下发任何思考参数，完全按模型自身行为，
     * 这样普通模型 / 不支持该参数的中转站不会被 400 拒绝。用户为推理模型按需调高。
     */
    enum class ReasoningEffort(
        val label: String,
        val hint: String,
        val openAiValue: String?,
        val budgetTokens: Int,
    ) {
        OFF(t("默认"), t("按模型自身行为，不额外下发思考参数"), null, 0),
        LOW(t("低"), t("少量思考，响应更快、更省 token"), "low", 1024),
        MEDIUM(t("中"), t("平衡思考深度与速度"), "medium", 4096),
        HIGH(t("高"), t("深度思考，更慢、更耗 token"), "high", 8192),
        ;

        companion object {
            fun fromName(name: String?): ReasoningEffort =
                entries.firstOrNull { it.name == name } ?: OFF
        }
    }

    /** 一个服务商档案。id 稳定唯一，用于 SharedPreferences / Keystore 索引。 */
    data class Profile(
        val id: String,
        val name: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val enabled: Boolean,
        val format: ApiFormat = ApiFormat.OPENAI,
        val userAgent: String = DEFAULT_USER_AGENT,
        val reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
    ) {
        /** 当前工作态：给 LlmClient 用的扁平配置。 */
        fun toConfig(): Config = Config(baseUrl, model, apiKey, enabled, format, userAgent, id, name, reasoningEffort)
    }

    /** 兼容旧调用：LlmClient / AgentLoop 使用的扁平配置。 */
    data class Config(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val enabled: Boolean,
        val format: ApiFormat = ApiFormat.OPENAI,
        val userAgent: String = DEFAULT_USER_AGENT,
        val profileId: String = "",
        val profileName: String = "",
        val reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
    )

    data class Preset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val model: String,
        val format: ApiFormat = ApiFormat.OPENAI,
        val userAgent: String = DEFAULT_USER_AGENT,
    )

    /** 主流中转站普遍放行的编程客户端标识，纯 SDK 默认不带 UA 会被判为「第三方聊天客户端」。 */
    const val DEFAULT_USER_AGENT = "Codex/1.0 (RenPyndIDE; OpenAI-Compatible)"

    /**
     * 中转站 UA 白名单预设（label -> User-Agent）。
     * 很多中转站按 UA 精确匹配放行「编程客户端」，普通 OkHttp/自造 UA 会被 403。
     * 这里列出错误提示中常见的白名单标识，供 UI 一键切换；值尽量贴近各客户端真实 UA。
     */
    val UA_PRESETS: List<Pair<String, String>> = listOf(
        "Claude Code" to "claude-cli/1.0.0 (external, cli)",
        "Codex" to "codex_cli_rs/0.1.0",
        "Cursor" to "Cursor/1.0",
        "Cline" to "Cline/1.0",
        "OpenCode" to "opencode/1.0",
        "WorkBuddy" to "WorkBuddy/1.0",
        "Grok CLI" to "grok-cli/1.0",
    )

    val PRESETS = listOf(
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Preset("xingchi", t("中转站"), "https://api.xingchiapi.com/v1", "gpt-4o-mini",
            userAgent = "Codex/1.0 (RenPyndIDE; OpenAI-Compatible)"),
        Preset("kimi", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        Preset("qwen", t("通义千问"), "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Preset("claude", "Claude", "https://api.anthropic.com/v1", "claude-sonnet-4-5", ApiFormat.ANTHROPIC),
        Preset("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", ApiFormat.GOOGLE),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // 档案 CRUD
    // ------------------------------------------------------------------

    /** 所有档案，按创建顺序。首次使用会从预设初始化，并兼容迁移旧单份配置。 */
    fun listProfiles(context: Context): List<Profile> {
        ensureInitialized(context)
        val ids = profileIds(context)
        return ids.mapNotNull { loadProfile(context, it) }
    }

    fun loadProfile(context: Context, id: String): Profile? {
        val p = prefs(context)
        val name = p.getString(pf(id, "name"), null) ?: return null
        val fmtName = p.getString(pf(id, "format"), null)
        val format = fmtName?.let { runCatching { ApiFormat.valueOf(it) }.getOrNull() }
            ?: ApiFormat.OPENAI
        return Profile(
            id = id,
            name = name,
            baseUrl = p.getString(pf(id, "url"), "") ?: "",
            model = p.getString(pf(id, "model"), "") ?: "",
            apiKey = p.getString(pf(id, "key"), null)?.let { decrypt(context, id, it) } ?: "",
            enabled = p.getBoolean(pf(id, "enabled"), false),
            format = format,
            userAgent = p.getString(pf(id, "ua"), DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT,
            reasoningEffort = ReasoningEffort.fromName(p.getString(pf(id, "effort"), null)),
        )
    }

    /** 新增或更新一个档案（按 id）。 */
    fun saveProfile(context: Context, profile: Profile) {
        val ids = profileIds(context).toMutableList()
        if (profile.id !in ids) ids += profile.id
        val editor = prefs(context).edit()
            .putString(KEY_PROFILE_IDS, ids.joinToString(","))
            .putString(pf(profile.id, "name"), profile.name)
            .putString(pf(profile.id, "url"), profile.baseUrl.trim().trimEnd('/'))
            .putString(pf(profile.id, "model"), profile.model.trim())
            .putBoolean(pf(profile.id, "enabled"), profile.enabled)
            .putString(pf(profile.id, "format"), profile.format.name)
            .putString(pf(profile.id, "ua"), profile.userAgent.trim().ifBlank { DEFAULT_USER_AGENT })
            .putString(pf(profile.id, "effort"), profile.reasoningEffort.name)
        if (profile.apiKey.isNotBlank()) {
            editor.putString(pf(profile.id, "key"), encrypt(context, profile.id, profile.apiKey.trim()))
        }
        editor.apply()
        if (getActiveId(context).isBlank()) setActive(context, profile.id)
    }

    fun deleteProfile(context: Context, id: String) {
        val ids = profileIds(context).filter { it != id }
        val editor = prefs(context).edit().putString(KEY_PROFILE_IDS, ids.joinToString(","))
        listOf("name", "url", "model", "enabled", "format", "ua", "effort", "key").forEach {
            editor.remove(pf(id, it))
        }
        editor.apply()
        // 删除该档案专属的 Keystore 密钥，避免残留。
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(aliasOf(id))
        }
        if (getActiveId(context) == id) {
            setActive(context, ids.firstOrNull() ?: "")
        }
    }

    fun newProfileId(): String = "p_" + System.currentTimeMillis().toString(36)

    fun getActiveId(context: Context): String = prefs(context).getString(KEY_ACTIVE_ID, "") ?: ""

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    /** 当前激活档案，兼容旧调用。 */
    fun load(context: Context): Config {
        ensureInitialized(context)
        val id = getActiveId(context)
        val profile = if (id.isNotBlank()) loadProfile(context, id) else null
        return (profile ?: listProfiles(context).firstOrNull())?.toConfig()
            ?: Config("", "", "", false)
    }

    /** 兼容旧调用：把扁平配置写回当前激活档案（无档案则新建默认）。 */
    fun save(
        context: Context,
        baseUrl: String,
        model: String,
        apiKey: String,
        enabled: Boolean,
        format: ApiFormat = ApiFormat.OPENAI,
        userAgent: String = DEFAULT_USER_AGENT,
        reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
    ) {
        ensureInitialized(context)
        var id = getActiveId(context)
        if (id.isBlank()) {
            id = PRESETS.first().id
            setActive(context, id)
        }
        val old = loadProfile(context, id)
        saveProfile(context, Profile(
            id = id,
            name = old?.name ?: PRESETS.firstOrNull { it.id == id }?.name ?: t("自定义"),
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey.ifBlank { old?.apiKey ?: "" },
            enabled = enabled,
            format = format,
            userAgent = userAgent,
            reasoningEffort = reasoningEffort,
        ))
    }

    fun isConfigured(context: Context): Boolean {
        val c = load(context)
        return c.baseUrl.isNotBlank() && c.apiKey.isNotBlank() && c.model.isNotBlank()
    }

    // ------------------------------------------------------------------
    // 初始化 / 迁移
    // ------------------------------------------------------------------

    private fun profileIds(context: Context): List<String> =
        prefs(context).getString(KEY_PROFILE_IDS, "")
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun ensureInitialized(context: Context) {
        val p = prefs(context)
        if (p.getString(KEY_PROFILE_IDS, "")?.isNotBlank() == true) return

        // 迁移旧单份配置
        val oldUrl = p.getString(OLD_BASE_URL, "") ?: ""
        val oldKeyEnc = p.getString(OLD_KEY_ENC, null)
        if (oldUrl.isNotBlank() || oldKeyEnc != null) {
            val fmtName = p.getString(OLD_FORMAT, null)
            val format = fmtName?.let { runCatching { ApiFormat.valueOf(it) }.getOrNull() }
                ?: ApiFormat.OPENAI
            val legacy = Profile(
                id = "legacy",
                name = t("旧配置"),
                baseUrl = oldUrl,
                model = p.getString(OLD_MODEL, "") ?: "",
                apiKey = oldKeyEnc?.let { decryptLegacy(context, it) } ?: "",
                enabled = p.getBoolean(OLD_ENABLE, false),
                format = format,
                userAgent = p.getString(OLD_USER_AGENT, DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT,
            )
            saveProfile(context, legacy)
            setActive(context, legacy.id)
            return
        }

        // 全新安装：从预设初始化各服务商，各自独立（Key 留空由用户分别填）
        PRESETS.forEach { preset ->
            saveProfile(context, Profile(
                id = preset.id,
                name = preset.name,
                baseUrl = preset.baseUrl,
                model = preset.model,
                apiKey = "",
                enabled = preset.id == PRESETS.first().id,
                format = preset.format,
                userAgent = preset.userAgent,
            ))
        }
        setActive(context, PRESETS.first().id)
    }

    // ------------------------------------------------------------------
    // 加密：每个档案独立 alias（Keystore AES-GCM）
    // ------------------------------------------------------------------

    private fun pf(id: String, field: String): String = "p_${id}_${field}"

    private fun aliasOf(id: String): String = ALIAS_PREFIX + id.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun getKey(context: Context, id: String): SecretKey {
        val alias = aliasOf(id)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(spec)
            generateKey()
        }
        return (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun encrypt(context: Context, id: String, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getKey(context, id)) }
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, id: String, encoded: String): String = try {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getKey(context, id), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    /** 旧版单 alias 解密（迁移用）。 */
    private fun decryptLegacy(context: Context, encoded: String): String = try {
        val alias = "renpyndide_ai_key"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: return ""
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all.copyOfRange(0, 12)))
        }
        String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}
