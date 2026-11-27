package com.renpy.mobile.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import kotlin.math.roundToInt

/**
 * 管理多个 Termux 会话，并构造启动 bash 所需的环境变量。
 *
 * 关键：Termux bootstrap 的脚本 shebang 硬编码了 /data/data/com.termux 路径，
 * 通过官方 termux-exec（LD_PRELOAD）+ TERMUX__PREFIX 指向我们的私有目录，
 * 让 termux-exec 在 exec 时把这些 shebang 重映射到实际安装位置。
 *
 * 注：Ren'Py 编译走内置 librenpython.so 引擎（见 EngineBridge），与这里无关；
 * 自制的 libpathremap hook 已移除，LD_PRELOAD 只保留官方 termux-exec。
 */
class TermuxSessionManager(private val context: Context) {

    /** Termux 前缀（PREFIX）绝对路径。 */
    fun prefix(): String = TermuxBootstrap.prefixDir(context).absolutePath

    /** HOME 绝对路径。 */
    fun home(): String = TermuxBootstrap.homeDir(context).absolutePath

    /**
     * 构造一组 Termux 会话共用的环境变量。
     *
     * 关键：termux-exec 靠以下输入变量把脚本 shebang / 代码里硬编码的
     * /data/data/com.termux 重定位到本 App 的实际路径。少一个都会退回编译期
     * 默认值 com.termux，导致 "bad interpreter: .../com.termux/.../sh"。
     */
    fun buildEnv(): Array<String> {
        val prefix = prefix()
        val home = home()
        val shell = "$prefix/bin/bash"
        val dataDir = context.dataDir.absolutePath                       // /data/user/0/com.renpy.mobile
        // termux-exec 依赖 LEGACY_DATA_DIR 把脚本 shebang 里「写死的旧路径」重定位到 dataDir。
        // bootstrap 里 101 个脚本的 shebang 硬编码的是官方 Termux 的 /data/data/com.termux，
        // 因此这里必须固定填 com.termux（不是我们的包名！），termux-exec 才能匹配并重写成
        // dataDir。填成 com.renpy.mobile 会导致匹配失败 → shebang 不改写 →
        // 内核直接执行 /data/data/com.termux/... → "bad interpreter: Permission denied"。
        val legacyDataDir = "/data/data/com.termux"
        val seContext = readSelfSeContext()
        val env = mutableListOf(
            "RENPY_REAL_DATA_DIR=$dataDir",
            "TERMUX__PREFIX=$prefix",
            // apt/dpkg 把 /data/data/com.termux/files/usr 硬编码进二进制，LD_PRELOAD 只改 exec 层，
            // 管不到它们内部 open() 配置目录。显式给出 apt 主配置与 dpkg 数据库目录，让它们在
            // 本 App 的私有 prefix 下也能工作（apt.conf 由 TermuxBootstrap 解压时生成）。
            "TERMUX_PREFIX=$prefix",
            "APT_CONFIG=$prefix/etc/apt/apt.conf",
            "DPKG_ADMINDIR=$prefix/var/lib/dpkg",
            "TERMUX_APP__DATA_DIR=$dataDir",
            "TERMUX_APP__LEGACY_DATA_DIR=$legacyDataDir",
            "PREFIX=$prefix",
            "HOME=$home",
            "TERMUX_VERSION=0.118.3",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PATH=$prefix/bin:$prefix/bin/applets:$prefix/sbin",
            "LD_LIBRARY_PATH=$prefix/lib",
            // 仅加载官方 termux-exec：在 exec 时重写脚本 shebang 里的旧路径。
            // libtermux-exec.so 是指向 libtermux-exec-ld-preload.so 的符号链接。
            "LD_PRELOAD=$prefix/lib/libtermux-exec.so",
            "TMPDIR=$prefix/tmp",
            "SHELL=$shell",
            "USER=termux",
            "LOGNAME=termux",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "EXTERNAL_STORAGE=/sdcard",
            "TERMUX_APP__PACKAGE_NAME=${context.packageName}",
            // system_linker_exec 用官方标准值 enable（仅 Android>=10 且非豁免上下文时启用）。
            "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=enable",
        )
        if (seContext.isNotEmpty()) env.add("TERMUX__SE_PROCESS_CONTEXT=$seContext")
        return env.toTypedArray()
    }

    /** 构造一个 Termux 会话（尚未启动 emulator，需 attach 到 TerminalView 时初始化尺寸）。 */
    fun createSession(name: String, client: TerminalSessionClient): TerminalSession {
        val prefix = prefix()
        val home = home()
        val shell = "$prefix/bin/bash"

        // 关键 1：Termux 的 JNI execvp(cmd, argv) 里 argv[0] 由调用方显式提供。
        // 必须把 shellPath 作为 argv[0]，否则 bash 会把参数当成自己的进程名
        // （提示符变成 "-l-5.3$"）。
        //
        // 关键 2：不用 login shell（-l）。login shell 会让 bash 读 /etc/profile，
        // 而该路径在 bootstrap 里硬编码为 /data/data/com.termux/...，且 profile 是
        // bash 内部 open() 的，termux-exec 的 execve 拦截管不到 → Permission denied。
        // 所需的 TERM/PATH/HOME/PREFIX 已全部通过 env 传入，stdin 是 PTY，
        // bash 会自动进入交互模式，无需 login shell。
        val session = TerminalSession(
            /* shellPath = */ shell,
            /* cwd       = */ home,
            // argv[0]=shell；--norc 跳过 bash 内部打开的 /etc/bash.bashrc
            //（该路径硬编码为 com.termux，termux-exec 的 execve 拦截管不到）。
            /* args      = */ arrayOf(shell, "--norc"),
            /* env       = */ buildEnv(),
            /* transcriptRows = */ null,
            /* client    = */ client,
        )
        session.mSessionName = name
        return session
    }

