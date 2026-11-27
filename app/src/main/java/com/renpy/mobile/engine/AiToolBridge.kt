package com.renpy.mobile.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import com.renpy.mobile.i18n.t

/**
 * AI 工具桥：把移植后的 renpy-mcp 工具包跑在内置 CPython 的独立进程里。
 *
 * 全链路零联网、零 pip、文件不出设备：
 *  assets/aimcp/ → 首次解压到 filesDir/aimcp/ → app_process 起独立进程 →
 *  nativeRunScript 执行 aimcp_entry.py → 读 AIMCP_RESULT JSON。
 */
object AiToolBridge {

    const val DIR = "engine-aitool"
    const val F_REQUEST = "request.txt"
    const val F_ARGS = "args.json"
    const val F_RESULT = "result.json"
    const val F_CRASH = "crash.log"
    const val F_STATUS = "status.txt"
    const val F_PROCLOG = "process.log"
    const val STATUS_RUNNING = "RUNNING"

    data class ToolSchema(val name: String, val description: String, val inputSchema: JSONObject)
    data class ToolResult(
        val ok: Boolean,
        val result: JSONObject?,
        val rawResult: String?,
        val changed: String?,
        val error: String?,
        val tool: String?,
    )

    /** 工具进程（execve 出来的），供超时/取消时终止。 */
    @Volatile
    private var toolProcess: Process? = null

    private val lock = Any()

    fun workDir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    /** 已解压的工具包根目录（filesDir/aimcp，含 renpy_mcp/ 与 mcp/ 与入口）。 */
    fun toolHome(context: Context): File = File(context.filesDir, "aimcp")

    /**
     * 首次使用时把 assets/aimcp/ 递归解压到 filesDir/aimcp/。
     * 用版本标记判断是否已解压，SDK/App 升级后可强制刷新。
     */
    private fun ensureExtracted(context: Context): File {
        val home = toolHome(context)
        val versionFile = File(home, ".version")
        val version = "2"  // v2: list 时空 project_root 回退占位路径，修复工具列不出来的问题
        if (versionFile.exists() && versionFile.readText().trim() == version &&
            File(home, "aimcp_entry.py").exists() && File(home, "renpy_mcp").isDirectory
        ) return home

        synchronized(this) {
            if (versionFile.exists() && versionFile.readText().trim() == version &&
                File(home, "aimcp_entry.py").exists()) return home
            home.deleteRecursively()
            home.mkdirs()
            copyAssets(context, "aimcp", home)
            versionFile.writeText(version)
        }
        return home
    }

    private fun copyAssets(context: Context, assetPath: String, dest: File) {
        val items = context.assets.list(assetPath) ?: emptyArray()
        if (items.isEmpty()) {
            // 是文件
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            return
        }
        // 是目录
        dest.mkdirs()
        for (item in items) {
            val child = if (assetPath.isEmpty()) item else "$assetPath/$item"
            copyAssets(context, child, File(dest, item))
        }
    }

    /** 静态 schema 的内存缓存（从 assets 读取一次）。 */
    @Volatile
    private var cachedSchemas: List<ToolSchema>? = null

