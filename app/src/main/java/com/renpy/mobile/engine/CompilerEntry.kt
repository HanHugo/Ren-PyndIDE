package com.renpy.mobile.engine

import java.io.File
import com.renpy.mobile.i18n.t

/**
 * 编译入口（app_process 启动的独立进程 main）。
 *
 * 为什么需要它：
 *  vivo 等机型上，编译跑在 zygote fork 出的 :compiler Service 进程时，
 *  libvivo_runtime.so / libgui.so 的 android::LoadWareManager::getInstance()
 *  内部 mutex 处于继承来的锁死状态，renpy 编译 dlopen 图形相关库时触发
 *  std::mutex::lock → pthread_mutex_lock → abort(SIGABRT)。
 *
 * app_process 是通过 ProcessBuilder fork+execve 出来的全新进程：execve 会
 * 清空进程内存、重新初始化所有静态变量与 mutex，因此锁状态是干净的，
 * 不再继承 zygote 的坏锁。本类只依赖文件路径，不需要 Android Context，
 * 适合在纯 ART 进程里运行。
 */
object CompilerEntry {

    @JvmStatic
    fun main(args: Array<String>) {
        // 启动标记：写到 process.log（stdout 被重定向到这里）。主进程据此确认
        // execve 成功且类已加载；若 process.log 为空 = 进程根本没起来（可能被 ROM 拦截）。
        println("COMPILER_PROC_STARTED pid=${android.os.Process.myPid()}")
        if (args.isEmpty()) return
        val work = File(args[0])
        val statusFile = File(work, CompileService.F_STATUS)
        val crashFile = File(work, CompileService.F_CRASH)
        val logFile = File(work, CompileService.F_LOG)
        try {
            val req = File(work, CompileService.F_REQUEST).readText().lineSequence().toList()
            if (req.size < 4) {
                statusFile.writeText(t("ERROR: 请求参数不完整"))
                return
            }
            val (soPath, sysPath, renpyPy, project) = req
            val result = EngineBridge.nativeCompile(
                soPath, sysPath, renpyPy, project,
                logFile.absolutePath, crashFile.absolutePath,
            )
            statusFile.writeText(result)
        } catch (t: Throwable) {
            statusFile.writeText("ERROR: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
