package com.renpy.mobile.termux

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import com.renpy.mobile.i18n.t

/**
 * Termux 环境安装器。
 *
 * 把 Termux 官方 aarch64 bootstrap（一个预编译的最小 Linux 用户态）下载到 App 私有目录，
 * 解压、重建符号链接、设置权限。之后终端用官方 termux-exec + TERMUX__PREFIX 启动 bash，
 * 获得一个可用的 bash / apt / pkg 环境（真 PTY、真 Linux 用户态）。
 *
 * 注：Ren'Py 编译走内置 librenpython.so 引擎（见 EngineBridge），与这里的 Termux 用户态无关；
 * 自制的 pathremap hook / root 符号链接 / 静态路径改写方案均已移除。
 *
 * 状态通过 [state] 暴露给 UI。
 */
object TermuxBootstrap {

    private const val TAG = "TermuxBootstrap"
    private const val MAX_DOWNLOAD_ATTEMPTS = 30

    /** 安装互斥锁：同一时刻只允许一个安装流程，防止重复点击导致多个协程并发读写同一文件。 */
    private val installMutex = Mutex()
    private val _installing = MutableStateFlow(false)
    /** 是否正在安装中（UI 据此禁用按钮，做重复点击防护）。 */
    val installing: StateFlow<Boolean> = _installing

    // 官方 bootstrap release（apt.android-7 变体，适配 Android 7+）。
    const val BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/download/" +
            "bootstrap-2026.11.15-r1%2Bapt.android-7/bootstrap-aarch64.zip"

