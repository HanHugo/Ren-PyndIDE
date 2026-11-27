package com.renpy.mobile.ui

import java.io.File

/**
 * Ren'Py 布局静态解析器（布局助手的数据层）。
 *
 * 职责：
 *  - 解析项目里的 rpy 脚本，提取 screen 块及其中的 displayable 元素与定位属性；
 *  - 解析 gui.* / config.* 变量得到画布分辨率、字体、字号、颜色、图片资源；
 *  - 按 Ren'Py 官方 position style properties 公式计算每个元素的画布矩形；
 *  - 支持把改动后的数值“精确回写”到源码（只替换属性后面的数字，不动其它字符）。
 *
 * 严格不依赖 Compose，方便单独推理与测试。
 *
 * 官方规则（style_properties.html#position-style-properties）：
 *  - position 值：int = 像素；float = 父容器尺寸的比例（0.5 = 一半）。
 *  - xalign v ≡ xpos=v 且 xanchor=v；0=左 0.5=中 1=右。
 *  - xcenter v ≡ xpos=v 且 xanchor=0.5。
 *  - anchor 相对“元素自身”尺寸；pos 相对“父容器”尺寸。
 *  - 最终左上角 = pos*父尺寸 - anchor*自身尺寸 + offset。
 *  - xsize：int=像素；float=父宽比例；等价于同时设 xminimum/xmaximum。
 */
object RpyLayoutParser {

    // ───────────────────────── 数据模型 ─────────────────────────

    /** 值的单位：像素(int) / 比例(float) / 动态表达式(无法静态确定)。 */
    enum class ValueUnit { PIXEL, FRACTION, DYNAMIC }

    /** 一个定位属性的原始出处与解析值。offset 为字符级区间，用于精确回写。 */
    data class LayoutProperty(
        val name: String,
        val rawValue: String,
        val startOffset: Int,
        val endOffset: Int,
        val number: Float?,
        val unit: ValueUnit,
    ) {
        val isEditable: Boolean get() = number != null
    }

    enum class ElementKind {
        WINDOW, FRAME, TEXT, TEXTBUTTON, IMAGEBUTTON, BUTTON,
        ADD, IMAGE, VBOX, HBOX, GRID, FIXED, VIEWPORT, LABEL, OTHER,
    }

    /** 画布上的一个可编辑元素。 */
    data class LayoutElement(
        val key: String,               // 稳定 key：screenName/index
        val kind: ElementKind,
        val label: String,             // UI 显示名
        val properties: Map<String, LayoutProperty>,
        val imageRef: String?,         // add/scene 引用的图片名，如 "bg room"
        val textPreview: String?,      // text 内容预览
        val startLine: Int,
        val endLine: Int,
        val nested: Boolean,           // 是否嵌套在其它 displayable 内（浅色区分）
        val sourcePath: String,        // 所属源文件绝对路径（回写必须按文件区分 offset）
    )

    /** 一个 screen 块。 */
    data class LayoutScreen(
        val name: String,
        val startLine: Int,
        val elements: List<LayoutElement>,
    )

    /** 从 gui.rpy / options.rpy 解析出的主题。 */
    data class GuiTheme(
        val width: Int,
        val height: Int,
        val textFont: String?,
        val textSize: Int,
        val textColor: String?,
        val nameFont: String?,
        val nameSize: Int,
        val nameColor: String?,
    ) {
        companion object {
            val DEFAULT = GuiTheme(
                width = 1280, height = 720,
                textFont = null, textSize = 33, textColor = "#ffffff",
                nameFont = null, nameSize = 45, nameColor = "#ffffff",
            )
        }
    }

    /** 解析后的完整文档。sources 保存每个源文件的原始文本，回写按文件独立进行。 */
    data class LayoutDoc(
        val fileName: String,
        val primaryPath: String,
        val sources: Map<String, String>,
        val screens: List<LayoutScreen>,
        val theme: GuiTheme,
        val warnings: List<String>,
    ) {
        val allElements: List<LayoutElement> get() = screens.flatMap { it.elements }
        /** 兼容单文件视图：主文件源码。 */
        val source: String get() = sources[primaryPath] ?: sources.values.firstOrNull() ?: ""
    }

