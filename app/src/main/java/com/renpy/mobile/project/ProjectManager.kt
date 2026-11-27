package com.renpy.mobile.project

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.renpy.mobile.model.FileNode
import com.renpy.mobile.termux.TermuxBootstrap
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import com.renpy.mobile.i18n.t

/**
 * 项目信息（一个 Ren'Py 项目 = Termux HOME 下的一个真实目录）。
 */
data class ProjectInfo(
    val name: String,
    val path: String,
    val template: String,
    val createdAt: Long,
    /** 最近一次打开的时间戳（未打开过则为 0）。「最近的项目」据此排序。 */
    val lastOpenedAt: Long = 0L,
)

/**
 * 真实文件系统层：项目的创建 / 列举 / 删除、项目树遍历、文件读写。
 *
 * 项目根目录可配置（见 projectsRoot）：默认在 filesDir/home/projects，
 * 也可由用户在设置里切换到公共文档目录或任意自选文件夹。所有项目操作统一走 projectsRoot。
 * 元数据藏在项目的 .renpyndide/project.json，不干扰 Ren'Py 自身结构。
 */
object ProjectManager {

    private const val PROJECTS_DIR = "projects"
    private const val META_DIR = ".renpyndide"
    private const val META_FILE = "project.json"

    // 项目根目录配置（与 MainActivity 的个性化设置共用同一份 prefs）
    private const val PREFS = "renpyndide_prefs"
    private const val KEY_PROJECTS_ROOT = "projects_root_path"

    /** 根目录来源：私有目录 / 公共目录 / 自定义文件夹。 */
    enum class RootKind { PRIVATE, PUBLIC, CUSTOM }

    val TEMPLATE_NAMES = arrayOf(t("空白项目"), t("视觉小说"), t("示例项目"))

    /** 项目名称只允许字母、数字、下划线、连字符，防路径穿越。 */
    fun isValidName(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9_\\-]{1,40}"))

    /** 默认私有项目根目录（Termux HOME/projects）。 */
    fun privateRoot(context: Context): File =
        File(TermuxBootstrap.homeDir(context), PROJECTS_DIR)

