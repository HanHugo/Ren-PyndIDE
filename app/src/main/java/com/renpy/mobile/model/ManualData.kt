package com.renpy.mobile.model

/**
 * Ren'Py 离线手册数据。
 * 内容整理自 Ren'Py 官方文档（language_basics / conditional / text / 各 statements 章节），
 * 随包内置、离线可用。仅作速查，详细语义以官方文档为准。
 */

data class ManualEntry(
    /** 条目名（关键字 / 标签 / 语法点），也是搜索主字段。 */
    val title: String,
    /** 一句话摘要。 */
    val summary: String,
    /** 官方语法定义。 */
    val syntax: String,
    /** 简短说明。 */
    val description: String,
    /** 可复制的示例代码。 */
    val example: String = "",
)

data class ManualCategory(
    val id: String,
    val title: String,
    val description: String,
    val entries: List<ManualEntry>,
)

object ManualData {

    val categories: List<ManualCategory> = listOf(
        ManualCategory(
            id = "script",
            title = "脚本语句",
            description = "Ren'Py 脚本语言的核心语句（官方 Reference Manual）。",
            entries = listOf(
                ManualEntry(
                    "label", "定义脚本标签/流程入口",
                    "label name (parameters)?:",
                    "为脚本中的一个位置命名，供 jump / call 跳转。名为 start 的标签是游戏开始处。可带参数以接收 call 传入的值。",
                    "label start:\n    \"游戏从这里开始。\"\n\nlabel chapter2:\n    return",
                ),
                ManualEntry(
                    "say", "对话/旁白（无关键字）",
                    "[character] \"text\" (with transition)?",
                    "对白语句是唯一不以关键字开头的语句。可为一个字符串（旁白/内心独白），或两个字符串（角色名 + 台词），也可用已定义的角色对象。",
                    "\"这是旁白。\"\n\"Eileen\" \"你好！\"\ne \"嗨，我是 Eileen。\"",
                ),
                ManualEntry(
                    "define", "定义变量（初始化时执行）",
                    "define name = python_expression",
                    "在游戏启动时（init 阶段）求值一次并赋值。常用于定义角色对象与配置。",
                    "define e = Character(\"Eileen\", color=\"#c8ffc8\")\ndefine config.name = \"我的游戏\"",
                ),
                ManualEntry(
                    "default", "声明默认值",
                    "default name = python_expression",
                    "当存档中不存在该变量时才赋予默认值，适合存档友好的游戏状态变量。",
                    "default affection = 0\ndefine e = Character(\"Eileen\")",
                ),
                ManualEntry(
                    "scene", "清屏并显示场景",
                    "scene image_name (onlayer layer)? (with transition)?",
                    "清除当前图层（默认 master）所有图像后显示指定图像，常用于切换背景。",
                    "scene bg classroom\nscene bg park with fade",
                ),
                ManualEntry(
                    "show", "显示图像",
                    "show image_name (at t)? (as a)? (behind b)? (onlayer l)? (zorder n)? (with t)?",
                    "在图层上显示一个图像。可用 at 指定 transform、as 指定化名、behind 指定层级顺序、zorder 指定绘制顺序。表达式形式用 show expression。",
                    "show eileen happy at center\nshow eileen happy as e2 behind bg",
                ),
                ManualEntry(
                    "hide", "隐藏图像",
                    "hide image_name (onlayer layer)? (with transition)?",
                    "从图层移除图像。按 tag 隐藏；可用 as 指定化名。",
                    "hide eileen\nhide eileen with dissolve",
                ),
                ManualEntry(
                    "with", "应用转场",
                    "with simple_expression",
                    "对整个画面应用转场效果。常见内置转场：fade、dissolve、wipe、move。",
                    "with fade\nwith dissolve\nwith Pause(1.0)",
                ),
                ManualEntry(
                    "menu", "分支选项",
                    "menu (name)?:  # 块内含 string: 选项",
                    "向玩家展示选项菜单。每个选项是一个以冒号结尾的字符串，后接选项被选中时执行的语句块；可用 if 条件控制显示。",
                    "menu:\n    \"去公园\":\n        \"好呀！\"\n    \"留在家里\" if tired:\n        \"休息一下。\"",
                ),
                ManualEntry(
                    "if / elif / else", "条件分支",
                    "if expression: ... (elif expression: ...)* (else: ...)?",
                    "按表达式真假有选择地执行语句块。if/elif 后跟条件，所有子句以冒号结尾。",
                    "if points >= 10:\n    jump best\nelif points >= 5:\n    jump good\nelse:\n    jump bad",
                ),
                ManualEntry(
                    "while", "循环",
                    "while expression:",
                    "当表达式为真时重复执行语句块。每次执行到块末会重新求值。没有内建 for/break/continue，可用 jump 模拟。",
                    "$ count = 3\nwhile count > 0:\n    \"[count]...\"\n    $ count -= 1",
                ),
                ManualEntry(
                    "for", "Python 循环（python 块内）",
                    "for target in iterable:  # python 块内",
                    "Ren'Py 脚本层没有 for；在 python 块或 init python 中可使用 Python 的 for 语句遍历序列。",
                    "python:\n    for i in range(3):\n        store.count += 1",
                ),
                ManualEntry(
                    "jump", "无条件跳转",
                    "jump name | expression simple_expression (from name)?",
                    "把控制转移到指定标签。表达式形式可动态计算目标。",
                    "jump chapter2\njump expression target_label",
                ),
                ManualEntry(
                    "call", "调用子流程",
                    "call name (arguments)? (from name)?",
                    "跳转到标签并在其 return 后返回调用点继续执行。可传参数给带参数的 label。",
                    "call morning\ncall express \"hi\" from _call_expr",
                ),
                ManualEntry(
                    "return", "返回/结束",
                    "return (expression)?",
                    "从 call 返回调用点；在主流程中结束游戏。可返回一个值。",
                    "return\nreturn 42",
                ),
                ManualEntry(
                    "pass", "空语句",
                    "pass",
                    "占位语句，什么都不做。用于需要语句块但无需实际动作的场景。",
                    "menu:\n    \"跳过\":\n        pass",
                ),
                ManualEntry(
                    "init / init python", "初始化语句块",
                    "init (priority)?:  |  init (priority)? python (hide)?:",
                    "在游戏加载时执行初始化代码，顺序任意（可用数字优先级控制）。init python 内是 Python 代码。不要在 init 中显示图像或与玩家交互。",
                    "init:\n    $ config.version = 1\n\ninit python:\n    FLAG = True",
                ),
                ManualEntry(
                    "python / $", "内嵌 Python",
                    "$ python_statement  |  python (hide)?:",
                    "以 $ 开头的单行是单条 Python 语句；python 块语句可写多行 Python 代码（含控制结构）。hide 可阻止局部变量写入 store。",
                    "$ affection += 1\npython:\n    for i in range(3):\n        affection += i",
                ),
                ManualEntry(
                    "pause", "暂停",
                    "pause (expression)?",
                    "暂停执行，等待玩家点击；若给定秒数则按时间自动继续。",
                    "pause\npause 1.5",
                ),
                ManualEntry(
                    "play / stop / queue", "声音播放控制",
                    "play channel file (fadeout f)? (fadein f)? (loop|noloop)? (if_changed)?\nstop channel (fadeout f)?\nqueue channel file (loop|noloop)?",
                    "play 在声道播放声音并停止原声音；queue 排队等待当前声音结束后播放；stop 停止声道。常用声道：music、sound、voice、ambient。",
                    "play music \"bgm.ogg\" fadein 1.0 loop\nstop music fadeout 2.0",
                ),
                ManualEntry(
                    "voice", "语音声道",
                    "voice file (if_changed)?",
                    "在 voice 声道播放语音文件，配合角色语音标签使用。",
                    "voice \"hello.ogg\"",
                ),
                ManualEntry(
                    "window", "对话框显示",
                    "window (show|hide) (transition)?",
                    "显示或隐藏对话框窗口。",
                    "window hide\nwindow show with dissolve",
                ),
                ManualEntry(
                    "image", "定义图像",
                    "image name = expression  |  image name: ...",
                    "定义一个图像（可直接是文件，也可用 ATL 变换块组合）。",
                    "image logo = \"logo.png\"\nimage eileen = \"eileen.png\"",
                ),
                ManualEntry(
                    "transform", "定义变换（ATL）",
                    "transform name (parameters)?:",
                    "用 ATL（动画与变换语言）定义一个可复用的 transform，供 show at 使用。",
                    "transform pop:\n    zoom 0.5\n    linear 0.3 zoom 1.0",
                ),
                ManualEntry(
                    "screen", "定义界面",
                    "screen name (parameters)?:",
                    "定义一个可显示的界面（Screen Language），用于 HUD、菜单等。用 show screen / call screen 调用。",
                    "screen hud():\n    text \"HP: [hp]\"",
                ),
                ManualEntry(
                    "scene / camera / layer", "图层与相机",
                    "camera (layer)? (at t)?: ...\nscene (onlayer l)? ...",
                    "camera 语句控制相机（3D/变换）；图层（layer）由 config.layers 定义，show/scene 可用 onlayer 指定目标图层。",
                    "camera at flip\nscene bg onlayer master",
                ),
            ),
        ),
        ManualCategory(
            id = "texttag",
            title = "文本标签",
            description = "写在字符串内的 {标签}，用于控制文字样式与对话节奏（官方 Text 章节）。",
            entries = listOf(
                ManualEntry("{b} / {i} / {u} / {s}", "粗体 / 斜体 / 下划线 / 删除线",
                    "{b}…{/b}  {i}…{/i}  {u}…{/u}  {s}…{/s}",
                    "成对出现的样式标签。关闭用 / 前缀；标签遵循后进先出，文本块结束时未关闭标签会自动关闭。",
                    "\"这是 {b}粗体{/b} 与 {i}斜体{/i}。\""),
                ManualEntry("{color}", "文字颜色",
                    "{color=#rrggbb}…{/color}",
                    "设置文字颜色，支持 #rgb、#rgba、#rrggbb、#rrggbbaa。",
                    "\"{color=#f00}红色{/color}文字。\""),
                ManualEntry("{size}", "字号",
                    "{size=n}…{/size}  {size=+n}…{/size}  {size=*f}…{/size}",
                    "设置字号（像素）。可绝对值、相对值 +n/-n，或倍数 *f（如 *2、*0.5）。",
                    "\"{size=32}大字体{/size} {size=*2}两倍{/size}\""),
                ManualEntry("{font}", "字体",
                    "{font=filename}…{/font}",
                    "临时使用指定字体文件，也可用 config.font_name_map 定义的别名。",
                    "\"{font=fonts/zh.ttf}中文{/font}\""),
                ManualEntry("{alpha}", "透明度",
                    "{alpha=a}…{/alpha}",
                    "设置透明度 0.0（全透明）到 1.0（不透明），支持 +、-、*f 相对调整。",
                    "\"{alpha=0.5}半透明{/alpha}\""),
                ManualEntry("{w}", "等待点击/时间",
                    "{w}  {w=seconds}",
                    "等待玩家点击后继续；带数值则等待指定秒数后自动继续。",
                    "\"你好{w=1.0}再见。\""),
                ManualEntry("{p}", "段落暂停",
                    "{p}  {p=seconds}",
                    "结束当前段落并等待点击（或指定秒数）后显示下一段。",
                    "\"第一段{p}第二段。\""),
                ManualEntry("{nw}", "不等待",
                    "{nw}  {nw=seconds}",
                    "本行文字显示完后无需点击即自动前进（可指定延迟秒数）。",
                    "\"自动继续{nw}\""),
                ManualEntry("{fast}", "立即显示",
                    "{fast}",
                    "使此前文字立即全部显示（即使在慢速文本模式下）。",
                    "\"快速显示之前的内容{fast}\""),
                ManualEntry("{clear}", "清空 NVL",
                    "{clear}",
                    "仅用于 NVL 独白模式，单独成行时类似 nvl clear，但不结束文本块。",
                    "\"{clear}\""),
                ManualEntry("{image}", "行内图片",
                    "{image=filename_or_image_name}",
                    "在文本中插入图片（自闭合），图片高度应为单行文本高度。",
                    "\"看这里 {image=heart}\""),
                ManualEntry("{space} / {vspace}", "空白",
                    "{space=pixels}  {vspace=pixels}",
                    "插入指定像素的水平 / 垂直空白（自闭合）。",
                    "\"A{space=20}B\""),
                ManualEntry("{rb} / {rt}", "注音（Ruby）",
                    "{rb}底字{/rb}{rt}注音{/rt}  或  【底字|注音】",
                    "给汉字加注音（振假名）。也可用全角/半角竖线的 【】 语法。文档中没有 {ruby} 标签。",
                    "\"{rb}東京{/rb}{rt}Tokyo{/rt} 或 【東京|Tokyo】\""),
                ManualEntry("{a}", "超链接",
                    "{a=jump:label}…{/a}",
                    "创建超链接，参数可为 jump:/call:/show:/showmenu: 或 URL。",
                    "\"点击{a=jump:help}帮助{/a}\""),
                ManualEntry("{cps}", "显示速度",
                    "{cps=n}…{/cps}",
                    "设置文字显示速度（字符/秒），支持 *f 倍数。",
                    "\"{cps=60}快速文字{/cps}\""),
                ManualEntry("{{ / [[ ", "转义字面量",
                    "{{ 输出 {    [[ 输出 [",
                    "用双写来输出字面量的 { 与 [；用 \\{、\\[ 在字符串中转义也可避免被当作标签/插值。",
                    "\"字面大括号 {{ 和方括号 [[\""),
            ),
        ),
        ManualCategory(
            id = "string",
            title = "字符串与插值",
            description = "字符串写法、转义与 [变量] 插值（官方 language_basics / text 章节）。",
            entries = listOf(
                ManualEntry("字符串引号", "三种引号",
                    "\"…\"  '…'  `…`",
                    "字符串可用双引号、单引号或反引号，须以相同引号闭合。Ren'Py 字符串中的连续空白/换行会压缩为单个空格（可用反斜杠空格保留）。",
                    "\"你好\"\n'你好'\n`跨行的\n字符串`"),
                ManualEntry("转义", "反斜杠转义",
                    "\\\"  \\'  \\%  \\[  \\{  \\n  \\ \\ ",
                    "反斜杠转义引号与特殊字符：\\% 输出 %、\\[ 输出 [、\\{ 输出 {、\\n 换行；反斜杠加空格可保留空格。",
                    "\"他说 \\\"你好\\\"\"\n\"换行\\n下一行\""),
                ManualEntry("[变量] 插值", "显示变量值",
                    "\"文本 [variable] 文本\"",
                    "显示文本前把 [...] 中的表达式替换为实际值。可用变量、属性、下标与格式说明；[[ 输出字面 [。",
                    "g \"欢迎，[playername]！\"\ne \"得分 [100.0 * p / m:.2]%\"\ne \"首个名字 [player.names[0]]\""),
                ManualEntry("插值转换标志", "!s !r !q !t 等",
                    "[expr!flag]",
                    "格式化插值结果：!s str()、!r repr()、!q 安全引用标签、!t 翻译、!i 递归插值、!u/!l/!c 大小写。标志顺序不影响结果。",
                    "e \"你好，[name!q]\"\ne \"[mood!t]\""),
                ManualEntry("前缀 r", "原始字符串",
                    "r\"…\"",
                    "支持 r 前缀（类似 Python 原始字符串）。u、b、f 前缀不受支持。",
                    "e r\"路径 C:\\new\""),
            ),
        ),
        ManualCategory(
            id = "basic",
            title = "基础语法",
            description = "文件结构、注释、缩进与块等语言基础（官方 Language Basics）。",
            entries = listOf(
                ManualEntry("# 注释", "行注释",
                    "# 注释内容",
                    "以 # 开头到行尾为注释；# 位于字符串内时不是注释。注释被 Ren'Py 忽略。",
                    "# 整行注释\nshow black  # 行尾注释\n\"# 这不是注释\""),
                ManualEntry("缩进与块", "空格分组",
                    "语句回车 → 缩进的行构成块",
                    "缩进只能用空格（不能用 Tab）。缩进更深的行开启新块；同块内缩进一致；出现更少缩进的非空行时块结束。",
                    "label start:\n    \"块内第一行\"\n    \"块内第二行\"\n\"回到顶层块\""),
                ManualEntry("逻辑行", "何时换行",
                    "以换行结束，除非行尾为 \\ 或有未闭合括号/字符串",
                    "逻辑行默认以换行结束；若行尾是反斜杠、括号未闭合、或处于字符串内，则延续到下一行。",
                    "\"跨行的\n字符串\"\n$ a = [1,\n        2]"),
                ManualEntry("名称 name", "标识符规则",
                    "字母/下划线开头，后接字母、数字、下划线",
                    "名称以字母或下划线开头，后接字母/数字/下划线；Unicode 字母可用。单下划线开头保留给 Ren'Py 内部，双下划线开头会做文件名相关改写。",
                    "define love_points = 0"),
                ManualEntry("名称 munging", "双下划线改写",
                    "__word → 文件专属名称",
                    "以两个下划线开头但不以两个下划线结尾的名称，会被改写成与文件名相关的形式，避免跨文件冲突。",
                    "define __score = 0  # 变为文件专属名"),
                ManualEntry(".rpy 文件", "脚本文件",
                    "game/ 目录下的 .rpy 文件",
                    "game/ 目录及其子目录中的 .rpy / .rpyc 文件共同组成脚本。启动时 .rpy 会编译为 .rpyc 加速加载。文件名须以字母或数字开头，且不能以 00 开头。",
                    "game/script.rpy\ngame/characters.rpy"),
            ),
        ),
    )

    /** 按关键词在标题/摘要/说明/语法中搜索；空查询返回全部分类。 */
    fun search(query: String): List<ManualCategory> {
        val q = query.trim()
        if (q.isEmpty()) return categories
        val kw = q.lowercase()
        return categories.mapNotNull { cat ->
            val matched = cat.entries.filter { e ->
                e.title.lowercase().contains(kw) ||
                    e.summary.lowercase().contains(kw) ||
                    e.description.lowercase().contains(kw) ||
                    e.syntax.lowercase().contains(kw) ||
                    e.example.lowercase().contains(kw)
            }
            if (matched.isEmpty()) null else cat.copy(entries = matched)
        }
    }
}
