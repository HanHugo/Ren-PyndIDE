package com.renpy.mobile.installer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import com.renpy.mobile.i18n.t

/**
 * Ren'Py SDK 真实安装管道。
 *
 * - 从 Ren'Py 官方站顺序下载三个真实 zip 包（SDK 核心 / RAPT / 原生符号）
 * - HttpURLConnection + Range 断点续传，写 .part 文件；支持暂停后原样续传
 * - 下载官方 checksums.txt 解析 SHA-256（失败回退内置校验值）后校验
 * - ZipInputStream 解压到 filesDir/sdk/<version>/<id>/，防 Zip Slip
 * - 解压成功后删除压缩包，仅保留解压产物
 * - SharedPreferences 持久化已安装版本与状态
 * - 通过 StateFlow 向 UI 暴露真实进度（UI 门槛完全由该 State 派生）
 */
object SdkInstaller {

    const val SDK_VERSION = "8.6.1"
    private const val BASE_URL = "https://www.renpy.org/dl/$SDK_VERSION"
    const val CHECKSUMS_URL = "$BASE_URL/checksums.txt"
    private const val PREFS = "sdk_installer_prefs"

    data class Component(
        val id: String,
        val name: String,
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val builtinSha256: String,
        val desc: String,
    )

    /** 三项映射为官方站真实存在的 zip 包（均为 zip，标准库即可解压）。 */
    val COMPONENTS = listOf(
        Component(
            id = "sdk",
            name = t("Ren'Py SDK 核心"),
            fileName = "renpy-8.6.1-sdk.zip",
            url = "$BASE_URL/renpy-8.6.1-sdk.zip",
            sizeBytes = 163_957_166L,
            builtinSha256 = "596c31be88b351de64166276708278a70f24e833be7181e7c37b8c106a0f0fdb",
            desc = t("完整 SDK：引擎、脚本、示例游戏与桌面平台运行时"),
        ),
        Component(
            id = "rapt",
            name = t("Android 打包工具 RAPT"),
            fileName = "renpy-8.6.1-rapt.zip",
            url = "$BASE_URL/renpy-8.6.1-rapt.zip",
            sizeBytes = 72_245_043L,
            builtinSha256 = "8f92b3e2dbe65d624a9b0fcf1bb5d5ab7e2ee7bca621a4e4f4d8f3e664e39142",
            desc = t("RAPT：将 Ren'Py 工程打包为 Android APK 的工具链（PC 端工具，需兼容层）"),
        ),
        Component(
            id = "native",
            name = t("原生符号库"),
            fileName = "android-native-symbols.zip",
            url = "$BASE_URL/android-native-symbols.zip",
            sizeBytes = 92_926_936L,
            builtinSha256 = "35931d64bd742c0ed26a2b4207bbfeae5e4b084ed98b2a85639c869e007a69d2",
            desc = t("Android 原生层符号与调试支持文件"),
        ),
    )

    enum class Phase { IDLE, DOWNLOADING, VERIFYING, EXTRACTING, INSTALLED, FAILED, PAUSED }

    data class ComponentState(
        val id: String,
        val phase: Phase = Phase.IDLE,
        val downloaded: Long = 0L,
        val total: Long = 0L,
        val speedBps: Long = 0L,
        val error: String? = null,
    )

    private val _states = MutableStateFlow(
        COMPONENTS.map { ComponentState(it.id, total = it.sizeBytes) }
    )
    val states: StateFlow<List<ComponentState>> = _states.asStateFlow()

    private var appCtx: Context? = null
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 唯一的安装任务（顺序下载三个组件）。暂停=取消它，继续=新建它。 */
    private var masterJob: Job? = null

    /** 幂等绑定 applicationContext，并从持久化状态恢复。 */
    fun bind(context: Context) {
        if (appCtx == null) {
            appCtx = context.applicationContext
            prefs = appCtx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _states.value = COMPONENTS.map { c ->
                val done = isComponentInstalled(c)
                ComponentState(
                    id = c.id,
                    phase = if (done) Phase.INSTALLED else Phase.IDLE,
                    downloaded = if (done) c.sizeBytes else 0L,
                    total = c.sizeBytes,
                )
            }
        }
    }

