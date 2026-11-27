package com.renpy.mobile.engine

import android.content.Context
import com.renpy.mobile.model.LogLevel
import com.renpy.mobile.project.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import com.renpy.mobile.i18n.t

/**
 * Ren'Py 内置引擎桥接。
 *
 * dlopen RAPT 自带的 arm64 librenpython.so（内部静态链接 CPython 3.12 + _renpy +
 * pygame_sdl2 + SDL2），手动注册全部内置模块后初始化 CPython，直接执行 SDK 的
 * renpy.py <project> compile。彻底绕开 Termux / pkg / dpkg / LD_PRELOAD hook / root
 * 这一整套改包名后的路径权限问题——编译不依赖任何外部 Python 运行时。
 */
object EngineBridge {

    data class CompileResult(
        val success: Boolean,
        val level: LogLevel,
        val message: String,
        val rpycCount: Int,
    )

    /** 从编译输出里提取 "Compiling game/xxx.rpy" 的当前文件名。 */
    private val COMPILE_LINE = Regex("""Compiling\s+(\S+\.rpy)""", RegexOption.IGNORE_CASE)

    init {
        System.loadLibrary("engine_bridge")
    }

    /** 自检：加载引擎、注册模块、初始化 CPython、验证 import。 */
    external fun nativeSelfTest(soPath: String, sysPath: String, outPath: String): String

    /** 真实编译：在内置引擎里执行 renpy.py <project> compile。crashPath 用于写 native 崩溃栈。 */
    external fun nativeCompile(
        soPath: String,
        sysPath: String,
        renpyPyPath: String,
        projectPath: String,
        outPath: String,
        crashPath: String,
    ): String

    /** AI 工具执行：在内置引擎里执行移植后的 renpy-mcp 工具脚本（纯标准库，无需 pip）。 */
    external fun nativeRunScript(
        soPath: String,
        sysPath: String,
        scriptPath: String,
        argsPath: String,
        outPath: String,
        crashPath: String,
    ): String

    /** 引擎 so 是否已随 APK 安装（nativeLibraryDir 里存在）。 */
    fun engineSoPath(context: Context): String? {
        val p = File(context.applicationInfo.nativeLibraryDir, "librenpython.so")
        return if (p.exists()) p.absolutePath else null
    }

    /**
     * SDK 根目录（含 renpy.py 与 renpy/ 包）。
     * SDK 8.6.1 真实结构：filesDir/sdk/8.6.1/sdk/renpy-8.6.1-sdk/
     *   ├── renpy.py          引擎入口
     *   ├── renpy/            renpy Python 包
     *   └── lib/python3.12/   CPython 3.12 标准库（sourceless .pyc）
     */
    fun sdkBase(context: Context): File? {
        val sdkRoot = File(context.filesDir, "sdk")
        return sdkRoot.walkTopDown().firstOrNull { dir ->
            dir.name == "python3.12" && dir.isDirectory && File(dir, "encodings").isDirectory
        }?.parentFile?.parentFile   // python3.12 -> lib -> SDK 根
    }

    fun buildSysPath(context: Context): String? {
        val base = sdkBase(context) ?: return null
        val stdlib = File(base, "lib/python3.12").absolutePath
        val lib = File(base, "lib").absolutePath
        return listOf(stdlib, lib, base.absolutePath).joinToString(":")
    }

    private fun sdkDiagnostic(context: Context): String {
        val sdkRoot = File(context.filesDir, "sdk")
        if (!sdkRoot.exists()) {
            return "未找到 Ren'Py SDK（${sdkRoot.absolutePath}），请先在设置里安装 SDK 核心"
        }
        val py12 = sdkRoot.walkTopDown().count { it.name == "python3.12" && it.isDirectory }
        return "未找到 SDK 标准库 lib/python3.12（python3.12 目录 $py12 个，根 ${sdkRoot.absolutePath}）"
    }