    /**
     * 构造一个「一次性命令」会话：用 bash -c 执行 [script] 后退出。
     * 用于后台编译等场景——复用与交互终端完全相同的 Termux 环境（LD_PRELOAD / PREFIX 等）。
     * 调用方负责 session.start()，并用 [TerminalSessionClient.onSessionFinished] 得知结束。
     */
    fun createCommandSession(name: String, script: String, client: TerminalSessionClient): TerminalSession {
        val prefix = prefix()
        val home = home()
        val shell = "$prefix/bin/bash"
        val session = TerminalSession(
            /* shellPath = */ shell,
            /* cwd       = */ home,
            /* args      = */ arrayOf(shell, "--norc", "-c", script),
            /* env       = */ buildEnv(),
            /* transcriptRows = */ null,
            /* client    = */ client,
        )
        session.mSessionName = name
        return session
    }

    /**
     * 后台执行一次命令（不依赖 TerminalView / PTY），返回进程退出码。
     * 环境变量与交互终端完全一致（LD_PRELOAD / PREFIX / PATH 等），因此 Termux 的
     * shebang 重映射照常生效。stdout/stderr 合并写入 [logFile]（追加）。
     * 适用于工具链安装、APK 打包等非交互命令。
     */
    fun execBackground(script: String, logFile: File, append: Boolean = false): Int {
        val shell = "${prefix()}/bin/bash"
        val pb = ProcessBuilder(shell, "--norc", "-c", script)
        pb.environment().clear()
        buildEnv().forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
        }
        pb.directory(homeDirFile())
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        val p = pb.start()
        val code = p.waitFor()
        try { p.destroy() } catch (_: Exception) {}
        return code
    }

    private fun homeDirFile(): File = TermuxBootstrap.homeDir(context).apply { mkdirs() }

    /** 读取当前进程的 SELinux context（termux-exec 判断能否用 system_linker_exec 用）。 */
    private fun readSelfSeContext(): String = try {
        File("/proc/self/attr/current").readText().trim('\n', '\u0000')
    } catch (_: Exception) {
        ""
    }
}

/** 一个轻量、可复用的 TerminalSessionClient 默认实现，UI 层可覆写需要的回调。 */
open class SimpleTerminalSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

/**
 * 会话级 client：PTY 有输出时系统唯一通过 [onTextChanged] 通知界面。
 * 这里必须触发 View 重绘，否则 emulator 状态更新了但屏幕不刷新
 * （表现为"打字/命令输出不显示，只有缩放时才刷新"）。
 * view 在 TerminalView 创建后由 UI 层回填。回调均在主线程执行，可直接操作 View。
 */
class RedrawingSessionClient : SimpleTerminalSessionClient() {
    var view: TerminalView? = null
    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }
}

/** TerminalViewClient 实现：处理输入法、捏合缩放、按键、剪贴板。 */
class TerminalViewClientImpl(
    private val context: Context,
    private val view: TerminalView,
    private val onBell: () -> Unit = {},
) : TerminalViewClient {

    companion object {
        private const val BASE_TEXT_SIZE = 16   // 默认字号（dp）
        private const val MIN_TEXT_SIZE = 8
        private const val MAX_TEXT_SIZE = 40
    }

    // 当前字号，捏合缩放时在此基础上增减，避免与模块内部 scaleFactor 双重累积。
    private var textSize = BASE_TEXT_SIZE

    /** 进入终端时弹一次软键盘，保证第一次就能输入。 */
    fun showKeyboard() {
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, 0)
    }

    /** 单击弹软键盘——官方 Termux 就是在这里拉起输入法。 */
    override fun onSingleTapUp(e: MotionEvent?) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, 0)
    }

    /**
     * 捏合缩放。[scale] 是模块内部累积的 mScaleFactor（每帧 *= 本次增量后传入）。
     * 必须原样返回让它继续累积，否则下一帧又从 1 开始，字号永远在基准附近抖动。
     * 该精简版 TerminalView 渲染时不用 mScaleFactor，所以我们据此直接调字号，
     * 并强制重绘——否则 updateSize() 只在行列数变化时才 invalidate，会导致屏幕不刷新、
     * 输入的字符回显也看不见。
     */
    override fun onScale(scale: Float): Float {
        val newSize = (BASE_TEXT_SIZE * scale).roundToInt().coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        if (newSize != textSize) {
            textSize = newSize
            view.setTextSize(newSize)   // 重建字体 + updateSize()
            view.postInvalidate()        // 强制重绘，保证缩放与输入回显都即时可见
        }
        return scale                     // 交还累积值，供下一帧继续放大/缩小
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}