    private fun requireCtx(): Context = appCtx ?: throw IllegalStateException(t("SdkInstaller 未 bind"))

    private fun sdkRoot(): File = File(requireCtx().filesDir, "sdk/$SDK_VERSION")

    private fun componentDir(c: Component): File = File(sdkRoot(), c.id)

    private fun partFile(c: Component): File = File(componentDir(c), "${c.fileName}.part")

    private fun prefKey(c: Component) = "installed_${SDK_VERSION}_${c.id}"

    private fun isComponentInstalled(c: Component): Boolean =
        prefs?.getBoolean(prefKey(c), false) == true && componentDir(c).exists()

    /** 三个组件是否全部安装完成（供 Service 判断收尾）。 */
    fun isAllInstalled(): Boolean = COMPONENTS.all { isComponentInstalled(it) }

    /** 由 StateFlow 派生的"全部安装完成"——UI 门槛应当用这个，保证随状态刷新。 */
    fun allInstalledFromState(current: List<ComponentState>): Boolean =
        current.size == COMPONENTS.size && current.all { it.phase == Phase.INSTALLED }

    fun installedVersion(): String? = if (isAllInstalled()) SDK_VERSION else null

    fun installedBytes(): Long = COMPONENTS.sumOf { c ->
        if (isComponentInstalled(c)) componentDir(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else 0L
    }

    private fun update(id: String, block: (ComponentState) -> ComponentState) {
        _states.value = _states.value.map { if (it.id == id) block(it) else it }
    }

    /**
     * 顺序安装所有尚未安装的组件。幂等：已有活动任务时直接返回。
     * 注意：暂停后继续、失败后重试都调用本方法；调用方可显式调，Service 的 onStartCommand 也会调。
     */
    fun installAll() {
        if (masterJob?.isActive == true) return
        masterJob = scope.launch {
            for (c in COMPONENTS.filterNot { isComponentInstalled(it) }) {
                coroutineContext.ensureActive()
                try {
                    doInstall(c)
                } catch (_: CancellationException) {
                    // 暂停/取消：把当前项标记为已暂停，并停止后续组件
                    update(c.id) { it.copy(phase = Phase.PAUSED, speedBps = 0) }
                    return@launch
                } catch (e: Exception) {
                    // 单项失败不影响其他组件继续
                    update(c.id) { it.copy(phase = Phase.FAILED, error = e.message ?: t("未知错误")) }
                }
            }
        }
    }

    /** 取消全部：取消任务、保留 .part 供续传、把进行中的项标记为 PAUSED。 */
    fun cancelAll() {
        masterJob?.cancel()
        masterJob = null
        _states.value = _states.value.map { s ->
            if (s.phase == Phase.DOWNLOADING || s.phase == Phase.VERIFYING || s.phase == Phase.EXTRACTING) {
                s.copy(phase = Phase.PAUSED, speedBps = 0)
            } else s
        }
    }

    /** 把失败/暂停项重置为 IDLE，供 installAll 重新启动（从 .part 续传）。 */
    fun retryFailed() {
        _states.value = _states.value.map { s ->
            if (s.phase == Phase.FAILED || s.phase == Phase.PAUSED) s.copy(phase = Phase.IDLE, error = null)
            else s
        }
    }

    /** 删除某组件的已安装文件与标记。 */
    fun uninstall(c: Component) {
        cancelAll()
        runCatching { componentDir(c).deleteRecursively() }
        prefs?.edit()?.putBoolean(prefKey(c), false)?.apply()
        update(c.id) { ComponentState(id = c.id, total = c.sizeBytes) }
    }

    private suspend fun doInstall(c: Component) {
        val dir = componentDir(c)
        if (!dir.exists()) dir.mkdirs()

        // ── 1. 下载（支持断点续传）──
        val part = partFile(c)
        var existing = if (part.exists()) part.length() else 0L
        // .part 超过官方大小视为损坏，丢弃重下
        if (existing > c.sizeBytes) {
            part.delete()
            existing = 0
        }
        update(c.id) {
            it.copy(phase = Phase.DOWNLOADING, downloaded = existing, total = c.sizeBytes, error = null)
        }

        // 建立连接；若 Range 返回 416（part 已完整），删除 part 从头下
        var conn = openConnection(c, existing)
        var code = conn.responseCode
        if (code == HttpURLConnection.HTTP_BAD_REQUEST || code == 416) {
            if (existing > 0) {
                conn.disconnect()
                part.delete()
                existing = 0
                conn = openConnection(c, 0)
                code = conn.responseCode
            }
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
        val contentLength = conn.contentLengthLong
        val total = if (append) existing + contentLength else (if (contentLength > 0) contentLength else c.sizeBytes)

        val raf = RandomAccessFile(part, "rw")
        if (append) raf.seek(existing) else { raf.setLength(0); existing = 0 }
        var downloaded = existing
        var lastBytes = existing
        var lastTime = System.currentTimeMillis()

        BufferedInputStream(conn.inputStream).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (!coroutineContext.isActive) break
                val n = input.read(buf)
                if (n <= 0) break
                raf.write(buf, 0, n)
                downloaded += n
                val now = System.currentTimeMillis()
                if (now - lastTime >= 400) {
                    val speed = ((downloaded - lastBytes) * 1000L) / (now - lastTime)
                    update(c.id) {
                        it.copy(
                            phase = Phase.DOWNLOADING,
                            downloaded = downloaded,
                            total = total,
                            speedBps = speed,
                        )
                    }
                    lastBytes = downloaded
                    lastTime = now
                }
            }
        }
        raf.close()
        conn.disconnect()

        // 下载被取消：保留 .part 供下次续传
        if (!coroutineContext.isActive) throw CancellationException()

        // 补齐最终进度显示，避免停在 99%
        update(c.id) { it.copy(downloaded = total, total = total, speedBps = 0) }

        // ── 2. 校验 SHA-256 ──
        update(c.id) { it.copy(phase = Phase.VERIFYING) }
        val expected = fetchExpectedSha256(c)
        val actual = sha256Of(part)
        if (!actual.equals(expected, ignoreCase = true)) {
            part.delete() // 校验失败：删除坏文件，下次从头下载
            throw IllegalStateException(t("校验失败：SHA-256 不匹配"))
        }

        // ── 3. 解压（防 Zip Slip）──
        update(c.id) { it.copy(phase = Phase.EXTRACTING) }
        val dest = dir
        ZipInputStream(FileInputStream(part)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(dest, entry.name).canonicalFile
                val destRoot = dest.canonicalFile
                if (!target.path.startsWith(destRoot.path + File.separator) && target.path != destRoot.path) {
                    throw SecurityException("非法压缩包路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // ── 4. 删除压缩包，只保留解压产物；写安装标记 ──
        part.delete()
        prefs?.edit()?.putBoolean(prefKey(c), true)?.apply()
        update(c.id) {
            it.copy(
                phase = Phase.INSTALLED,
                downloaded = c.sizeBytes,
                total = c.sizeBytes,
                speedBps = 0,
                error = null,
            )
        }
    }

    private fun openConnection(c: Component, from: Long): HttpURLConnection {
        val conn = URL(c.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (RenPyndIDE)")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
        conn.connect()
        return conn
    }

    /** 从官方 checksums.txt 解析 sha256；网络失败回退内置值。 */
    private fun fetchExpectedSha256(c: Component): String {
        return try {
            val text = URL(CHECKSUMS_URL).readText(Charsets.UTF_8)
            var inSha256 = false
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#")) {
                    inSha256 = trimmed.equals("# sha256", ignoreCase = true)
                } else if (inSha256 && trimmed.isNotEmpty()) {
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[1] == c.fileName && parts[0].length == 64) {
                        return parts[0]
                    }
                }
            }
            c.builtinSha256
        } catch (_: Exception) {
            c.builtinSha256
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
