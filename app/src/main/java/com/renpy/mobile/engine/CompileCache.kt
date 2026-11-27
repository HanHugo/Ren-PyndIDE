package com.renpy.mobile.engine

import com.renpy.mobile.project.ProjectInfo
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * .rpy 脚本编译缓存。
 *
 * 目的：每次编译前比对 game/ 下所有 .rpy 的内容哈希；若与上次成功编译的快照
 * 完全一致（且 rpyc 产物仍在），则跳过昂贵的 native 编译，直接复用已有 .rpyc。
 *
 * 快照存于项目的 .renpyndide/compile-cache.json，不干扰 Ren'Py 自身结构。
 * 快照含 SDK 根路径指纹——SDK 升级后指纹变化，缓存自动失效，避免用旧引擎产物。
 */
object CompileCache {

    private const val META_DIR = ".renpyndide"
    private const val CACHE_FILE = "compile-cache.json"
    private const val KEY_SDK = "sdk"
    private const val KEY_COUNT = "rpycCount"
    private const val KEY_TIME = "savedAt"
    private const val KEY_FILES = "files"

    /** 单个脚本的哈希记录：相对路径 -> SHA-256（内容）。 */
    private data class Snapshot(
        val sdk: String,
        val rpycCount: Int,
        val files: Map<String, String>,
    )

    /** 扫描项目 game/ 下所有 .rpy，返回 相对路径(以 game/ 为根) -> SHA-256。 */
    private fun scan(projectPath: String): Map<String, String> {
        val game = File(projectPath, "game")
        if (!game.exists()) return emptyMap()
        val out = mutableMapOf<String, String>()
        game.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".rpy", ignoreCase = true)) {
                val rel = f.relativeTo(game).path.replace(File.separatorChar, '/')
                val hash = sha256(f)
                if (hash != null) out[rel] = hash
            }
        }
        return out
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

    private fun countRpyc(projectPath: String): Int {
        val game = File(projectPath, "game")
        if (!game.exists()) return 0
        return game.walkTopDown().count { it.isFile && it.name.endsWith(".rpyc", ignoreCase = true) }
    }

    private fun cacheFile(projectPath: String) = File(File(projectPath, META_DIR), CACHE_FILE)

    private fun load(projectPath: String): Snapshot? = runCatching {
        val f = cacheFile(projectPath)
        if (!f.exists()) return@runCatching null
        val obj = JSONObject(f.readText())
        val filesObj = obj.getJSONObject(KEY_FILES)
        val files = mutableMapOf<String, String>()
        filesObj.keys().forEach { key -> files[key] = filesObj.getString(key) }
        Snapshot(
            sdk = obj.optString(KEY_SDK, ""),
            rpycCount = obj.optInt(KEY_COUNT, 0),
            files = files,
        )
    }.getOrNull()

    /**
     * 缓存是否命中。命中条件：
     *  1) 存在有效快照；
     *  2) SDK 指纹一致（SDK 升级后自动失效）；
     *  3) 当前 .rpy 集合（路径+内容哈希）与快照完全一致；
     *  4) game/ 下 rpyc 数量 >= 快照记录数（防止 rpyc 被删后用空缓存）。
     */
    fun hit(project: ProjectInfo, sdkFingerprint: String): Boolean {
        val snap = load(project.path) ?: return false
        if (snap.sdk != sdkFingerprint) return false
        if (snap.files.isEmpty()) return false
        val current = scan(project.path)
        if (current != snap.files) return false
        val rpycNow = countRpyc(project.path)
        return rpycNow >= snap.rpycCount && rpycNow > 0
    }

    /** 编译成功后保存快照。 */
    fun save(project: ProjectInfo, sdkFingerprint: String) {
        runCatching {
            val dir = File(project.path, META_DIR).apply { mkdirs() }
            val files = scan(project.path)
            val obj = JSONObject().apply {
                put(KEY_SDK, sdkFingerprint)
                put(KEY_COUNT, countRpyc(project.path))
                put(KEY_TIME, System.currentTimeMillis())
                val filesObj = JSONObject()
                files.forEach { (k, v) -> filesObj.put(k, v) }
                put(KEY_FILES, filesObj)
            }
            File(dir, CACHE_FILE).writeText(obj.toString())
        }
    }

    /** 缓存失效（脚本变更或编译失败后调用）。 */
    fun invalidate(project: ProjectInfo) {
        runCatching { cacheFile(project.path).delete() }
    }

    /** 供日志显示：缓存里记录的 rpyc 数量。 */
    fun cachedRpycCount(project: ProjectInfo): Int = load(project.path)?.rpycCount ?: 0
}