    /** 运行一次自检，返回 (状态, 输出内容)。 */
    fun selfTest(context: Context): Result<Pair<String, String>> = runCatching {
        val soPath = engineSoPath(context)
            ?: return Result.failure(IllegalStateException(t("librenpython.so 未安装（仅 arm64 支持）")))
        val sysPath = buildSysPath(context)
            ?: return Result.failure(IllegalStateException(sdkDiagnostic(context)))
        val out = File(context.cacheDir, "engine-selftest.log")
        val status = nativeSelfTest(soPath, sysPath, out.absolutePath)
        val output = if (out.exists()) out.readText() else t("(无输出)")
        status to output
    }

    /** 统计项目 game/ 下 .rpyc 文件数量。 */
    private fun countRpyc(project: ProjectInfo): Int {
        val game = File(project.path, "game")
        if (!game.exists()) return 0
        return game.walkTopDown().count { it.isFile && it.name.endsWith(".rpyc", ignoreCase = true) }
    }

    /** 统计项目 game/ 下 .rpy 脚本数量（进度分母）。 */
    private fun countRpy(project: ProjectInfo): Int {
        val game = File(project.path, "game")
        if (!game.exists()) return 0
        return game.walkTopDown().count { it.isFile && it.name.endsWith(".rpy", ignoreCase = true) }
    }

    /**
     * 统计"本次编译开始后新写出/重写"的 .rpyc 数量，作为真实进度分子。
     *
     * 为什么不用 rpyc 数量增量：重编译时旧 rpyc 已存在，Ren'Py 会逐个覆盖它们，
     * 数量不变 → 增量恒 0 → 进度永远 0%（这正是之前 0% 的根因之一）。
     * 改用 mtime：凡 mtime >= sinceMs - 容差 的 rpyc，都算本次已编译完成。
     */
    private fun countFreshRpyc(project: ProjectInfo, sinceMs: Long): Int {
        val game = File(project.path, "game")
        if (!game.exists()) return 0
        val threshold = sinceMs - 2000L // 文件系统 mtime 精度容差
        return game.walkTopDown().count { f ->
            f.isFile && f.name.endsWith(".rpyc", ignoreCase = true) && f.lastModified() >= threshold
        }
    }

    /** 从 compile.log 尾部读出当前正在编译的脚本文件名。 */
    private fun lastCompilingFile(log: File): String = runCatching {
        if (!log.exists()) return@runCatching ""
        log.useLines { lines ->
            lines.mapNotNull { COMPILE_LINE.find(it)?.groupValues?.get(1) }.lastOrNull() ?: ""
        }
    }.getOrDefault("")

    /** compile.log 的当前快照：(全部行, Compiling 行数, 最后一条 Compiling 文件)。 */
    private data class LogSnapshot(val lines: List<String>, val compiled: Int, val currentFile: String)

    private fun readLogSnapshot(log: File): LogSnapshot = runCatching {
        if (!log.exists()) return@runCatching LogSnapshot(emptyList(), 0, "")
        val all = log.readLines()
        var n = 0
        var last = ""
        for (raw in all) {
            COMPILE_LINE.find(raw)?.let { n++; last = it.groupValues[1] }
        }
        LogSnapshot(all, n, last)
    }.getOrDefault(LogSnapshot(emptyList(), 0, ""))