    /** 一次数值改动：把 [startOffset,endOffset) 替换为 newValue（sourcePath 指明改哪个文件）。 */
    data class ValueEdit(
        val sourcePath: String,
        val startOffset: Int,
        val endOffset: Int,
        val newValue: String,
    )

    /** 计算出的矩形，坐标是相对画布的像素（已乘上画布实际尺寸）。 */
    data class ElementRect(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )

    // ───────────────────────── 主入口 ─────────────────────────

    /**
     * 解析当前文件。若当前文件没有 screen 块，自动扫描项目 game/ 下其它 rpy
     * （优先 screens.rpy），把找到的 screen 一起纳入，主题从项目所有 rpy 汇总。
     */
    fun parseProject(projectPath: String, currentFilePath: String?): LayoutDoc {
        val gameDir = File(projectPath, "game")
        val allRpy = (gameDir.listFiles { f -> f.isFile && f.name.endsWith(".rpy", true) }
            ?: emptyArray()).sortedBy { it.name }

        // 主题：从所有 rpy 汇总（gui 变量可能分散在 gui.rpy / options.rpy / screens.rpy）
        val theme = parseTheme(allRpy)

        // 候选文件：当前文件优先，其次 screens.rpy，再其余
        val candidates = linkedMapOf<String, File>()
        currentFilePath?.let { File(it).takeIf { f -> f.isFile } }?.let { candidates[it.name] = it }
        allRpy.firstOrNull { it.name.equals("screens.rpy", true) }?.let { candidates[it.name] = it }
        allRpy.forEach { candidates[it.name] = it }

        val screens = mutableListOf<LayoutScreen>()
        val warnings = mutableListOf<String>()
        val sources = linkedMapOf<String, String>()
        val primaryPath = currentFilePath?.takeIf { File(it).isFile }
            ?: candidates.values.firstOrNull()?.absolutePath ?: ""
        var primaryName = File(primaryPath).name.ifBlank { "script.rpy" }

        for ((name, file) in candidates) {
            val src = try { file.readText() } catch (e: Exception) {
                warnings.add("读取 $name 失败：${e.message}"); continue
            }
            sources[file.absolutePath] = src
            // screen 块（元素声明 + 内联/缩进定位属性）
            val parsed = parseScreens(src, keyPrefix = name, sourcePath = file.absolutePath)
            screens.addAll(parsed)
            // style 块：标准模板的定位属性多在 style 里（如 style say_window: xalign 0.5）
            val styleScreens = parseStyleBlocks(src, keyPrefix = name, sourcePath = file.absolutePath)
            screens.addAll(styleScreens)
        }
        // 保证主文件一定在 sources 里（即使没有 screen）
        if (primaryPath.isNotBlank() && !sources.containsKey(primaryPath)) {
            sources[primaryPath] = try { File(primaryPath).readText() } catch (e: Exception) { "" }
        }

        val dynamicCount = screens.sumOf { s -> s.elements.count { e -> e.properties.values.any { !it.isEditable } } }
        if (dynamicCount > 0) {
            warnings.add("有 $dynamicCount 处动态表达式（变量/绝对定位），画布按默认态显示，不参与拖动")
        }

        return LayoutDoc(
            fileName = primaryName,
            primaryPath = primaryPath,
            sources = sources,
            screens = screens.distinctBy { it.name + it.startLine + it.elements.firstOrNull()?.sourcePath },
            theme = theme,
            warnings = warnings,
        )
    }

    // ───────────────────────── 主题解析 ─────────────────────────

