package com.renpy.mobile.ai

import com.renpy.mobile.i18n.t
import com.renpy.mobile.i18n.tr

/**
 * 工具（skill）分组注册表：实现「渐进式工具加载」+「设备能力过滤」。
 *
 * 背景：renpy-mcp 有 80 个工具，本 App 运行在 Android 上，其中一部分工具
 * （launch_preview / build_distribution / warp_to 等）依赖 `asyncio.create_subprocess_exec`
 * 去启动桌面版 Ren'Py SDK launcher —— 手机上根本没有该可执行文件，调用必定失败，
 * 还会让 AI 陷入「反复尝试→报错→放弃」的死循环，污染对话。
 *
 * 因此本注册表只暴露在「纯文件读写 + 静态分析」范畴内、设备上真正可用的工具，
 * 并按用途分组：core 默认加载，其余按 load_tool_group 按需加载。
 * 预览 / 打包 / 运行游戏请走 App 自带的界面，不通过 MCP 工具。
 */
object ToolGroups {

    const val GROUP_CORE = "core"
    const val META_LIST_GROUPS = "list_tool_groups"
    const val META_LOAD_GROUP = "load_tool_group"

    /**
     * 设备上不可用的工具黑名单（依赖桌面 SDK 子进程 / 图形栈 / 系统级能力）。
     * 即使静态 schema 里存在，也绝不会注入给 LLM。
     */
    private val DEVICE_UNSUPPORTED: Set<String> = setOf(
        "launch_preview", "stop_preview", "get_preview_status", "warp_to",
        "set_drafting_mode", "build_distribution", "generate_translation_scaffolding",
    )

    fun isAvailable(tool: String): Boolean = tool !in DEVICE_UNSUPPORTED

    /** 组名 -> 该组工具名列表（已剔除设备不可用工具）。 */
    val GROUPS: LinkedHashMap<String, List<String>> = linkedMapOf(
        GROUP_CORE to listOf(
            "get_project_overview", "list_labels", "read_label", "read_raw_file",
            "get_lint_report", "find_invalid_jumps", "find_undefined_characters",
            "find_undefined_screens", "find_missing_assets", "refresh_project",
            "add_label", "add_say", "add_jump", "add_call", "add_menu",
            "update_menu_choice", "apply_unified_diff",
        ),
        "read" to listOf(
            "read_label_tree", "list_characters", "read_character", "list_variables",
            "list_screens", "read_screen", "list_images", "list_audio", "find_references",
            "read_canvas_positions", "find_unused_characters", "find_unreachable_labels",
            "read_ignored_diagnostics", "get_choice_graph", "get_translation_coverage",
            "find_stale_translations", "get_recent_edits", "get_media_invariants",
            "get_scaffold_status",
        ),
        "edit" to listOf(
            "add_condition_branch", "set_variable_default", "rename_label", "add_audio_play",
            "add_image_alias", "add_character", "update_character", "add_layered_image",
            "add_transform", "add_screen", "update_options_field", "set_canvas_positions",
            "set_ignored_diagnostics", "add_menu_branch", "redirect_jump", "delete_label",
            "add_pause", "add_setvar", "add_show", "add_with_effect", "add_flash",
            "set_start_target",
        ),
        "scenario" to listOf(
            "new_project", "create_scene", "create_choice_node", "create_route",
            "add_dialogue_block", "swap_background", "add_character_to_scene", "set_scene_music",
            "add_inventory_item_scaffold", "add_minigame_screen_scaffold", "add_screen_layout",
            "add_stage", "add_imagemap", "repair_scaffold",
            // generate_translation_scaffolding 已剔除（依赖 renpy_sdk.run 子进程）
        ),
        // preview / build / advanced 组已整体移除：依赖桌面 SDK 子进程，设备不可用。
    ).mapValues { (_, tools) -> tools.filter { isAvailable(it) } } as LinkedHashMap<String, List<String>>

    /** 组 -> 一句话说明，供 list_tool_groups 返回。 */
    private val GROUP_DESC = mapOf(
        GROUP_CORE to t("项目概览、读取脚本、搜索问题、常用编辑与 diff（默认已加载）"),
        "read" to t("深入读取：标签树/角色/变量/屏幕/图片音频/引用/诊断/翻译/编辑历史"),
        "edit" to t("精细编辑：条件分支/变量/重命名/角色/屏幕/跳转/删除/特效等"),
        "scenario" to t("场景与脚手架：新建项目/场景/选项/路线/背景/音乐/inventory/小游戏"),
    )

    /** 工具名 -> 所属组。 */
    private val toolToGroup: Map<String, String> = buildMap {
        for ((group, tools) in GROUPS) for (t in tools) put(t, group)
    }

    fun groupOf(tool: String): String? = toolToGroup[tool]

    /** 供 list_tool_groups 元工具返回的文本。 */
    fun describeGroups(): String = buildString {
        appendLine(t("可用工具组（按需用 load_tool_group 加载，加载后下一轮即可调用）："))
        for ((group, tools) in GROUPS) {
            appendLine("- $group：${GROUP_DESC[group] ?: ""}（${tools.size} 个）")
        }
        appendLine("提示：core 组默认已加载。需要其他组的工具时，先调用 load_tool_group(group=\"组名\")。")
        appendLine(t("注意：运行/预览游戏请用 App 底部的运行按钮，打包 APK 请用 App 的打包界面，这两项不通过工具完成。"))
    }

    /** 供 load_tool_group 返回的文本（列出组内工具名）。 */
    fun describeGroup(group: String): String? {
        val tools = GROUPS[group] ?: return null
        return tr("已加载工具组 [{0}]：{1}。你现在可以调用这些工具。", group, tools.joinToString(", "))
    }

    fun isValidGroup(group: String) = GROUPS.containsKey(group)
}
