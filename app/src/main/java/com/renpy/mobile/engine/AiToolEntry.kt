package com.renpy.mobile.engine

import java.io.File
import com.renpy.mobile.i18n.t

/**
 * AI 工具执行入口（app_process 启动的独立进程 main）。
 *
 * 与 CompilerEntry 同理：dlopen librenpython.so 会污染主进程图形栈，
 * 且 vivo 机型上 zygote fork 的进程继承坏锁 → execve 的全新进程最稳。
 * 本类只依赖文件路径，不需要 Android Context，适合纯 ART 进程运行。
 *
 * 工作目录（args[0]）文件协议：
 *  - request.txt : 6 行 = soPath, sysPath, scriptPath, argsPath, outPath, crashPath
 *  - status.txt  : 子进程写 RUNNING → SCRIPT_DONE / 错误
 */
object AiToolEntry {

    @JvmStatic
    fun main(args: Array<String>) {
        println("AITOOL_PROC_STARTED pid=${android.os.Process.myPid()}")
        if (args.isEmpty()) return
        val work = File(args[0])
        val statusFile = File(work, AiToolBridge.F_STATUS)
        try {
            val req = File(work, AiToolBridge.F_REQUEST).readText().lineSequence().toList()
            if (req.size < 6) {
                statusFile.writeText(t("ERROR: 请求参数不完整"))
                return
            }
            val soPath = req[0]
            val sysPath = req[1]
            val scriptPath = req[2]
            val argsPath = req[3]
            val outPath = req[4]
            val crashPath = req[5]
            statusFile.writeText(AiToolBridge.STATUS_RUNNING)
            val result = EngineBridge.nativeRunScript(
                soPath, sysPath, scriptPath, argsPath, outPath, crashPath,
            )
            statusFile.writeText(result)
        } catch (t: Throwable) {
            statusFile.writeText("ERROR: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
