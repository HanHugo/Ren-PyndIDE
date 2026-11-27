package com.renpy.mobile.engine

import com.renpy.mobile.project.ProjectInfo
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * APK 打包缓存。
 *
 * 打包最耗时的是 Gradle 构建（分钟级）。若两次打包的「输入」完全一致，则直接复用
 * 上次产出的 APK，跳过整个打包流程（组装 distribution / 生成工程 / gradlew assemble）。
 *
 * 缓存 key（任一变化即失效，重新打包）：
 *  1) 配置：包名 / 应用名 / 版本号 / 屏幕方向 / 图标名
 *  2) 图标文件内容 SHA-256（图标被替换即失效）
 *  3) 启动图文件内容 SHA-256
 *  4) 游戏内容：game/ 下所有 .rpy / .rpyc / 图片 / 音频等资源的「相对路径 + 内容哈希」
 *     —— 关键：改了脚本或立绘必须重打，否则 APK 里还是旧游戏
 *  5) SDK 根路径（SDK 升级后失效）
 *
 * 缓存产物（APK 本体 + 元数据）存于项目 `.renpyndide/apk-cache/`，随项目走，不干扰 Ren'Py 结构。
 */
object ApkCache {

    private const val META_DIR = ".renpyndide"
    private const val CACHE_DIR = "apk-cache"
    private const val META_FILE = "meta.json"

    private fun cacheDir(projectPath: String) = File(File(projectPath, META_DIR), CACHE_DIR)
    private fun metaFile(projectPath: String) = File(cacheDir(projectPath), META_FILE)
    private fun cachedApk(projectPath: String): File? =
        cacheDir(projectPath).listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }
            ?.maxByOrNull { it.lastModified() }

    /** 打包输入的指纹（key）。 */
    data class Fingerprint(
        val config: String,
        val icon: String,
        val splash: String,
        val game: String,
        val sdk: String,
    ) {
        fun key(): String = listOf(config, icon, splash, game, sdk).joinToString("|")
    }

    /** 计算当前打包输入的指纹。 */
    fun fingerprint(
        project: ProjectInfo,
        config: ApkBuilder.BuildConfig,
        sdkPath: String,
    ): Fingerprint = Fingerprint(
        config = listOf(
            config.packageName, config.appName, config.version,
            config.orientation, config.iconName,
        ).joinToString("\u001f"),
        icon = fileHash(config.iconPath),
        splash = fileHash(config.splashPath),
        game = gameHash(project.path),
        sdk = sdkPath,
    )

    /** 单个文件 SHA-256；路径为空/文件不存在返回 ""。 */
    private fun fileHash(path: String): String {
        if (path.isBlank()) return ""
        val f = File(path)
        if (!f.isFile) return ""
        return sha256(f) ?: ""
    }

    /** game/ 下所有文件的「相对路径:SHA256」排序拼接后再取哈希，作为整体内容指纹。 */
    private fun gameHash(projectPath: String): String {
        val game = File(projectPath, "game")
        if (!game.exists()) return ""
        val entries = game.walkTopDown()
            .filter { it.isFile }
            .map { f ->
                val rel = f.relativeTo(game).path.replace(File.separatorChar, '/')
                "$rel:${sha256(f) ?: "ERR"}"
            }
            .sorted()
        val sb = StringBuilder()
        entries.forEach { sb.append(it).append('\n') }
        return sha256Text(sb.toString())
    }

    private fun sha256(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun sha256Text(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * 命中判定：
     *  1) 存在有效元数据；
     *  2) 指纹 key 完全一致；
     *  3) 缓存的 APK 文件仍然存在。
     * 命中返回缓存 APK 的 File，否则 null。
     */
    fun hit(project: ProjectInfo, fp: Fingerprint): File? {
        val meta = metaFile(project.path)
        if (!meta.exists()) return null
        val savedKey = runCatching { JSONObject(meta.readText()).optString("key", "") }.getOrNull() ?: return null
        if (savedKey != fp.key()) return null
        val apk = cachedApk(project.path) ?: return null
        if (!apk.isFile || apk.length() <= 0) return null
        return apk
    }

    /** 打包成功后保存缓存：把新 APK 复制进缓存目录并写元数据。 */
    fun save(project: ProjectInfo, fp: Fingerprint, apkFile: File): File? = runCatching {
        val dir = cacheDir(project.path).apply { mkdirs() }
        // 清掉旧缓存 APK
        dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }?.forEach { it.delete() }
        val dest = File(dir, apkFile.name)
        apkFile.copyTo(dest, overwrite = true)
        val obj = JSONObject().apply {
            put("key", fp.key())
            put("savedAt", System.currentTimeMillis())
            put("apkName", apkFile.name)
            put("size", dest.length())
        }
        metaFile(project.path).writeText(obj.toString())
        dest
    }.getOrNull()

    /** 缓存失效（配置/内容已变或打包失败时调用，非必须——指纹不匹配会自动失效）。 */
    fun invalidate(project: ProjectInfo) {
        runCatching { metaFile(project.path).delete() }
    }

    /** 供 UI/日志：缓存里记录的时间戳。 */
    fun cachedAt(project: ProjectInfo): Long = runCatching {
        if (!metaFile(project.path).exists()) 0L
        else JSONObject(metaFile(project.path).readText()).optLong("savedAt", 0L)
    }.getOrDefault(0L)
}