    /**
     * 列出全部工具 schema（供 LLM 生成 tool_calls 定义）。
     *
     * 关键：工具清单必须与引擎/SDK 是否安装解耦。旧实现走 Python 进程 list，
     * 而 runTool 会检查 SDK 标准库——用户没装全 SDK 时 list 直接失败返回空，
     * LLM 就只看到 ask_user 一个工具（截图里 AI 说"我只有一个工具"的根因）。
     * 现在优先从构建期导出的 assets/aimcp/tools_schema.json 读取，100% 可靠；
     * 真正执行工具（call）时才需要引擎 + SDK。
     */
    suspend fun listTools(context: Context): List<ToolSchema> = withContext(Dispatchers.IO) {
        cachedSchemas?.let { return@withContext it }
        val tools = mutableListOf<ToolSchema>()
        // 1) 静态 schema（主路径）
        runCatching {
            context.assets.open("aimcp/tools_schema.json").use { input ->
                val arr = org.json.JSONArray(input.bufferedReader().readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tools += ToolSchema(
                        name = o.optString("name"),
                        description = o.optString("description"),
                        inputSchema = o.optJSONObject("inputSchema") ?: JSONObject(),
                    )
                }
            }
        }
        // 2) 静态读取失败才回退 Python list（兼容未来动态工具）
        if (tools.isEmpty()) {
            val parsed = runTool(context, JSONObject().apply {
                put("project_root", "")
                put("action", "list")
            })
            parsed.result?.optJSONArray("tools")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tools += ToolSchema(
                        name = o.optString("name"),
                        description = o.optString("description"),
                        inputSchema = o.optJSONObject("inputSchema") ?: JSONObject(),
                    )
                }
            }
        }
        if (tools.isNotEmpty()) cachedSchemas = tools
        tools
    }

    /** 调用一个工具。projectPath 为项目根目录（含 game/）。 */
    suspend fun call(
        context: Context,
        projectPath: String,
        tool: String,
        arguments: JSONObject,
    ): ToolResult = withContext(Dispatchers.IO) {
        val args = JSONObject().apply {
            put("project_root", projectPath)
            put("sdk_root", EngineBridge.sdkBase(context)?.absolutePath ?: projectPath)
            put("action", "call")
            put("tool", tool)
            put("arguments", arguments)
        }
        runTool(context, args).copy(tool = tool)
    }

    /**
     * 执行一次工具脚本，返回解析后的 AIMCP_RESULT。
     * 串行化：Agent Loop 保证串行，这里再加锁防止并发起多个进程互相覆盖工作目录。
     */
    private fun runTool(context: Context, args: JSONObject): ToolResult = synchronized(lock) {
        val d = workDir(context)
        // 清理上一轮产物
        listOf(F_REQUEST, F_RESULT, F_CRASH, F_STATUS, F_PROCLOG)
            .forEach { File(d, it).delete() }

        val soPath = EngineBridge.engineSoPath(context)
            ?: return ToolResult(false, null, null, null, t("内置引擎未安装（仅 arm64）"), null)
        val sysPath = EngineBridge.buildSysPath(context)
            ?: return ToolResult(false, null, null, null, t("未找到 SDK 标准库"), null)
        val home = ensureExtracted(context)
        val scriptPath = File(home, "aimcp_entry.py").absolutePath
        if (!File(scriptPath).exists())
            return ToolResult(false, null, null, null, t("工具包入口缺失"), null)

        // 写参数文件
        val argsPath = File(d, F_ARGS).absolutePath
        File(argsPath).writeText(args.toString())
        val outPath = File(d, F_RESULT).absolutePath
        val crashPath = File(d, F_CRASH).absolutePath

        // request.txt：6 行参数
        File(d, F_REQUEST).writeText(
            listOf(soPath, sysPath, scriptPath, argsPath, outPath, crashPath).joinToString("\n")
        )
        File(d, F_STATUS).writeText(STATUS_RUNNING)

        // 启动 execve 进程
        val started = tryStartExternalProcess(context, d)
        if (!started) {
            return ToolResult(false, null, null, null, t("AI 工具进程启动失败"), null)
        }

        // 轮询等待（工具通常秒级，给 60 秒上限）
        val deadline = System.currentTimeMillis() + 60_000L
        var status = STATUS_RUNNING
        var exitGrace = -1
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(120)
            status = runCatching {
                if (File(d, F_STATUS).exists()) File(d, F_STATUS).readText().trim() else ""
            }.getOrDefault("")

            // 崩溃快速感知
            val crash = File(d, F_CRASH)
            if (crash.exists() && crash.length() > 0) {
                val stack = runCatching { crash.readText().take(500) }.getOrDefault("")
                return ToolResult(false, null, null, null, "native 崩溃：$stack", null)
            }
            // 完成
            if (status.isNotEmpty() && status != STATUS_RUNNING) break
            // 进程退出但没写 status（sys.exit 提前终止）→ 宽限后按 result.json 判定
            if (!isProcessAlive()) {
                if (exitGrace < 0) exitGrace = 0
                if (exitGrace >= 3) {
                    if (File(d, F_RESULT).exists() && File(d, F_RESULT).length() > 0) {
                        status = t("SCRIPT_DONE rc=0 (进程退出补判)")
                    } else {
                        status = t("PROC_EXIT: 进程退出但未产出结果")
                    }
                    break
                }
                exitGrace++
            }
        }

        toolProcess?.destroy()
        toolProcess = null

        if (status == STATUS_RUNNING) {
            return ToolResult(false, null, null, null, t("工具执行超时（60s）"), null)
        }
        if (!status.startsWith("SCRIPT_DONE") && !status.startsWith("PROC_EXIT")) {
            return ToolResult(false, null, null, null, "工具执行失败：$status", null)
        }

        // 解析 result.json 里的 AIMCP_RESULT 行
        val out = File(d, F_RESULT)
        if (!out.exists()) return ToolResult(false, null, null, null, t("无结果输出"), null)
        val line = out.readLines().firstOrNull { it.startsWith("AIMCP_RESULT:") }
            ?: return ToolResult(false, null, out.readText().take(800), null, t("结果格式异常（缺少 AIMCP_RESULT）"), null)
        return try {
            val json = JSONObject(line.removePrefix("AIMCP_RESULT:"))
            val ok = json.optBoolean("ok", false)
            val result = json.optJSONObject("result")
            val raw = when (val r = json.opt("result")) {
                is String -> r
                is JSONObject -> r.toString(2)
                else -> null
            }
            ToolResult(
                ok = ok,
                result = result,
                rawResult = raw,
                changed = json.optString("changed").takeIf { it.isNotEmpty() },
                error = json.optString("error").takeIf { it.isNotEmpty() },
                tool = json.optString("tool").takeIf { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            ToolResult(false, null, line.take(800), null, "结果解析失败：${e.message}", null)
        }
    }

    private fun tryStartExternalProcess(context: Context, d: File): Boolean = try {
        val apk = context.applicationInfo.sourceDir
        val libDir = context.applicationInfo.nativeLibraryDir
        val cmd = listOf(
            "/system/bin/app_process",
            "-Djava.class.path=$apk",
            "-Djava.library.path=$libDir",
            context.applicationInfo.dataDir,
            AiToolEntry::class.java.name,
            d.absolutePath,
        )
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(File(d, F_PROCLOG))
        pb.environment()["CLASSPATH"] = apk
        toolProcess = pb.start()
        true
    } catch (t: Throwable) {
        runCatching { File(d, F_PROCLOG).writeText("APP_PROC_START_FAIL: ${t.javaClass.simpleName}: ${t.message}\n") }
        false
    }

    private fun isProcessAlive(): Boolean {
        val p = toolProcess ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    /** 取消当前工具执行。 */
    fun cancel() {
        toolProcess?.destroy()
        toolProcess = null
    }
}