    /**
     * 用内置引擎编译项目。所有进度/错误通过 [onLog] 回传，编译进度通过 [onProgress] 回传。
     * onProgress 参数：(已编译数, 总数, 当前脚本, 阶段说明)。
     */
    suspend fun compile(
        context: Context,
        project: ProjectInfo,
        onLog: (LogLevel, String) -> Unit,
        onProgress: (done: Int, total: Int, file: String, phase: String) -> Unit = { _, _, _, _ -> },
    ): CompileResult = withContext(Dispatchers.IO) {
        try {
            val soPath = engineSoPath(context)
                ?: return@withContext CompileResult(false, LogLevel.ERROR, t("内置引擎未安装（仅 arm64）"), 0)
            val base = sdkBase(context)
                ?: return@withContext CompileResult(false, LogLevel.ERROR, sdkDiagnostic(context), 0)
            val sysPath = buildSysPath(context)!!
            val renpyPy = File(base, "renpy.py")
            if (!renpyPy.exists()) {
                return@withContext CompileResult(false, LogLevel.ERROR, t("未找到 SDK 的 renpy.py"), 0)
            }

            val work = CompileService.workDir(context)
            val log = File(work, CompileService.F_LOG)
            val crash = File(work, CompileService.F_CRASH)
            val statusFile = File(work, CompileService.F_STATUS)

            // 回显上一次 native 崩溃栈（上次编译进程若崩，这里能拿到 tombstone）
            if (crash.exists() && crash.length() > 0) {
                val stack = runCatching { crash.readText().trim() }.getOrDefault("")
                if (stack.isNotEmpty()) {
                    onLog(LogLevel.ERROR, t("【上次崩溃栈】上一次编译在独立进程崩溃的信息："))
                    stack.lineSequence().take(40).forEach { onLog(LogLevel.ERROR, "  $it") }
                }
            }

            val before = countRpyc(project)
            val totalRpy = countRpy(project)

            // ── rpy 内容哈希缓存：脚本未变则跳过 native 编译，直接复用已有 rpyc ──
            // 指纹用 SDK 根路径：SDK 升级后自动失效，避免用旧引擎产物。
            val sdkFingerprint = base.absolutePath
            if (CompileCache.hit(project, sdkFingerprint)) {
                val cached = CompileCache.cachedRpycCount(project)
                onProgress(totalRpy, totalRpy, "", t("使用缓存"))
                onLog(LogLevel.INFO, "检测到 ${totalRpy} 个脚本与上次编译完全一致，使用缓存（$cached 个 rpyc）")
                onLog(LogLevel.SUCCESS, t("编译完成（缓存命中，跳过编译）"))
                return@withContext CompileResult(true, LogLevel.SUCCESS, "使用缓存：$cached 个 rpyc 已就绪", cached)
            }

            // 记录本次编译开始时刻：进度分子用"此后新写出的 rpyc 数"（覆盖重编译场景）
            val compileStartMs = System.currentTimeMillis()
            onProgress(0, totalRpy, "", t("准备编译环境"))
            onLog(LogLevel.INFO, "内置引擎开始编译 ${project.name}（独立进程 :compiler）…")

            // 启动独立编译进程（so 的 SDL/EGL 图形栈被隔离，不再污染主进程 UI）
            CompileService.start(context, soPath, sysPath, renpyPy.absolutePath, project.path)

            // 轮询状态直到编译结束（最多 3 分钟），同时实时上报进度。
            // start() 已清空 crash.log，因此本轮它一旦出现内容 = 子进程本次崩溃。
            // 大项目（上千个 rpy）编译可能需要十几分钟，给足 15 分钟；
            // 期间进度条由 Compiling 行数实时驱动，用户能看到编译确实在推进。
            val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
            var status: String
            var tick = 0
            var reported = 0              // 已回传到 UI 的日志行数（增量）
            var lastSnapshot = LogSnapshot(emptyList(), 0, "")
            var lastFresh = -1            // 上一轮新产出的 rpyc 数，用于检测进度增量
            var exitGraceTicks = -1       // 进程消失后的宽限计数（避免文件写入竞态误判）
            while (true) {
                delay(300)
                tick++
                status = runCatching {
                    if (statusFile.exists()) statusFile.readText().trim() else ""
                }.getOrDefault("")
                // 用户取消：立即结束等待并返回取消结果（子进程由 ACTION_CANCEL 自杀回收）
                if (CompileService.isCancelled(context) || status == CompileService.STATUS_CANCELLED) {
                    onLog(LogLevel.WARNING, t("编译已取消"))
                    onProgress(0, totalRpy, "", t("已取消"))
                    return@withContext CompileResult(false, LogLevel.WARNING, t("编译已取消"), 0)
                }
                // 崩溃快速感知：crash.log 有内容立即结束，不再傻等到超时
                if (crash.exists() && crash.length() > 0) {
                    status = t("NATIVE_CRASH: 编译子进程崩溃（见崩溃栈）")
                    break
                }
                // app_process 启动失败快速感知：进程日志若出现 JVM/类加载错误，
                // 说明 execve 出来的编译进程根本没跑起来（既不写 status 也不写 crash），
                // 立即结束并回报，避免空等到 15 分钟超时。
                if (status == CompileService.STATUS_RUNNING) {
                    val plog = File(work, CompileService.F_PROCLOG)
                    if (plog.exists() && plog.length() > 0) {
                        val pt = runCatching { plog.readText() }.getOrDefault("")
                        val lower = pt.lowercase()
                        if (lower.contains("classnotfound") || lower.contains("noclassdeffound") ||
                            lower.contains("could not find") || lower.contains("unable to") ||
                            lower.contains("exception in thread") || lower.contains("java.lang.")
                        ) {
                            status = t("PROC_START_FAIL: 编译进程启动失败（见 process.log）")
                            onLog(LogLevel.ERROR, "【编译进程启动失败】${pt.take(600)}")
                            break
                        }
                    }
                }

                // 实时读编译输出：增量回传新行 + 用 Compiling 行数驱动进度
                lastSnapshot = readLogSnapshot(log)
                val all = lastSnapshot.lines
                if (all.size > reported) {
                    // 只回传新增行里最有信息量的：stage/bridge/错误/compiling。
                    // 上限放宽到 40 行：初始化阶段 native 会连发多条细粒度 stage，
                    // 上限太小会让 reported 越过未显示的行、把关键打点永久吞掉。
                    val fresh = all.drop(reported)
                    var shown = 0
                    for (raw in fresh) {
                        if (shown >= 40) break
                        val line = raw.trim().removeSuffix("\r")
                        if (line.isBlank()) continue
                        val lower = line.lowercase()
                        val interesting = lower.contains("[stage]") || lower.contains("[bridge]") ||
                            lower.contains("error") || lower.contains("traceback") ||
                            lower.contains("exception") || lower.contains("compiling ") ||
                            lower.contains("rpyc") ||
                            // faulthandler 每 15 秒 dump 的 Python 栈，用来定位卡在哪个函数
                            lower.contains("thread") || lower.contains("current thread") ||
                            lower.contains("file \"") || lower.contains("most recent call first")
                        if (interesting) {
                            onLog(
                                if (lower.contains("error") || lower.contains("traceback")) LogLevel.ERROR
                                else LogLevel.INFO,
                                "│ $line".take(300),
                            )
                            shown++
                        }
                    }
                    reported = all.size
                }
                // 进度：Ren'Py 8.6.1 编译时**不打印** "Compiling xxx.rpy"，
                // 旧代码依赖该正则 → 分子恒 0 → 进度永远 0%（看起来像卡死）。
                // 改用"本次编译开始后新写出的 rpyc 数"作分子——rpyc 逐个生成，
                // 是唯一可靠、且能覆盖重编译（旧 rpyc 被覆盖）的真实进度信号。
                val fresh = countFreshRpyc(project, compileStartMs)
                val done = fresh.coerceAtMost(if (totalRpy > 0) totalRpy else Int.MAX_VALUE)
                onProgress(done, totalRpy, "", t("正在编译脚本"))

                // 进度可见性：每产出新的 rpyc 打一条日志，编译期间用户能看到脚本在推进，
                // 不再只盯着静态的 "before PyRun_SimpleFileExFlags" 一行以为卡死。
                if (fresh != lastFresh) {
                    if (fresh > (if (lastFresh < 0) 0 else lastFresh)) {
                        onLog(LogLevel.INFO, "  已编译 $done/$totalRpy 个脚本…")
                    }
                    lastFresh = fresh
                }

                if (status.isNotEmpty() && status != CompileService.STATUS_RUNNING) break

                // 【关键修复】进程存活感知：renpy.py 编译完成若调用 sys.exit()，
                // PyRun_SimpleFileExFlags 会走 CPython 的 handle_system_exit → Py_Exit()，
                // 直接终止整个编译进程，native 侧 569 行之后的 "COMPILE_DONE" 永远写不上，
                // status.txt 卡在 RUNNING，导致「编译完什么也不返回、一直卡着」。
                // 这里直接检测进程是否已退出；退出且 status 仍 RUNNING 时，按实际 rpyc
                // 产出补判结果，不再傻等到 15 分钟超时。
                if (status == CompileService.STATUS_RUNNING &&
                    CompileService.isExternalProcess() &&
                    !CompileService.isCompileProcessAlive()
                ) {
                    if (exitGraceTicks < 0) exitGraceTicks = 0
                    // 宽限 3 个 tick（~0.9s）：native 进程退出瞬间 status.txt 可能还在写，
                    // 给它一点时间落盘，避免竞态把成功误判成失败。
                    if (exitGraceTicks >= 3) {
                        val producedNow = countFreshRpyc(project, compileStartMs)
                        val compiledLines = readLogSnapshot(log).compiled
                        if (producedNow > 0 || compiledLines > 0) {
                            // 有实际产出 → 视为编译成功退出（sys.exit 提前终止了进程）
                            onLog(LogLevel.WARNING, t("编译进程已结束（引擎未写完成状态，通常由 sys.exit 触发）"))
                            status = t("COMPILE_DONE rc=0 (进程退出，按产出补判)")
                        } else {
                            status = t("PROC_EXIT: 编译进程退出但未产出 rpyc 或编译行，可能启动失败或崩溃")
                        }
                        break
                    }
                    exitGraceTicks++
                }
                if (System.currentTimeMillis() > deadline) {
                    status = t("TIMEOUT: 编译超过 15 分钟未完成（请看进度与日志，进度为 0 请查崩溃栈）")
                    break
                }
            }
            onLog(LogLevel.INFO, "【引擎状态】$status")

            // 本次编译若触发了 native 崩溃，回显栈 + faulthandler 抓到的 Python 栈。
            // faulthandler 在 SIGSEGV 时把"崩溃瞬间正在执行的 Python 调用栈"写进 compile.log，
            // 这是定位"崩在 renpy 的哪个函数/哪一行脚本"的关键证据，必须显示出来。
            if (crash.exists() && crash.length() > 0) {
                onLog(LogLevel.ERROR, t("【native 崩溃栈】"))
                runCatching { crash.readText().trim() }.getOrDefault("")
                    .lineSequence().take(40).forEach { onLog(LogLevel.ERROR, "  $it") }
                val pyStack = runCatching { log.readLines() }.getOrDefault(emptyList())
                    .filter { l ->
                        val s = l.trimStart()
                        s.startsWith("Current thread") || s.startsWith("Thread 0x") ||
                            s.startsWith("File \"") || s.startsWith("  File ") ||
                            s.contains("faulthandler") || s.startsWith("Timeout (")
                    }.takeLast(40)
                if (pyStack.isNotEmpty()) {
                    onLog(LogLevel.ERROR, t("【崩溃时 Python 栈（faulthandler）】"))
                    pyStack.forEach { onLog(LogLevel.ERROR, "  ${it.take(300)}") }
                }
            }

            // 解析输出：只认 Ren'Py 真正的编译错误信号。
            // 绝不能宽泛匹配 error/exception/traceback —— 诊断输出、faulthandler 栈、
            // renpy 自身恢复日志都含这些词，之前虚报 42 个错误把成功编译判成失败。
            var errors = 0
            var warnings = 0
            val lines = runCatching { log.readLines() }.getOrDefault(emptyList())
            val errorLines = mutableListOf<String>()
            lines.forEach { raw ->
                val line = raw.trim().removeSuffix("\r")
                if (line.isBlank()) return@forEach
                val lower = line.lowercase()
                // 诊断/引擎/faulthandler 噪音，一律不算错误
                val isNoise = lower.startsWith("[bridge]") || lower.startsWith("[stage]") ||
                    lower.contains("faulthandler") || lower.contains("most recent call first") ||
                    lower.startsWith("thread 0x") || lower.startsWith("file \"") ||
                    lower.startsWith("timeout (") || lower.contains("baseexception") ||
                    lower.contains("except exception") || lower.contains("diag script")
                if (isNoise) return@forEach
                // Ren'Py 真正编译错误的确定性标志
                val isRealError = lower.startsWith("script error") ||
                    lower.contains("script error at") ||
                    lower.startsWith("i'm sorry, but an uncaught exception") ||
                    lower.contains("while running game code") ||
                    lower.startsWith("traceback (most recent call last)")
                when {
                    isRealError -> {
                        errors++
                        errorLines += line.take(300)
                    }
                    lower.contains("warning") -> warnings++
                }
            }
            // 编译中已增量回传过大部分行，这里只补显错误行（去重后最多 30），避免刷屏
            errorLines.distinct().take(30).forEach { text -> onLog(LogLevel.ERROR, text) }
            if (lines.isEmpty()) {
                onLog(LogLevel.WARNING, t("编译输出为空——子进程可能在写出任何内容前就退出了，请看【引擎状态】与崩溃栈"))
            }

            val after = countRpyc(project)
            // 用「本次编译开始后新写出/重写的 rpyc 数」而非总量差：重编译时旧 rpyc 被
            // 覆盖、总量不变，用差值会误判失败。mtime 法覆盖首次与重编译两种场景。
            val produced = countFreshRpyc(project, compileStartMs)
            val statusOk = status.startsWith("COMPILE_DONE")
            // 成功判定以引擎状态 + 实际产出 rpyc 为准。
            // errors 仅作展示：脚本里若有少量可容忍告警不该推翻一次真实成功的编译。
            val success = statusOk && (produced > 0 || (after - before) > 0)
            val finalDone = produced.coerceAtLeast(0)
                .coerceAtMost(if (totalRpy > 0) totalRpy else Int.MAX_VALUE)
            onProgress(finalDone, totalRpy, "", if (success) t("编译完成") else t("编译失败"))

            if (success) {
                // 编译成功：保存哈希快照，下次脚本未变即可命中缓存。
                CompileCache.save(project, sdkFingerprint)
                val msg = "编译完成，生成/更新 $produced 个 .rpyc（共 $after 个）"
                onLog(LogLevel.SUCCESS, msg)
                CompileResult(true, LogLevel.SUCCESS, msg, after)
            } else {
                // 编译失败：旧缓存可能已过期，失效掉，下次强制真编译。
                CompileCache.invalidate(project)
                val msg = when {
                    !statusOk -> "引擎编译未完成：$status"
                    errors > 0 -> "编译失败：$errors 个错误${if (warnings > 0) "，$warnings 个警告" else ""}"
                    produced == 0 -> t("编译结束但未生成 rpyc（可能脚本无改动或编译失败，请看日志）")
                    else -> t("编译结束，请查看日志")
                }
                onLog(LogLevel.ERROR, msg)
                CompileResult(false, LogLevel.ERROR, msg, after)
            }
        } catch (e: Exception) {
            val msg = "引擎编译异常：${e.message ?: "未知错误"}"
            onLog(LogLevel.ERROR, msg)
            onProgress(0, 0, "", t("编译异常"))
            CompileResult(false, LogLevel.ERROR, msg, 0)
        }
    }
}