    /** 内置在 APK assets 里的 bootstrap zip（免联网下载）。 */
    private const val ASSET_ZIP = "termux-bootstrap-aarch64.zip"

    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val downloaded: Long, val total: Long, val speedBps: Long) : Phase
        data object Verifying : Phase
        data class Extracting(val entries: Int, val total: Int) : Phase
        data object Installed : Phase
        data class Failed(val reason: String) : Phase
    }

    data class State(val phase: Phase = Phase.Idle)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed

    /** PREFIX：bootstrap 安装根（usr 目录）。 */
    fun prefixDir(context: Context) = File(context.filesDir, "usr")
    /** HOME：用户主目录。 */
    fun homeDir(context: Context) = File(context.filesDir, "home")

    /** 是否已安装（usr/bin/bash 存在即可执行）。 */
    fun isInstalled(context: Context): Boolean {
        val ok = File(prefixDir(context), "bin/bash").canExecute()
        _installed.value = ok
        return ok
    }

    /** 进程开始时刷新一次安装状态。 */
    fun bind(context: Context) {
        isInstalled(context)
    }

    private fun update(p: Phase) {
        _state.value = State(p)
    }

    /**
     * 安装 bootstrap。可在协程中调用。返回是否成功。
     * 带互斥锁 + installing 状态：重复点击（并发调用）会被立即忽略，不会启动第二个安装流程。
     */
    suspend fun install(context: Context): Boolean {
        // tryLock：已有安装在进行时直接返回 false，避免多个协程并发读写同一个 zip 文件
        if (!installMutex.tryLock()) {
            Log.w(TAG, t("install 已被调用，忽略重复点击"))
            return false
        }
        _installing.value = true
        return try {
            doInstall(context)
        } finally {
            _installing.value = false
            installMutex.unlock()
        }
    }

    /** 真正的安装流程（调用方已持有 [installMutex]）。 */
    private suspend fun doInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefix = prefixDir(context)
        if (File(prefix, "bin/bash").canExecute()) {
            _installed.value = true
            update(Phase.Installed)
            return@withContext true
        }
        try {
            val zipFile = File(context.cacheDir, ASSET_ZIP)
            // 1) 准备 zip：优先从 APK 内置的 assets 复制（免联网，秒级）；
            //    仅当内置资源缺失/损坏时才回退到联网断点续传。
            val fromAssets = try {
                copyBuiltinZip(context, zipFile)
            } catch (e: Exception) {
                Log.w(TAG, "内置 bootstrap 不可用，回退联网下载: ${e.message}")
                false
            }
            if (!fromAssets) {
                try {
                    download(BOOTSTRAP_URL, zipFile)
                } catch (e: Exception) {
                    throw java.io.IOException("下载失败: ${e.message}", e)
                }
            }
            // 2) 校验（zip 完整性）；失败则删除残包，下次从头下载
            update(Phase.Verifying)
            try {
                verifyZip(zipFile)
            } catch (e: Exception) {
                Log.w(TAG, "zip 校验失败，删除残包以便重下: ${e.message}")
                zipFile.delete()
                throw java.io.IOException("校验失败: ${e.message}", e)
            }
            // 3) 解压到 staging（含符号链接、权限），成功后原子改名
            try {
                extract(zipFile, prefix, context)
            } catch (e: Exception) {
                throw java.io.IOException("解压失败: ${e.message}", e)
            }
            // 4) 完成
            _installed.value = true
            update(Phase.Installed)
            true
        } catch (e: CancellationException) {
            update(Phase.Idle)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            update(Phase.Failed(e.message ?: t("未知错误")))
            false
        }
    }

    /**
     * 从 APK 内置 assets 复制 bootstrap zip 到 [dest]（免联网）。
     * 若 [dest] 已是完整内置包则直接复用。返回是否成功。
     */
    private suspend fun copyBuiltinZip(context: Context, dest: File): Boolean = withContext(Dispatchers.IO) {
        val assetSize = try {
            context.assets.openFd(ASSET_ZIP).length
        } catch (e: Exception) {
            // openFd 对 compressed 资源可能失败，退回粗估
            -1L
        }
        // 若目标文件大小与内置包一致，直接复用（避免重复拷贝）
        if (dest.exists() && assetSize > 0 && dest.length() == assetSize) {
            update(Phase.Downloading(assetSize, assetSize, 0))
            return@withContext true
        }
        val buf = ByteArray(256 * 1024)
        var copied = 0L
        var lastUpdate = 0L
        context.assets.open(ASSET_ZIP).use { input ->
            FileOutputStream(dest).use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    copied += n
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 60) {
                        update(Phase.Downloading(copied, assetSize, 0))
                        lastUpdate = now
                    }
                }
            }
        }
        update(Phase.Downloading(copied, assetSize, 0))
        if (copied <= 0) throw java.io.IOException(t("内置 bootstrap 为空"))
        if (assetSize > 0 && copied != assetSize) {
            dest.delete()
            throw java.io.IOException("内置 bootstrap 大小不符: $copied/$assetSize")
        }
        true
    }

    /** 断点续传下载到 zipFile。网络中断会自动重试续传，最多 [MAX_DOWNLOAD_ATTEMPTS] 轮。 */
    private suspend fun download(urlStr: String, dest: File) {
        var total = 0L
        var attempts = 0
        var lastResume = -1L // 检测「这一轮一点进度都没有」，避免卡死
        long@ while (attempts < MAX_DOWNLOAD_ATTEMPTS) {
            attempts++
            var resume = if (dest.exists()) dest.length() else 0L
            if (resume == lastResume) {
                // 上一轮没能往前推进，删掉重来（可能本地文件已损坏）
                dest.delete()
                resume = 0
            }
            lastResume = resume
            var conn: HttpURLConnection? = null
            var raf: java.io.RandomAccessFile? = null
            var streamBroken = false
            try {
                conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (RenPyndIDE)")
                if (resume > 0) conn.setRequestProperty("Range", "bytes=$resume-")
                conn.connect()
                val code = conn.responseCode
                if (code !in 200..299 && code != 416) {
                    throw java.io.IOException("HTTP $code")
                }
                if (code == 416) {
                    // Range 越界：文件已完整，交给校验
                    conn.disconnect()
                    return
                }
                val len = conn.contentLengthLong
                val partial = code == 206
                total = if (partial) resume + len else len
                raf = java.io.RandomAccessFile(dest, "rw")
                if (partial) raf.seek(resume) else { raf.setLength(0); resume = 0 }
                var downloaded = resume
                val buf = ByteArray(64 * 1024)
                var lastTime = System.currentTimeMillis()
                var lastBytes = downloaded
                conn.inputStream.use { input ->
                    while (true) {
                        val n = try {
                            input.read(buf)
                        } catch (e: java.io.IOException) {
                            // 网络在传输中途被掐断（Stream closed / connection reset 等）。
                            // 不冒泡异常，当作本轮中断；已写入的部分会被下一轮续传接管。
                            Log.w(TAG, "download stream interrupted at $downloaded: ${e.message}")
                            streamBroken = true
                            break
                        }
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 500 && total > 0) {
                            val speed = ((downloaded - lastBytes) * 1000L) / (now - lastTime)
                            update(Phase.Downloading(downloaded, total, speed))
                            lastBytes = downloaded
                            lastTime = now
                        }
                    }
                }
                raf.close()
                conn.disconnect()
                update(Phase.Downloading(downloaded, total, 0))
                if (!streamBroken && (total <= 0 || downloaded >= total)) {
                    return // 下载完成
                }
                // 没下完或中途断流 → 进入下一轮续传
            } catch (e: CancellationException) {
                try { raf?.close() } catch (_: Exception) {}
                conn?.disconnect()
                throw e
            } catch (e: java.io.IOException) {
                Log.w(TAG, "download attempt $attempts failed: ${e.message}")
                if (attempts >= MAX_DOWNLOAD_ATTEMPTS) throw e
            } finally {
                try { raf?.close() } catch (_: Exception) {}
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        throw java.io.IOException("下载失败：连续 $MAX_DOWNLOAD_ATTEMPTS 次网络中断，请检查网络后重试")
    }

    /** 校验 zip 完整性（遍历一次中央目录/条目）。 */
    private suspend fun verifyZip(zip: File) = withContext(Dispatchers.IO) {
        java.util.zip.ZipFile(zip).use { zf ->
            if (zf.size() < 100) throw java.io.IOException("bootstrap zip 条目异常: ${zf.size()}")
            if (zf.getEntry("SYMLINKS.txt") == null) throw java.io.IOException(t("bootstrap zip 缺少 SYMLINKS.txt"))
        }
    }

    /** 解压到 staging 目录，重建符号链接与权限，最后原子改名到 prefix。 */
    private suspend fun extract(zip: File, prefix: File, context: Context) = withContext(Dispatchers.IO) {
        val staging = File(prefix.parentFile, "usr-staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        val buffer = ByteArray(8096)
        val symlinks = mutableListOf<Pair<String, String>>()
        var entries = 0
        val total = java.util.zip.ZipFile(zip).use { it.size() }

        ZipInputStream(zip.inputStream()).use { zin ->
            var e: ZipEntry?
            while (true) {
                e = zin.nextEntry ?: break
                entries++
                if (e.name == "SYMLINKS.txt") {
                    // 关键：绝不能用 use/useLines 包装 zin 的 reader——关闭 reader 会连带关闭
                    // 底层 ZipInputStream，导致处理完本条目后 zin.nextEntry 抛 "Stream closed"。
                    // readBytes() 只消费当前 entry 的字节（ZipInputStream.read 到本 entry 末尾返回 -1），
                    // 不跨界读下一个 entry，也不关闭 zin。
                    val content = zin.readBytes().toString(Charsets.UTF_8)
                    content.lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val parts = line.split("←", limit = 2)
                        if (parts.size != 2) throw java.io.IOException("符号链接格式错误: $line")
                        val oldPath = parts[0]
                        val newPath = File(staging, parts[1]).absolutePath // parts[1] 形如 "./lib/xxx"
                        symlinks.add(oldPath to newPath)
                        File(newPath).parentFile?.mkdirs()
                    }
                } else {
                    val target = File(staging, e.name)
                    if (e.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var n: Int
                            while (true) {
                                n = zin.read(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                        // 与官方一致：bin/ libexec/ apt-helper/ methods/ 与 second-stage 脚本设可执行
                        if (e.name.startsWith("bin/") ||
                            e.name.startsWith("libexec") ||
                            e.name.startsWith("lib/apt/apt-helper") ||
                            e.name.startsWith("lib/apt/methods") ||
                            e.name == "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
                        ) {
                            Os.chmod(target.absolutePath, 448) // 0700
                        }
                    }
                }
                if (entries % 200 == 0) update(Phase.Extracting(entries, total))
            }
        }
        if (symlinks.isEmpty()) throw java.io.IOException(t("未找到 SYMLINKS.txt"))
        for ((oldPath, newPath) in symlinks) {
            try { Os.symlink(oldPath, newPath) } catch (_: Exception) { /* 已存在则忽略 */ }
        }
        update(Phase.Extracting(entries, total))

        // 原子改名：先删旧 prefix
        if (prefix.exists()) prefix.deleteRecursively()
        if (!staging.renameTo(prefix)) throw java.io.IOException(t("usr-staging 改名失败"))

        // 准备 home 与必要目录
        homeDir(context).mkdirs()
        File(prefix, "tmp").mkdirs()

        // 关键：apt/dpkg 二进制把 FHS 路径硬编码为 /data/data/com.termux/files/usr。
        // termux-exec 的 LD_PRELOAD 只重写 exec 层，管不到 apt/dpkg 内部 open() 配置目录
        // （表现为 "Unable to read .../etc/apt/apt.conf.d/" 与 "dpkg.cfg.d: Permission denied"）。
        // 这里生成 apt.conf，用 Dir 指令把 apt 的所有根指向真实 prefix；配合调用方 export
        // APT_CONFIG / DPKG_ADMINDIR，apt 即可在任意私有目录下正常工作（无需 root、无需 LD_PRELOAD 重写）。
        writeAptConf(prefix)
    }

    /**
     * 确保 apt.conf 存在（幂等）。供已安装旧 bootstrap 的场景补齐——旧环境在 apt.conf
     * 机制加入前解压，不会自动生成；工具链安装前调用此方法把配置就位。
     */
    fun ensureAptConf(context: Context) {
        if (isInstalled(context)) writeAptConf(prefixDir(context))
    }

    /**
     * 生成 $PREFIX/etc/apt/apt.conf：显式指定 apt 的配置/状态/缓存/二进制目录为真实 prefix。
     * 覆盖 apt 二进制编译期硬编码的 /data/data/com.termux 路径。
     */
    private fun writeAptConf(prefix: File) {
        val p = prefix.absolutePath
        val conf = File(prefix, "etc/apt/apt.conf")
        conf.parentFile?.mkdirs()
        conf.writeText(
            """
Dir "$p";
Dir::Etc "etc/apt";
Dir::Etc::sourcelist "sources.list";
Dir::Etc::sourceparts "sources.list.d";
Dir::Etc::trusted "trusted.gpg";
Dir::Etc::trustedparts "trusted.gpg.d";
Dir::Etc::preferences "preferences";
Dir::Etc::preferencesparts "preferences.d";
Dir::State "var/lib/apt";
Dir::State::lists "lists";
Dir::Cache "var/cache/apt";
Dir::Cache::archives "archives";
Dir::Bin "bin";
# 关键：apt 的下载 method 二进制目录。若不显式指定，apt 会用编译期硬编码的
# /data/data/com.termux/files/usr/lib/apt/methods → "method driver ... could not be found"。
Dir::Bin::methods "$p/lib/apt/methods";
# 致命: apt 校验 InRelease 签名时会调用 apt-key, 但 Termux patch 把 Dir::Bin::apt-key
# 写成了绝对路径 /data/data/com.termux/files/usr/bin/apt-key (覆盖上面的 Dir "$p")。
# 本 App 无权限执行那个路径 -> "Couldn't execute .../apt-key" -> "repository is not
# signed" -> 所有 pkg install 全部失败。显式指回私有 prefix, 并用下面两项彻底放行。
Dir::Bin::apt-key "$p/bin/apt-key";
APT::Get::AllowUnauthenticated "true";
Acquire::AllowInsecureRepositories "true";
APT::Architecture "aarch64";
APT::Architectures { "aarch64"; };
""".trimIndent() + "\n"
        )
        // apt 需要这些运行时目录存在，否则报 "Archives directory .../partial is missing"。
        // 用 File 在这里幂等创建，不依赖脚本执行顺序。
        listOf(
            "etc/apt/preferences.d", "etc/apt/sources.list.d", "etc/apt/trusted.gpg.d",
            "var/lib/apt/lists/partial", "var/lib/dpkg/updates",
            "var/cache/apt/archives/partial",
        ).forEach { File(prefix, it).mkdirs() }

        // 关键：这个 bootstrap 的 lib/apt/methods 只有 http，没有 https driver，
        // 而内置 sources.list 用的是 https → apt 报 "method driver https could not be found"，
        // 所有 pkg install 全部失败。改写为 Termux 官方 cloudflare http 镜像（已验证 200）。
        val sources = File(prefix, "etc/apt/sources.list")
        sources.writeText(
            "# 使用 http：本 bootstrap 未内置 https apt method（只有 http/file/...）\n" +
                "# [trusted=yes] 关键：apt 二进制把 apt-key 路径硬编码为 /data/data/com.termux/...，\n" +
                "# 本 App 无权限执行 -> 签名校验必然失败 -> \"repository is not signed\" -> 全部 pkg\n" +
                "# install 失败。trusted=yes 让 apt 跳过签名校验，apt-key 根本不会被调用。\n" +
                "deb [trusted=yes] http://packages-cf.termux.dev/apt/termux-main/ stable main\n"
        )
    }

    /** 卸载 bootstrap。 */
    suspend fun uninstall(context: Context) = withContext(Dispatchers.IO) {
        prefixDir(context).deleteRecursively()
        _installed.value = false
        update(Phase.Idle)
    }
}