    /** 公共文档目录下的项目根（/storage/emulated/0/Documents/RenPyndIDE/projects）。 */
    fun publicRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "RenPyndIDE/projects")

    /** 当前配置的根目录来源。 */
    fun rootKind(context: Context): RootKind {
        val custom = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECTS_ROOT, null)
        return when {
            custom.isNullOrBlank() -> RootKind.PRIVATE
            custom == publicRoot().absolutePath -> RootKind.PUBLIC
            else -> RootKind.CUSTOM
        }
    }

    /**
     * 当前项目根目录。优先读自定义配置；未配置时用默认私有目录。
     * 目录不存在会尝试创建；创建失败（如权限问题）则回退到私有目录，保证 App 始终可用。
     */
    fun projectsRoot(context: Context): File {
        val custom = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECTS_ROOT, null)
        val target = if (custom.isNullOrBlank()) privateRoot(context) else File(custom)
        return try {
            if (!target.exists() && !target.mkdirs()) privateRoot(context).also { it.mkdirs() }
            else target
        } catch (e: Exception) {
            privateRoot(context).also { it.mkdirs() }
        }
    }

    /** 设置项目根目录。传 null 或空串恢复默认私有目录。 */
    fun setProjectsRoot(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECTS_ROOT, path?.trim()?.takeIf { it.isNotBlank() }).apply()
    }

    /**
     * 验证一个路径能否作为项目根目录：必须可写（能创建子目录并删除）。
     * 有 MANAGE_EXTERNAL_STORAGE 权限时公共目录也可写。
     */
    fun isWritableDir(path: String): Boolean = try {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".renpyndide_write_test_${System.currentTimeMillis()}")
        val created = probe.createNewFile()
        created && probe.delete()
    } catch (e: Exception) {
        false
    }

    /**
     * 列举全部项目。默认按「最近打开」排序（从未打开过的按创建时间兜底，排在有打开记录的之后），
     * 这样首页「最近的项目」名副其实；需要纯创建顺序时传 [byRecentOpened] = false。
     */
    fun listProjects(context: Context, byRecentOpened: Boolean = true): List<ProjectInfo> {
        val root = projectsRoot(context)
        val all = (root.listFiles { it.isDirectory } ?: emptyArray()).mapNotNull { readMeta(it) }
        return if (byRecentOpened) {
            all.sortedByDescending { if (it.lastOpenedAt > 0) it.lastOpenedAt else it.createdAt }
        } else {
            all.sortedByDescending { it.createdAt }
        }
    }

    /** 记录项目被打开的时间（写入元数据 lastOpenedAt），供「最近的项目」排序。 */
    fun markOpened(context: Context, name: String) {
        val dir = File(projectsRoot(context), name)
        val meta = File(File(dir, META_DIR), META_FILE)
        runCatching {
            val json = if (meta.exists()) JSONObject(meta.readText()) else JSONObject()
            json.put("name", json.optString("name", name))
            json.put("lastOpenedAt", System.currentTimeMillis())
            meta.parentFile?.mkdirs()
            meta.writeText(json.toString())
        }
    }

    fun projectByName(context: Context, name: String): ProjectInfo? {
        if (!isValidName(name)) return null
        val dir = File(projectsRoot(context), name)
        return if (dir.isDirectory) readMeta(dir) else null
    }

    /** 从指定根目录按名称读取项目（用于迁移回滚等不在当前根目录下的场景）。 */
    fun projectInDir(root: File, name: String): ProjectInfo? {
        val dir = File(root, name)
        return if (dir.isDirectory) readMeta(dir) else null
    }

    fun exists(context: Context, name: String): Boolean =
        isValidName(name) && File(projectsRoot(context), name).exists()

    /** 新建项目：建目录、写初始脚本、写元数据。 */
    fun createProject(context: Context, name: String, templateIndex: Int): ProjectInfo {
        require(isValidName(name)) { t("项目名称只能包含字母、数字、下划线和连字符（1-40 位）") }
        val dir = File(projectsRoot(context), name)
        require(!dir.exists()) { t("同名项目已存在") }

        // 保证项目里至少有一个文件夹（game 是 Ren'Py 的标准脚本目录）
        File(dir, "game").mkdirs()

        filesFor(templateIndex).forEach { (rel, content) ->
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }

        val metaDir = File(dir, META_DIR).also { it.mkdirs() }
        val json = JSONObject().apply {
            put("name", name)
            put("template", TEMPLATE_NAMES.getOrElse(templateIndex) { TEMPLATE_NAMES[0] })
            put("createdAt", System.currentTimeMillis())
        }
        File(metaDir, META_FILE).writeText(json.toString())
        return readMeta(dir)!!
    }

    /** 删除项目（递归）。 */
    fun deleteProject(context: Context, name: String): Boolean {
        if (!isValidName(name)) return false
        return File(projectsRoot(context), name).deleteRecursively()
    }

    /**
     * 把项目从当前根目录迁移到 [targetRoot] 下。
     * - 显示进度：复制 [onProgress](0..1)
     * - 保留全部内容（含 .renpyndide 元数据）
     * - 复制校验成功后才删除源目录，避免中途失败导致数据丢失
     * - 若目标目录下已存在同名项目则拒绝
     * - 迁移完成后按新路径读取并返回 ProjectInfo
     */
    fun migrateProject(
        context: Context,
        project: ProjectInfo,
        targetRoot: File,
        onProgress: (Float) -> Unit = {},
    ): ProjectInfo {
        val source = File(project.path)
        require(source.isDirectory) { "源项目目录不存在：${project.path}" }
        val target = File(targetRoot, source.name)
        require(target.absolutePath != source.absolutePath) { t("项目已在此位置") }
        require(!target.exists()) { t("目标位置已存在同名项目") }
        if (!target.exists() && !target.mkdirs()) throw IllegalStateException("无法创建目标目录：${target.absolutePath}")
        require(isWritableDir(targetRoot.absolutePath)) { t("目标目录不可写") }

        val allFiles = source.walkTopDown().toList()
        val dirs = allFiles.filter { it.isDirectory }
        val files = allFiles.filter { it.isFile }
        val totalSteps = dirs.size + files.size + 1
        var done = 0

        // 1) 先建全部目录结构
        dirs.forEach { dir ->
            val rel = dir.relativeTo(source).path
            val dest = if (rel.isEmpty() || rel == ".") target else File(target, rel)
            dest.mkdirs()
            done++; onProgress(done.toFloat() / totalSteps * 0.85f)
        }
        // 2) 逐个复制文件
        files.forEach { file ->
            val dest = File(target, file.relativeTo(source).path)
            dest.parentFile?.mkdirs()
            file.copyTo(dest, overwrite = true)
            done++; onProgress(done.toFloat() / totalSteps * 0.85f)
        }
        // 3) 校验：文件数量一致且大小一致
        val targetFiles = target.walkTopDown().filter { it.isFile }.toList()
        val sizesMatch = files.size == targetFiles.size && files.all { src ->
            val dst = File(target, src.relativeTo(source).path)
            dst.exists() && dst.length() == src.length()
        }
        if (!sizesMatch) {
            target.deleteRecursively()
            throw IllegalStateException(t("复制校验失败，已回滚，源项目未改动"))
        }
        onProgress(0.95f)

        // 4) 校验通过，删除源目录
        source.deleteRecursively()
        done++; onProgress(1f)

        return readMeta(target) ?: ProjectInfo(target.name, target.absolutePath, project.template, project.createdAt)
    }

    fun readFile(path: String): String = File(path).readText()

    /** 统计文件总行数（流式，不把全文读进内存）。 */
    fun countLines(path: String): Int = try {
        var n = 0
        File(path).forEachLine { n++ }
        n
    } catch (e: Exception) {
        0
    }

    /**
     * 读取文件的行区间 [fromLine, fromLine + maxLines)，返回拼接文本（保留行尾换行）。
     * 这是分块加载的核心：只扫描并读取指定区间的行，不一次性把整文件加载进内存。
     */
    fun readLines(path: String, fromLine: Int, maxLines: Int): String {
        val sb = StringBuilder()
        var lineNo = 0
        var taken = 0
        File(path).forEachLine { line ->
            lineNo++
            if (lineNo >= fromLine && taken < maxLines) {
                sb.append(line).append('\n')
                taken++
            }
        }
        return sb.toString()
    }

    fun writeFile(path: String, content: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    /** 文件名/目录名合法性：不允许分隔符与路径穿越。 */
    private val NODE_NAME_REGEX = Regex("[^/\\\\\\x00]{1,60}")

    private fun safeNodeName(name: String): String {
        val trimmed = name.trim()
        require(NODE_NAME_REGEX.matches(trimmed)) { "名称不能为空或包含 / \\ 等非法字符（1-60 位）" }
        require(trimmed != "." && trimmed != "..") { t("名称无效") }
        return trimmed
    }

    /** 解析父目录绝对路径，确保它位于项目根之内，防路径穿越。 */
    private fun safeParent(project: ProjectInfo, parentPath: String): File {
        val root = File(project.path).canonicalFile
        val parent = (if (parentPath.isBlank()) root else File(parentPath)).canonicalFile
        require(parent == root || parent.path.startsWith(root.path + File.separator)) {
            t("目标目录不在项目内")
        }
        require(parent.isDirectory) { t("目标目录不存在") }
        return parent
    }

    /** 在指定目录下新建子文件夹，返回新目录绝对路径。 */
    fun createFolder(project: ProjectInfo, parentPath: String, name: String): String {
        val dir = File(safeParent(project, parentPath), safeNodeName(name))
        require(!dir.exists()) { t("同名文件/文件夹已存在") }
        require(dir.mkdirs()) { t("创建文件夹失败") }
        return dir.absolutePath
    }

    /** 在指定目录下新建文件（可带内容），返回新文件绝对路径。 */
    fun createFile(
        project: ProjectInfo,
        parentPath: String,
        name: String,
        content: String = "",
    ): String {
        val f = File(safeParent(project, parentPath), safeNodeName(name))
        require(!f.exists()) { t("同名文件/文件夹已存在") }
        f.writeText(content)
        return f.absolutePath
    }

    /** 删除文件或空/非空目录（递归）。禁止删除项目根目录。 */
    fun deleteNode(project: ProjectInfo, path: String): Boolean {
        val root = File(project.path).canonicalFile
        val target = File(path).canonicalFile
        require(target != root && target.path.startsWith(root.path + File.separator)) {
            t("不能删除项目根目录")
        }
        return target.deleteRecursively()
    }

    /** 从系统 Uri 导入图片/音频到指定目录，返回新文件绝对路径。 */
    fun importAsset(
        context: Context,
        project: ProjectInfo,
        parentPath: String,
        name: String,
        uri: Uri,
    ): String {
        val f = File(safeParent(project, parentPath), safeNodeName(name))
        require(!f.exists()) { t("同名文件已存在") }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { t("无法读取所选文件") }
            f.outputStream().use { out -> input.copyTo(out) }
        }
        return f.absolutePath
    }

    /** 遍历项目目录为 UI 树；隐藏 . 开头的元数据目录；目录在前、同类按名排序。 */
    fun projectTree(project: ProjectInfo): List<FileNode> {
        val root = File(project.path)
        fun walk(dir: File): List<FileNode> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            // .rpyc 是 Ren'Py 编译生成的二进制字节码，不是可编辑源文件；
            // 编译后项目里会生成大量这类文件，显示在树里只会干扰，直接过滤掉。
            .filterNot { it.name.endsWith(".rpyc", ignoreCase = true) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { f ->
                    if (f.isDirectory) {
                        FileNode(f.name, true, walk(f), f.absolutePath)
                    } else {
                        FileNode(f.name, false, emptyList(), f.absolutePath)
                    }
                }
        return walk(root)
    }

    private fun readMeta(dir: File): ProjectInfo? {
        if (!dir.isDirectory) return null
        val meta = File(File(dir, META_DIR), META_FILE)
        if (!meta.exists()) {
            return ProjectInfo(dir.name, dir.absolutePath, TEMPLATE_NAMES[0], dir.lastModified())
        }
        return try {
            val json = JSONObject(meta.readText())
            ProjectInfo(
                name = json.optString("name", dir.name),
                path = dir.absolutePath,
                template = json.optString("template", TEMPLATE_NAMES[0]),
                createdAt = json.optLong("createdAt", dir.lastModified()),
                lastOpenedAt = json.optLong("lastOpenedAt", 0L),
            )
        } catch (e: Exception) {
            ProjectInfo(dir.name, dir.absolutePath, TEMPLATE_NAMES[0], dir.lastModified())
        }
    }

    /** 各模板生成的初始文件（相对项目根路径 -> 内容）。 */
    private fun filesFor(templateIndex: Int): Map<String, String> = when (templateIndex) {
        1 -> mapOf( // 视觉小说
            "game/script.rpy" to VISUAL_NOVEL_SCRIPT,
            "game/characters.rpy" to CHARACTERS,
            "game/options.rpy" to OPTIONS,
        )
        2 -> mapOf( // 示例项目
            "game/script.rpy" to SAMPLE_SCRIPT,
            "game/characters.rpy" to CHARACTERS,
            "game/options.rpy" to OPTIONS,
            "README.md" to README,
        )
        else -> mapOf( // 空白项目
            "game/script.rpy" to BLANK_SCRIPT,
            "game/options.rpy" to OPTIONS,
        )
    }

    private val BLANK_SCRIPT = """
        # 新的 Ren'Py 项目
        # 从 label start 开始编写你的故事

        label start:
            t("你好，世界！")
            return
    """.trimIndent()

    private val VISUAL_NOVEL_SCRIPT = """
        # 视觉小说模板

        define a = Character("Alice", color="#c8ffc8")
        define m = Character(t("我"))

        label start:
            scene bg room
            with fade

            t("今天又是普通的一天……")

            show alice at center
            a t("嗨！你也在这里吗？")

            menu:
                t("打招呼"):
                    m t("你好，Alice。")
                    a t("很高兴认识你！")
                t("沉默不语"):
                    m "……"

            t("故事从这里开始，尽情创作吧。")
            return
    """.trimIndent()

    private val SAMPLE_SCRIPT = """
        # 示例项目 —— Ren'PyndIDE
        # 展示基础对话、角色、选项与跳转

        define a = Character("Alice", color="#c8ffc8")
        define m = Character(t("我"))

        label start:
            scene bg classroom
            with fade

            t("第一章：初遇")

            show alice at center
            a t("嗨！你也在这上课吗？")

            menu:
                t("打招呼"):
                    m t("你好，Alice。")
                    a t("很高兴认识你！")
                    $ affection = 10
                t("沉默不语"):
                    m "……"
                    a t("你好像有点紧张。")

            if affection >= 10:
                a t("我觉得我们会成为好朋友。")
            else:
                a t("那下次见啦。")

            jump chapter2

        label chapter2:
            scene bg park
            with dissolve
            t("公园里的风轻轻吹过。")
            return
    """.trimIndent()

    private val CHARACTERS = """
        # 角色定义
        define a = Character("Alice", color="#c8ffc8")
        define b = Character("Bob", color="#ffc8c8")
        define m = Character(t("我"))
    """.trimIndent()

    private val OPTIONS = """
        # 项目配置（精简）
        define config.name = t("我的视觉小说")
        define config.screen_width = 1280
        define config.screen_height = 720
    """.trimIndent()

    private val README = """
        # 我的 Ren'Py 项目

        用 Ren'PyndIDE 创建。

        - game/script.rpy —— 主脚本，从 label start 开始
        - game/characters.rpy —— 角色定义
        - game/options.rpy —— 项目配置
    """.trimIndent()
}
