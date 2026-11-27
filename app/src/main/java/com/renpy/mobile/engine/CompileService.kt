package com.renpy.mobile.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import com.renpy.mobile.i18n.t

/**
 * 在独立进程 :compiler 中执行 Ren'Py 编译。
 *
 * 为什么独立进程：librenpython.so 静态链接了 SDL/EGL/Mali 整套图形栈，
 * compile 时真正初始化 SDL 会污染进程内 RenderThread 的图形环境，导致
 * eglSwapBuffers 处 abort()（SIGABRT）。放进无 UI 的子进程后：
 *  - 主进程 RenderThread 不加载该 so，不再被污染 → 不再闪退
 *  - 子进程没有 UI/RenderThread，即使 SDL abort 也只影响编译进程
 *  - 子进程崩溃时主进程能通过 status 文件检测到，正常报错而非闪退
 *
 * 通信协议（全部落在 cacheDir/engine-compile/，两进程共享同一 dataDir）：
 *  - request.json : 主进程写入的编译参数
 *  - compile.log  : native 编译输出（引擎 stdout/stderr）
 *  - crash.log    : native 崩溃栈
 *  - status.txt   : 子进程写入的结束状态；"RUNNING" 表示进行中
 */
class CompileService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_COMPILE = "com.renpy.mobile.engine.COMPILE"
        const val ACTION_CANCEL = "com.renpy.mobile.engine.CANCEL"
        const val DIR = "engine-compile"
        const val F_REQUEST = "request.txt"
        const val F_LOG = "compile.log"
        const val F_CRASH = "crash.log"
        const val F_STATUS = "status.txt"
        const val F_CANCEL = "cancel.flag"
        const val F_FALLBACK = "fallback.flag"     // 标记本轮用了回退 Service 路径
        const val F_PROCLOG = "process.log"        // app_process  stdout/stderr
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_CANCELLED = "CANCELLED"

        fun workDir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

        /** 当前正在运行的编译进程（app_process execve 出来的），供取消时终止。 */
        @Volatile
        private var compileProcess: Process? = null

        /** 本轮是否用 app_process 外部进程。回退到 :compiler Service 时没有 Process 对象。 */
        @Volatile
        private var usingExternalProcess = false

        /**
         * 外部编译进程是否仍在运行。主进程轮询据此感知「进程已退出但没写 status」的情况
         * （典型：renpy.py 编译完调用 sys.exit → PyRun 内部 Py_Exit 直接 exit 进程，
         *  导致 native 侧来不及写 COMPILE_DONE）。无外部进程（回退 Service 路径）时返回
         *  true，避免把回退路径误判成已退出。
         */
        fun isCompileProcessAlive(): Boolean {
            if (!usingExternalProcess) return true
            val p = compileProcess ?: return false
            // 不用 Process.isAlive()（API 26+），minSdk 24 上会崩；exitValue() 全版本可用。
            return try {
                p.exitValue()   // 能取到退出码 = 进程已结束
                false
            } catch (_: IllegalThreadStateException) {
                true            // 还在运行
            }
        }

        fun isExternalProcess(): Boolean = usingExternalProcess

        /** 主进程调用：写请求、标记 RUNNING、启动编译进程。 */
        fun start(context: Context, soPath: String, sysPath: String, renpyPy: String,
                  project: String) {
            val d = workDir(context)
            // 清理上一轮产物（含取消标志 / 进程日志 / 回退标记）
            listOf(F_LOG, F_CRASH, F_STATUS, F_CANCEL, F_FALLBACK, F_PROCLOG)
                .forEach { File(d, it).delete() }
            // 请求文件：每行一个参数（路径不含换行，安全）
            File(d, F_REQUEST).writeText(
                listOf(soPath, sysPath, renpyPy, project).joinToString("\n")
            )
            File(d, F_STATUS).writeText(STATUS_RUNNING)

            // 【关键】优先用 app_process 以 execve 方式启动编译进程：
            //  vivo 机型上 zygote fork 出的 :compiler 进程继承 libvivo_runtime.so
            //  已锁死的 LoadWareManager mutex，renpy 编译时 abort。execve 会清空
            //  进程内存、重置所有 mutex，从根源避开这个厂商 bug。
            usingExternalProcess = false
            val started = tryStartExternalProcess(context, d)
            if (started) {
                usingExternalProcess = true
            } else {
                // 回退：某些受限 ROM 不允许 exec app_process，退回原独立 Service 进程。
                File(d, F_FALLBACK).writeText("1")
                val intent = Intent(context, CompileService::class.java).setAction(ACTION_COMPILE)
                context.startService(intent)
            }
        }

        private fun tryStartExternalProcess(context: Context, d: File): Boolean = try {
            val apk = context.applicationInfo.sourceDir
            val libDir = context.applicationInfo.nativeLibraryDir
            // app_process 语法：app_process [vm-options] <cmd-dir> <class-name> [args...]
            // cmd-dir 是必需的位置参数（会被 chdir，可为任意可读目录），不能省略，
            // 否则我们的类名会被当成 cmd-dir、入口类永远加载不到。
            val cmd = listOf(
                "/system/bin/app_process",
                "-Djava.class.path=$apk",
                "-Djava.library.path=$libDir",
                context.applicationInfo.dataDir,   // cmd-dir
                CompilerEntry::class.java.name,    // 入口类
                d.absolutePath,                    // args[0] = 工作目录
            )
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(File(d, F_PROCLOG))
            // CLASSPATH 环境变量兜底：个别 ROM 对 -Djava.class.path 解析有差异
            pb.environment()["CLASSPATH"] = apk
            compileProcess = pb.start()
            true
        } catch (t: Throwable) {
            runCatching { File(d, F_PROCLOG).writeText("APP_PROC_START_FAIL: ${t.javaClass.simpleName}: ${t.message}\n") }
            false
        }

        /** 主进程调用：请求取消当前编译。写标志供轮询立即感知，并终止编译进程。 */
        fun cancel(context: Context) {
            val d = workDir(context)
            File(d, F_CANCEL).writeText("1")
            File(d, F_STATUS).writeText(STATUS_CANCELLED)
            // 终止 app_process 编译进程（execve 出来的）
            compileProcess?.destroy()
            compileProcess = null
            // 同时通知 :compiler Service（回退路径用），杀掉可能残留的 native 编译
            val intent = Intent(context, CompileService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }

        /** 取消标志是否存在（供主进程轮询）。 */
        fun isCancelled(context: Context): Boolean = File(workDir(context), F_CANCEL).exists()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 取消请求：终止 :compiler 进程。nativeCompile 是同步阻塞调用，无法从内部中断，
        // 因此在这里结束 service 并自杀进程——native 编译随之被系统回收，主进程不受影响。
        if (intent?.action == ACTION_CANCEL) {
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_COMPILE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            val d = workDir(this@CompileService)
            val statusFile = File(d, F_STATUS)
            try {
                val args = File(d, F_REQUEST).readText().lineSequence().toList()
                if (args.size < 4) {
                    statusFile.writeText(t("ERROR: 请求参数不完整"))
                    stopSelf(startId)
                    return@launch
                }
                val (soPath, sysPath, renpyPy, project) = args
                val log = File(d, F_LOG)
                val crash = File(d, F_CRASH)

                // 关键：在这个独立进程里 dlopen + 初始化 + 编译
                val status = EngineBridge.nativeCompile(
                    soPath, sysPath, renpyPy, project,
                    log.absolutePath, crash.absolutePath,
                )
                statusFile.writeText(status)
            } catch (t: Throwable) {
                runCatching { statusFile.writeText("ERROR: ${t.message}") }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