    private fun parseTheme(files: List<File>): GuiTheme {
        var w: Int? = null
        var h: Int? = null
        var textFont: String? = null
        var textSize: Int? = null
        var textColor: String? = null
        var nameFont: String? = null
        var nameSize: Int? = null
        var nameColor: String? = null

        val intPat = Regex("""(?://[^\n]*|#[^\n]*)""") // 占位，下面用专门正则
        for (f in files) {
            val s = try { f.readText() } catch (e: Exception) { continue }
            // gui.init(1280, 720)
            Regex("""\bgui\.init\s*\(\s*(\d+)\s*,\s*(\d+)""").find(s)?.let {
                w = it.groupValues[1].toIntOrNull() ?: w
                h = it.groupValues[2].toIntOrNull() ?: h
            }
            fun findInt(name: String): Int? =
                Regex("""\b(?:define\s+)?$name\s*=\s*(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull()
            fun findStr(name: String): String? =
                Regex("""\b(?:define\s+)?$name\s*=\s*["']([^"']+)["']""").find(s)?.groupValues?.get(1)

            w = findInt("config.screen_width") ?: w
            h = findInt("config.screen_height") ?: h
            textFont = findStr("gui.text_font") ?: textFont
            textSize = findInt("gui.text_size") ?: textSize
            textColor = findStr("gui.text_color") ?: textColor
            nameFont = findStr("gui.name_text_font") ?: nameFont
            nameSize = findInt("gui.name_text_size") ?: nameSize
            nameColor = findStr("gui.name_text_color") ?: nameColor
        }

        val d = GuiTheme.DEFAULT
        return GuiTheme(
            width = w ?: d.width,
            height = h ?: d.height,
            textFont = textFont ?: d.textFont,
            textSize = textSize ?: d.textSize,
            textColor = textColor ?: d.textColor,
            nameFont = nameFont ?: nameFont ?: d.nameFont,
            nameSize = nameSize ?: d.nameSize,
            nameColor = nameColor ?: d.nameColor,
        )
    }

    // ───────────────────────── screen / 元素解析 ─────────────────────────

    /** 已知的定位属性名集合。 */
    private val POS_PROPS = setOf(
        "xpos", "ypos", "pos", "xanchor", "yanchor", "anchor",
        "xalign", "yalign", "align", "xcenter", "ycenter", "xycenter",
        "xoffset", "yoffset", "offset", "xsize", "ysize", "xysize",
        "xmaximum", "ymaximum", "xminimum", "yminimum", "xfill", "yfill",
    )

    /** 元素声明行的正则：捕获关键字 + 该行剩余参数。 */
    private val ELEMENT_LINE = Regex(
        """^\s*(window|frame|textbutton|imagebutton|button|text|add|image|vbox|hbox|grid|fixed|viewport|side|bar|label)\b(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseScreens(source: String, keyPrefix: String, sourcePath: String): List<LayoutScreen> {
        val lines = source.lines()
        // 计算每行起始字符偏移，便于把行内位置换算成全局 offset
        val lineOffsets = IntArray(lines.size + 1)
        var acc = 0
        for (i in lines.indices) {
            lineOffsets[i] = acc
            acc += lines[i].length + 1 // +1 for '\n'
        }
        lineOffsets[lines.size] = acc

        // 先找到所有 screen 块的 [start, end) 行区间
        val screenHeader = Regex("""^\s*screen\s+([A-Za-z_]\w*)\s*(\([^)]*\))?\s*:""")
        val blocks = mutableListOf<Triple<String, Int, Int>>() // name, startLine, endLine
        for (i in lines.indices) {
            val m = screenHeader.find(lines[i]) ?: continue
            val baseIndent = indentOf(lines[i])
            var end = i + 1
            while (end < lines.size) {
                val l = lines[end]
                if (l.isBlank()) { end++; continue }
                if (indentOf(l) <= baseIndent) break
                end++
            }
            blocks.add(Triple(m.groupValues[1], i, end))
        }

        return blocks.map { (name, start, end) ->
            val elements = parseElementsInBlock(
                lines = lines,
                lineOffsets = lineOffsets,
                start = start + 1,
                end = end,
                screenName = name,
                keyPrefix = keyPrefix,
                sourcePath = sourcePath,
            )
            LayoutScreen(name, start, elements)
        }
    }

    private fun indentOf(line: String): Int {
        var n = 0
        for (c in line) {
            when (c) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    /**
     * 解析 style 块。标准 Ren'Py 模板中，对话框/菜单的定位属性常写在
     * `style say_window:` 或 `style window:` 里，而非 screen 元素行内。
     * 每个含定位属性的 style 块生成一个虚拟元素，可直接编辑其数值属性。
     */
    private fun parseStyleBlocks(source: String, keyPrefix: String, sourcePath: String): List<LayoutScreen> {
        val lines = source.lines()
        val lineOffsets = IntArray(lines.size + 1)
        var acc = 0
        for (i in lines.indices) { lineOffsets[i] = acc; acc += lines[i].length + 1 }
        lineOffsets[lines.size] = acc

        // 只关心这些 style 名（界面容器类），避免把按钮/文本样式也列出来刷屏
        val interesting = Regex(
            """.*(window|frame|dialogue|namebox|menu|choice|label|box).*""",
            RegexOption.IGNORE_CASE,
        )
        val header = Regex("""^\s*style\s+([A-Za-z_][\w]*)\s*(?::|\([^)]*\)\s*:)""")
        val result = mutableListOf<LayoutScreen>()

        for (i in lines.indices) {
            val hm = header.find(lines[i]) ?: continue
            val styleName = hm.groupValues[1]
            if (!interesting.containsMatchIn(styleName)) continue
            val baseIndent = indentOf(lines[i])
            val props = linkedMapOf<String, LayoutProperty>()
            var end = i + 1
            while (end < lines.size) {
                val l = lines[end]
                if (l.isBlank()) { end++; continue }
                val ind = indentOf(l)
                if (ind <= baseIndent) break
                val pm = Regex("""^\s*([a-z][a-z0-9_]*)\s+(\S+)""").find(l) ?: run { end++; continue }
                val pname = pm.groupValues[1]
                if (pname in POS_PROPS) {
                    val vraw = pm.groupValues[2]
                    val vs = lineOffsets[end] + pm.groupValues[0].length - vraw.length
                    makeProperty(pname, vraw, vs, vs + vraw.length)?.let { props[pname] = it }
                }
                end++
            }
            if (props.isNotEmpty()) {
                val el = LayoutElement(
                    key = "$keyPrefix/style/$styleName",
                    kind = ElementKind.FRAME,
                    label = styleName,
                    properties = props,
                    imageRef = null,
                    textPreview = null,
                    startLine = i,
                    endLine = end - 1,
                    nested = false,
                    sourcePath = sourcePath,
                )
                result.add(LayoutScreen("style:$styleName", i, listOf(el)))
            }
        }
        return result
    }

    private fun parseElementsInBlock(
        lines: List<String>,
        lineOffsets: IntArray,
        start: Int,
        end: Int,
        screenName: String,
        keyPrefix: String,
        sourcePath: String,
    ): List<LayoutElement> {
        val elements = mutableListOf<LayoutElement>()
        // 每个元素记录其声明行缩进，用于判断属性行归属
        val elemIndent = mutableListOf<Int>()
        var index = 0

        for (lineNo in start until end) {
            val raw = lines[lineNo]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = indentOf(raw)

            val em = ELEMENT_LINE.find(raw)
            if (em != null) {
                val keyword = em.groupValues[1].lowercase()
                val rest = em.groupValues[2]
                val kind = kindOf(keyword)
                // 图片引用：add "bg room" / image "x" / scene 不在 screen 内
                val imageRef = Regex("""["']([^"']+\.(?:png|jpg|jpeg|webp))["']""", RegexOption.IGNORE_CASE)
                    .find(rest)?.groupValues?.get(1)
                    ?: Regex("""["']([a-zA-Z0-9_ ]+)["']""").find(rest)?.groupValues?.get(1)
                        ?.takeIf { k -> keyword == "add" || keyword == "image" }
                // 文本预览：text "..." / textbutton "..."
                val textPreview = if (kind == ElementKind.TEXT || kind == ElementKind.TEXTBUTTON ||
                    kind == ElementKind.BUTTON || kind == ElementKind.LABEL
                ) {
                    Regex("""["']([^"']*)["']""").find(rest)?.groupValues?.get(1)?.take(40)
                } else null
                // id "xxx"
                val idRef = Regex("""\bid\s+["']([^"']+)["']""").find(rest)?.groupValues?.get(1)

                val props = linkedMapOf<String, LayoutProperty>()
                // rest 在本行中的起始位置 = 行首偏移 + rest 在原行的下标（含缩进），
                // 不能简单用 keyword.length，否则漏掉行首缩进导致内联属性 offset 错位。
                val restBase = lineOffsets[lineNo] + raw.indexOf(rest)
                parseInlineProperties(rest, restBase, props)

                val label = idRef ?: textPreview ?: imageRef ?: keyword.replaceFirstChar { it.uppercase() }
                val key = "$keyPrefix/$screenName/${index++}"
                elements.add(
                    LayoutElement(
                        key = key,
                        kind = kind,
                        label = label,
                        properties = props,
                        imageRef = imageRef,
                        textPreview = textPreview,
                        startLine = lineNo,
                        endLine = lineNo,
                        nested = false,
                        sourcePath = sourcePath,
                    )
                )
                elemIndent.add(indent)
                continue
            }

            // 属性行：属于最近一个缩进小于它的元素
            if (elements.isNotEmpty()) {
                val propMatch = Regex("""^\s*([a-z][a-z0-9_]*)\s+(\S+)""").find(raw) ?: continue
                val pname = propMatch.groupValues[1]
                if (pname !in POS_PROPS) continue
                var owner = -1
                for (k in elemIndent.indices.reversed()) {
                    if (elemIndent[k] < indent) { owner = k; break }
                }
                if (owner < 0) continue
                val valueStart = lineOffsets[lineNo] + propMatch.groupValues[1].length +
                    (propMatch.groupValues[0].length - propMatch.groupValues[1].length - propMatch.groupValues[2].length)
                val valueRaw = propMatch.groupValues[2]
                val prop = makeProperty(pname, valueRaw, valueStart, valueStart + valueRaw.length)
                val e = elements[owner]
                if (prop != null) {
                    elements[owner] = e.copy(
                        properties = e.properties + (pname to prop),
                        endLine = lineNo,
                    )
                }
                // 标记嵌套：属性行缩进更深说明还有父容器，简化处理不细分
            }
        }
        return elements
    }

    /** 解析元素声明行行尾的内联属性，如 `add "x.png" xalign 0.5 yalign 0.0`。 */
    private fun parseInlineProperties(rest: String, baseOffset: Int, out: MutableMap<String, LayoutProperty>) {
        // 屏蔽引号内的字符串（图片名/文本），避免 "bg room.png" 里的单词被误判为属性。
        // 用等长空格替换，保持所有字符偏移不变。
        val mask = CharArray(rest.length) { rest[it] }
        var quote: Char? = null
        for (i in rest.indices) {
            val c = rest[i]
            if (c == '\\' && quote != null && i + 1 < rest.length) {
                mask[i] = ' '; mask[i + 1] = ' '  // 转义字符一起屏蔽
                continue
            }
            if (quote != null) {
                mask[i] = ' '
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'' || c == '`') {
                mask[i] = ' '
                quote = c
            }
        }
        val masked = String(mask)
        val pat = Regex("""\b([a-z][a-z0-9_]*)\s+(-?\d+(?:\.\d+)?|-?\.\d+|absolute\s*\([^)]*\)|[A-Za-z_]\w*)""")
        var m = pat.find(masked)
        while (m != null) {
            val name = m.groupValues[1]
            if (name in POS_PROPS) {
                // 属性名/数值都在引号外，原文与掩码一致，直接按位置取原文
                val rawInMask = m.groupValues[2]
                // val 相对匹配起点的偏移 = group0 长度 - val 长度（group0 不含前导空白）
                val s = baseOffset + m.range.first + (m.groupValues[0].length - rawInMask.length)
                val raw = rest.substring(m.range.first, m.range.last + 1)
                    .let { seg -> seg.substring(seg.indexOf(rawInMask)) }
                makeProperty(name, raw, s, s + raw.length)?.let { out[name] = it }
            }
            m = m.next()
        }
    }

    private fun makeProperty(name: String, raw: String, start: Int, end: Int): LayoutProperty? {
        val clean = raw.trim().trimEnd(',', ')', '(')
        // 布尔型 xfill/yfill 不参与数值拖动
        if (clean.equals("True", true) || clean.equals("False", true)) {
            return LayoutProperty(name, raw, start, end, null, ValueUnit.DYNAMIC)
        }
        // 绝对定位 absolute(...) / 变量 / 元组 -> 动态
        if (clean.contains('(') || clean.contains(',')) {
            return LayoutProperty(name, raw, start, end, null, ValueUnit.DYNAMIC)
        }
        val num = clean.toFloatOrNull()
        return if (num != null) {
            val unit = if (clean.contains('.')) ValueUnit.FRACTION else ValueUnit.PIXEL
            LayoutProperty(name, raw, start, end, num, unit)
        } else {
            LayoutProperty(name, raw, start, end, null, ValueUnit.DYNAMIC)
        }
    }

    private fun kindOf(keyword: String): ElementKind = when (keyword) {
        "window" -> ElementKind.WINDOW
        "frame" -> ElementKind.FRAME
        "text" -> ElementKind.TEXT
        "textbutton" -> ElementKind.TEXTBUTTON
        "imagebutton" -> ElementKind.IMAGEBUTTON
        "button" -> ElementKind.BUTTON
        "add", "image" -> ElementKind.ADD
        "vbox" -> ElementKind.VBOX
        "hbox" -> ElementKind.HBOX
        "grid" -> ElementKind.GRID
        "fixed" -> ElementKind.FIXED
        "viewport" -> ElementKind.VIEWPORT
        "label" -> ElementKind.LABEL
        else -> ElementKind.OTHER
    }

    // ───────────────────────── 布局计算（官方公式） ─────────────────────────

    /**
     * 计算元素在画布中的矩形。容器取整个画布（screen 铺满）。
     * size 缺省时：图片用其实际像素尺寸/画布尺寸；其它用经验默认值。
     */
    fun computeRect(
        element: LayoutElement,
        theme: GuiTheme,
        canvasW: Float,
        canvasH: Float,
        imageSize: (String) -> Pair<Int, Int>? = { null },
    ): ElementRect {
        val p = element.properties
        // align 展开为 pos+anchor
        fun prop(name: String) = p[name]?.number
        fun fraction(name: String): Float? {
            val v = p[name] ?: return null
            return if (v.unit == ValueUnit.FRACTION) v.number else null
        }

        val xAlign = fraction("xalign")
        val yAlign = fraction("yalign")
        var xPos = prop("xpos")
        var yPos = prop("ypos")
        var xAnchor = prop("xanchor")
        var yAnchor = prop("yanchor")
        if (xAlign != null) { xPos = xAlign; xAnchor = xAlign }
        if (yAlign != null) { yPos = yAlign; yAnchor = yAlign }
        p["xcenter"]?.number?.let { xPos = it; xAnchor = 0.5f }
        p["ycenter"]?.number?.let { yPos = it; yAnchor = 0.5f }

        // 宽高比例
        fun sizeFraction(sizeName: String, maxName: String, fallbackPx: Float): Float {
            val sp = p[sizeName]
            if (sp?.number != null) {
                return if (sp.unit == ValueUnit.FRACTION) sp.number!! else sp.number!! / fallbackPx
            }
            p[maxName]?.number?.let {
                return if (p[maxName]!!.unit == ValueUnit.FRACTION) it else it / fallbackPx
            }
            if (p["${sizeName[0]}fill"]?.rawValue?.contains("True") == true) return 1f
            return 0f // 由调用方用默认尺寸兜底
        }

        var wFrac = sizeFraction("xsize", "xmaximum", canvasW)
        var hFrac = sizeFraction("ysize", "ymaximum", canvasH)

        // 默认尺寸兜底：图片用真实尺寸；文本/窗口用经验值
        if (wFrac <= 0f || hFrac <= 0f) {
            val img = element.imageRef?.let { imageSize(it) }
            val defW = img?.first?.toFloat() ?: when (element.kind) {
                ElementKind.WINDOW, ElementKind.FRAME, ElementKind.FIXED, ElementKind.VIEWPORT -> canvasW * 0.9f
                ElementKind.TEXT, ElementKind.LABEL -> canvasW * 0.5f
                else -> canvasW * 0.25f
            }
            val defH = img?.second?.toFloat() ?: when (element.kind) {
                ElementKind.WINDOW, ElementKind.FRAME, ElementKind.FIXED, ElementKind.VIEWPORT -> canvasH * 0.25f
                ElementKind.TEXT, ElementKind.LABEL -> canvasH * 0.06f
                else -> canvasH * 0.3f
            }
            if (wFrac <= 0f) wFrac = defW / canvasW
            if (hFrac <= 0f) hFrac = defH / canvasH
        }

        val w = (wFrac * canvasW).coerceAtLeast(1f)
        val h = (hFrac * canvasH).coerceAtLeast(1f)

        // pos 转像素：fraction 乘画布，pixel 直接用
        fun posPx(v: Float?, unit: ValueUnit?, parent: Float): Float {
            if (v == null) return 0f
            return if (unit == ValueUnit.FRACTION) v * parent else v
        }
        // anchor 相对自身：fraction 乘自身，pixel 直接用
        fun anchorPx(v: Float?, unit: ValueUnit?, self: Float): Float {
            if (v == null) return 0f
            return if (unit == ValueUnit.FRACTION) v * self else v
        }

        fun unitOf(name: String) = p[name]?.unit
        val xp = posPx(xPos, unitOf("xpos").let { if (xAlign != null) ValueUnit.FRACTION else it }, canvasW)
        val yp = posPx(yPos, unitOf("ypos").let { if (yAlign != null) ValueUnit.FRACTION else it }, canvasH)
        val xa = anchorPx(xAnchor, unitOf("xanchor").let { if (xAlign != null) ValueUnit.FRACTION else it }, w)
        val ya = anchorPx(yAnchor, unitOf("yanchor").let { if (yAlign != null) ValueUnit.FRACTION else it }, h)
        val xoff = p["xoffset"]?.number ?: 0f
        val yoff = p["yoffset"]?.number ?: 0f

        return ElementRect(xp - xa + xoff, yp - ya + yoff, w, h)
    }

    // ───────────────────────── 精确回写 ─────────────────────────

    /** 把一个属性的数值改成 newValue（已格式化好的字符串），生成编辑记录。 */
    fun editFor(element: LayoutElement, propertyName: String, newValue: String): ValueEdit? {
        val prop = element.properties[propertyName] ?: return null
        if (!prop.isEditable) return null
        return ValueEdit(element.sourcePath, prop.startOffset, prop.endOffset, newValue)
    }

    /** 数值格式化：比例保留 2 位小数，像素取整。 */
    fun formatNumber(value: Float, unit: ValueUnit): String = when (unit) {
        ValueUnit.FRACTION -> {
            val v = (value * 100).roundToInt() / 100f
            if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.2f", v)
        }
        else -> value.roundToInt().toString()
    }

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()

    /**
     * 应用一批编辑，返回更新后的 sources（按文件分组，每个文件内从后往前替换）。
     * 同时返回受影响的文件路径集合，供调用方写磁盘。
     */
    data class ApplyResult(val sources: Map<String, String>, val changedPaths: List<String>)

    fun applyEdits(sources: Map<String, String>, edits: List<ValueEdit>): ApplyResult {
        val result = sources.toMutableMap()
        val changed = linkedSetOf<String>()
        edits.groupBy { it.sourcePath }.forEach { (path, fileEdits) ->
            var text = result[path] ?: return@forEach
            fileEdits.sortedByDescending { it.startOffset }.forEach { e ->
                val s = e.startOffset.coerceIn(0, text.length)
                val en = e.endOffset.coerceIn(s, text.length)
                text = text.substring(0, s) + e.newValue + text.substring(en)
            }
            result[path] = text
            changed.add(path)
        }
        return ApplyResult(result, changed.toList())
    }

    // ───────────────────────── 图片资源定位 ─────────────────────────

    /** 把 Ren'Py 图片引用（如 "bg room" 或 "gui/textbox.png"）解析为磁盘绝对路径。 */
    fun resolveImage(projectPath: String, ref: String): String? {
        val gameDir = File(projectPath, "game")
        val name = ref.trim().trim('"', '\'')
        val exts = listOf("", ".png", ".jpg", ".jpeg", ".webp")
        val variants = linkedSetOf(name, name.replace(' ', '_'))
        for (v in variants) {
            for (ext in exts) {
                val candidate = if (v.contains('.')) File(gameDir, v) else File(gameDir, v + ext)
                if (candidate.isFile) return candidate.absolutePath
            }
        }
        return null
    }
}
