package com.renpy.mobile.model

import com.renpy.mobile.i18n.t

/**
 * 项目树节点：文件树里的一个条目（目录或文件），纯展示用。
 */
data class FileNode(
    val name: String,
    val isDirectory: Boolean,
    val children: List<FileNode> = emptyList(),
    /** 文件系统绝对路径；mock 数据留空。 */
    val path: String = "",
)

/** 文件类型：决定用代码编辑器还是媒体查看器打开。 */
enum class FileKind { TEXT, IMAGE, AUDIO, UNKNOWN }

/** 按扩展名识别文件类型。图片/音频不按文本读取，交给对应查看器。 */
fun kindOf(name: String): FileKind = when {
    name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
        name.endsWith(".jpeg", true) || name.endsWith(".gif", true) ||
        name.endsWith(".webp", true) || name.endsWith(".bmp", true) -> FileKind.IMAGE
    name.endsWith(".ogg", true) || name.endsWith(".wav", true) ||
        name.endsWith(".mp3", true) || name.endsWith(".m4a", true) ||
        name.endsWith(".flac", true) || name.endsWith(".aac", true) -> FileKind.AUDIO
    name.endsWith(".rpy", true) || name.endsWith(".py", true) ||
        name.endsWith(".txt", true) || name.endsWith(".md", true) ||
        name.endsWith(".rpyi", true) || name.endsWith(".rpym", true) ||
        name.endsWith(".json", true) || name.endsWith(".yml", true) ||
        name.endsWith(".yaml", true) || name.endsWith(".cfg", true) ||
        name.endsWith(".ini", true) || name.endsWith(".xml", true) ||
        name.endsWith(".html", true) || name.endsWith(".css", true) ||
        name.endsWith(".js", true) || name.endsWith(".sh", true) -> FileKind.TEXT
    name.contains('.') -> FileKind.UNKNOWN
    else -> FileKind.TEXT
}

/**
 * 代码标签页：顶部/编辑区打开的一个文件。
 */
data class CodeTab(
    val fileName: String,
    /** 当前已加载进内存的文本（分块模式下是文件前 [loadedLines] 行）。 */
    val content: String,
    /** 文件系统绝对路径；mock 数据留空。 */
    val path: String = "",
    /** 是否有未写回磁盘的改动。 */
    val dirty: Boolean = false,
    /** 文件类型，决定查看器。 */
    val kind: FileKind = FileKind.TEXT,
    /** 文件总行数；-1 表示全文模式（小文件 / mock / 非文本文件）。 */
    val totalLines: Int = -1,
    /** 当前已加载的行数；-1 表示全文已加载。 */
    val loadedLines: Int = -1,
) {
    /** 是否还有未加载的行（分块模式且未到文件末尾）。 */
    val hasMoreLines: Boolean
        get() = totalLines > loadedLines && loadedLines > 0
}

/**
 * 日志级别，决定日志面板里颜色与图标。
 */
enum class LogLevel { INFO, WARNING, ERROR, SUCCESS }

/**
 * 日志条目。
 */
data class LogEntry(
    val level: LogLevel,
    val time: String,
    val message: String,
    /** 可选：关联源文件绝对路径，点击日志可跳转（编译错误解析出来）。 */
    val file: String = "",
    /** 可选：关联源文件行号（1-based），0 表示未指定。 */
    val line: Int = 0,
) {
    val hasLocation: Boolean get() = file.isNotEmpty() && line > 0
}

// ── 以下均为 UI 展示用假数据 ────────────────────────────────

val mockProjectTree = listOf(
    FileNode("game", true, listOf(
        FileNode("script.rpy", false),
        FileNode("script_chapter2.rpy", false),
        FileNode("characters.rpy", false),
        FileNode("options.rpy", false),
        FileNode("gui.rpy", false),
        FileNode("screens.rpy", false),
        FileNode("images", true, listOf(
            FileNode("bg_classroom.png", false),
            FileNode("bg_park.png", false),
            FileNode("sprite_alice.png", false),
        )),
        FileNode("audio", true, listOf(
            FileNode("bgm_theme.ogg", false),
            FileNode("sfx_click.wav", false),
        )),
        FileNode("tl", true, listOf(
            FileNode("zh_CN", true, listOf(
                FileNode("script.rpy", false),
            )),
        )),
    )),
    FileNode("lib", true, listOf(
        FileNode("python3.12", true),
    )),
    FileNode("renpy", true),
    FileNode("README.md", false),
)

val mockCodeContent = """# 第一章：初遇
# 这是一个用于 UI 预览的示例脚本

define a = Character("Alice", color="#c8ffc8")
define m = Character(t("我"))

label start:
    scene bg classroom
    with fade

    t("今天又是普通的一天……")

    show sprite_alice at center
    a t("嗨！你也在这里上课吗？")

    menu:
        t("打招呼"):
            m t("你好，Alice。")
            a t("很高兴认识你！")
        t("沉默不语"):
            m "……"
            a t("唔，你好像有点紧张。")

    if affection >= 10:
        a t("我觉得我们以后会是很要好的朋友。")
    else:
        a t("那下次再见啦。")

    hide sprite_alice
    jump chapter2

label chapter2:
    scene bg park
    with dissolve
    t("公园里的风轻轻吹过。")
    return
""".trimIndent()

val mockTabs = listOf(
    CodeTab("script.rpy", mockCodeContent),
    CodeTab("characters.rpy", """define a = Character("Alice", color="#c8ffc8")
define b = Character("Bob", color="#ffc8c8")
define m = Character(t("我"))
""".trimIndent()),
    CodeTab("options.rpy", """define config.name = t("我的视觉小说")
define config.screen_width = 1280
define config.screen_height = 720
""".trimIndent()),
)

val mockLogs = listOf(
    LogEntry(LogLevel.INFO, "10:23:01", t("项目已加载: /storage/MyGame")),
    LogEntry(LogLevel.SUCCESS, "10:23:02", t("编译完成，共 3 个脚本文件")),
    LogEntry(LogLevel.INFO, "10:23:02", t("开始运行 label start ...")),
    LogEntry(LogLevel.WARNING, "10:23:05", t("line 24: 变量 'affection' 未定义，将使用默认值 0")),
    LogEntry(LogLevel.ERROR, "10:23:09", t("line 41: 无法找到标签 'chapter3'")),
)
