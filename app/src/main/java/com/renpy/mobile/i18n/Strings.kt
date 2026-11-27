package com.renpy.mobile.i18n

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * 运行时字符串字典。
 *
 * t() 直接读 [dict]（一个 mutableState）：
 * - 在 Composable 中调用会订阅，语言切换后自动重组、文字即时更新；
 * - 在普通类（编译服务 / 日志 / 消息）中调用也能拿到当前字典快照，不依赖 Compose 上下文。
 *
 * 字典未命中的 key 原样返回简体中文原文，故任何情况下界面都不会出现空白或 key 泄漏。
 */
object Strings {
    private const val PREFS = "renpyndide_prefs"
    private const val KEY_LANGUAGE = "language_code"

    /** 当前语言偏好（AUTO 也存这里，运行时再解析为具体语言）。 */
    private val _language = mutableStateOf(AppLanguage.AUTO)
    val language: AppLanguage get() = _language.value

    /** 当前生效的具体语言（AUTO 已解析）。 */
    private val _effective = mutableStateOf(AppLanguage.SIMPLIFIED)
    val effective: AppLanguage get() = _effective.value

    /** 合并后的最终字典；读取即订阅。 */
    private val _dict = mutableStateOf<Map<String, String>>(emptyMap())
    val dict: Map<String, String> get() = _dict.value

    /** 已加载的包信息，供设置页展示。 */
    private val _loaded = mutableStateOf<List<TranslationPack>>(emptyList())
    val loadedPacks: List<TranslationPack> get() = _loaded.value

    private var appContext: Context? = null

    /** App 启动时调用一次：绑定 context、恢复语言偏好、加载字典。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguage.AUTO.code)
        _language.value = AppLanguage.fromCode(saved)
        reload()
    }

    /** 切换语言并持久化。 */
    fun setLanguage(context: Context, lang: AppLanguage) {
        _language.value = lang
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, lang.code).apply()
        reload()
    }

    /** 重新解析语言并重建字典（导入/删除用户包后也调用）。 */
    fun reload() {
        val ctx = appContext ?: return
        val resolved = if (_language.value == AppLanguage.AUTO) {
            resolveSystemLanguage()
        } else {
            _language.value
        }
        _effective.value = resolved

        // 1) 内置包（简中无包，原文即中文）
        val builtIn = if (resolved == AppLanguage.SIMPLIFIED) {
            null
        } else {
            readAssetPack(ctx, resolved.code)
        }

        // 2) 用户包：文件名 translations/{code}.json，覆盖内置包
        val userPack = readUserPack(ctx, resolved.code)

        val packs = mutableListOf<TranslationPack>()
        if (builtIn != null) packs += builtIn
        if (userPack != null) packs += userPack
        _loaded.value = packs

        // 3) 合并：内置先入，用户包后入覆盖
        val merged = LinkedHashMap<String, String>()
        builtIn?.entries?.let { merged.putAll(it) }
        userPack?.entries?.let { merged.putAll(it) }
        _dict.value = merged
    }

    private fun readAssetPack(ctx: Context, code: String): TranslationPack? = try {
        ctx.assets.open("${TranslationPack.ASSETS_DIR}/$code.json").use { stream ->
            TranslationPack.fromJson(stream.bufferedReader().use { it.readText() })
        }
    } catch (e: Exception) {
        null
    }

    private fun readUserPack(ctx: Context, code: String): TranslationPack? = try {
        val file = File(ctx.filesDir, "${TranslationPack.USER_DIR}/$code.json")
        if (file.exists()) TranslationPack.fromJson(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    /** 用户包目录绝对路径（设置页导入/导出用）。 */
    fun userDir(context: Context): File = File(context.filesDir, TranslationPack.USER_DIR)

    /** 保存用户包（覆盖同 code 的旧包），随后重载字典。 */
    fun saveUserPack(context: Context, pack: TranslationPack): Result<Unit> = try {
        val dir = userDir(context)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "${pack.code}.json").writeText(TranslationPack.toJson(pack))
        reload()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 删除用户包，随后重载字典。 */
    fun deleteUserPack(context: Context, code: String): Result<Unit> = try {
        File(userDir(context), "$code.json").delete()
        reload()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 是否存在用户包。 */
    fun hasUserPack(context: Context, code: String): Boolean =
        File(userDir(context), "$code.json").exists()

    /**
     * 翻译：查字典，未命中返回原文；{0}{1}... 依次替换为参数。
     * 缺参数时占位符原样保留，便于排查。
     */
    fun translate(key: String, vararg args: Any?): String {
        val d = _dict.value
        var result = d[key] ?: key
        args.forEachIndexed { index, value ->
            result = result.replace("{$index}", value.toString())
        }
        return result
    }
}

/**
 * 界面文案翻译入口（普通函数，非 @Composable）。
 * - 内部读取 mutableState：在 Composable 调用链中会订阅，语言切换后自动重组；
 * - 在枚举构造、普通类、后台线程中同样可调用，返回当前字典快照。
 * 因此全项目（含非 Composable 的编译/日志代码）都能统一用 t()。
 */
fun t(key: String, vararg args: Any?): String = Strings.translate(key, *args)

/** 语义同 [t]，别名，用于强调非 Composable 场景（后台服务、日志、消息）。 */
fun tr(key: String, vararg args: Any?): String = Strings.translate(key, *args)
