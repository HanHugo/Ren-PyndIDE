package com.renpy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 轻量但完整的 Markdown 渲染器（零第三方依赖，离线可用）。
 *
 * 支持：# 标题(可无空格) / 表格(GitHub 风格 | --- |) / 有序·无序·任务列表 /
 * 引用块 > / 代码块 ```(带横向滚动) / 粗体 / 斜体 / 删除线 / 行内代码 /
 * 链接 [text](url) / 裸 URL / 水平线 ---。
 *
 * 解析分两层：先按行切成块(标题/表格/列表/代码块/引用/分割线/段落)，
 * 再对每个块的行内内容做 AnnotatedString 样式。字号随 fontSizeScale 缩放。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fontSizeScale: Float = 1f,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onLinkClick: (String) -> Unit = {},
) {
    val base = MaterialTheme.typography.bodyMedium.fontSize * fontSizeScale
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> MdHeading(block, base, color, onLinkClick)
                is MdBlock.Paragraph -> MdParagraph(block, base, color, onLinkClick)
                is MdBlock.Code -> MdCode(block, base)
                is MdBlock.Table -> MdTable(block, base, color, onLinkClick)
                is MdBlock.Quote -> MdQuote(block, base, color, onLinkClick)
                is MdBlock.ListBlock -> MdList(block, base, color, onLinkClick)
                is MdBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = base.value.dp * 0.5f),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/* ──────────────────────────── 行内样式构造 ──────────────────────────── */

private data class InlineColors(val primary: Color, val tertiary: Color, val codeBg: Color)

@Composable
private fun inlineColors(): InlineColors {
    val cs = MaterialTheme.colorScheme
    return InlineColors(cs.primary, cs.tertiary, cs.surfaceContainerHigh)
}

@Composable
private fun buildInline(
    raw: String,
    base: TextUnit,
    color: Color,
    bold: Boolean = false,
    strike: Boolean = false,
): AnnotatedString {
    val colors = inlineColors()
    // 缓存：同一段文本在字号/主题/配色不变时直接复用已构建的 AnnotatedString，
    // 避免流式输出与滚动重组时反复重解析（曾导致切 pane 与滚动卡顿）。
    return remember(raw, base, color, colors, bold, strike) {
        buildAnnotatedString { appendInline(raw, base, color, colors, bold, strike) }
    }
}

private fun AnnotatedString.Builder.appendInline(
    line: String,
    base: TextUnit,
    color: Color,
    colors: InlineColors,
    bold: Boolean = false,
    strike: Boolean = false,
) {
    var i = 0
    // 关键：连续普通字符先攒进 buffer 再一次 append。
    // 逐字符 withStyle{append(c)} 会把 emoji 的 UTF-16 代理对拆成两个 span，
    // 导致显示为 ❖ 豆腐块；同时会产生上千个 span，切换时重排卡顿。
    val plain = StringBuilder()
    fun flushPlain() {
        if (plain.isEmpty()) return
        withStyle(SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
        )) { append(plain.toString()) }
        plain.setLength(0)
    }
    while (i < line.length) {
        val c = line[i]
        // 转义符：\* -> *
        if (c == '\\' && i + 1 < line.length && "\\`*_{}[]()#+-.!|>~".contains(line[i + 1])) {
            plain.append(line[i + 1]); i += 2; continue
        }
        // 行内标题标记（流式输出常把 ### 挤在行中）：转成加粗短标记
        if (c == '#' && i + 1 < line.length && line[i + 1] == '#') {
            var j = i
            while (j < line.length && line[j] == '#') j++
            if (j < line.length && line[j] == ' ') j++
            flushPlain()
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = colors.primary)) {
                append("▎")
            }
            i = j
            continue
        }
        // 链接 [text](url)
        if (c == '[') {
            val cb = line.indexOf(']', i + 1)
            if (cb > i && cb + 1 < line.length && line[cb + 1] == '(') {
                val cp = line.indexOf(')', cb + 2)
                if (cp > cb) {
                    flushPlain()
                    val label = line.substring(i + 1, cb)
                    val url = line.substring(cb + 2, cp)
                    val start = length
                    withStyle(SpanStyle(
                        color = colors.primary,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    )) { append(label) }
                    addStringAnnotation("URL", url, start, length)
                    i = cp + 1
                    continue
                }
            }
        }
        // 裸 URL
        if (c == 'h' && (line.startsWith("http://", i) || line.startsWith("https://", i))) {
            flushPlain()
            var end = i
            while (end < line.length && !line[end].isWhitespace() && line[end] != ')' && line[end] != ']') end++
            val url = line.substring(i, end)
            val start = length
            withStyle(SpanStyle(
                color = colors.primary,
                textDecoration = TextDecoration.Underline,
            )) { append(url) }
            addStringAnnotation("URL", url, start, length)
            i = end
            continue
        }
        // 行内代码 `code`
        if (c == '`') {
            val end = line.indexOf('`', i + 1)
            if (end > i) {
                flushPlain()
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = base * 0.9f,
                    color = colors.tertiary,
                    background = colors.codeBg,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                )) { append(line.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        // 粗体 **text** 或 __text__
        if ((c == '*' && i + 1 < line.length && line[i + 1] == '*') ||
            (c == '_' && i + 1 < line.length && line[i + 1] == '_')) {
            val end = line.indexOf(c.toString() + c, i + 2)
            if (end > i) {
                flushPlain()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(line.substring(i + 2, end), base, color, colors, bold = true, strike = strike)
                }
                i = end + 2
                continue
            }
        }
        // 删除线 ~~text~~
        if (c == '~' && i + 1 < line.length && line[i + 1] == '~') {
            val end = line.indexOf("~~", i + 2)
            if (end > i) {
                flushPlain()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInline(line.substring(i + 2, end), base, color, colors, bold, strike = true)
                }
                i = end + 2
                continue
            }
        }
        // 斜体 *text* 或 _text_
        if (c == '*' || (c == '_' && isItalicBoundary(line, i))) {
            val end = line.indexOf(c, i + 1)
            if (end > i) {
                flushPlain()
                withStyle(SpanStyle(
                    fontStyle = FontStyle.Italic,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (strike) TextDecoration.LineThrough else null,
                )) { append(line.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        // 上标 ^text^
        if (c == '^') {
            val end = line.indexOf('^', i + 1)
            if (end > i) {
                flushPlain()
                withStyle(SpanStyle(fontSize = base * 0.75f, baselineShift = BaselineShift.Superscript)) {
                    append(line.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // 普通字符攒入 buffer（emoji 代理对保持连续）
        plain.append(c)
        i++
    }
    flushPlain()
}

private fun isItalicBoundary(line: String, i: Int): Boolean {
    if (i > 0 && line[i - 1].isLetterOrDigit()) return false
    val end = line.indexOf('_', i + 1)
    return end > i && (end + 1 >= line.length || !line[end + 1].isLetterOrDigit())
}

/* ──────────────────────────── 块级渲染 ──────────────────────────── */

@Composable
private fun MdHeading(h: MdBlock.Heading, base: TextUnit, color: Color, onLinkClick: (String) -> Unit) {
    val (size, weight) = when (h.level) {
        1 -> base * 1.42f to FontWeight.ExtraBold
        2 -> base * 1.26f to FontWeight.Bold
        3 -> base * 1.13f to FontWeight.Bold
        else -> base * 1.04f to FontWeight.SemiBold
    }
    val padTop = if (h.level <= 2) base.value.dp * 0.7f else base.value.dp * 0.45f
    val annotated = buildInline(h.text, base, color, bold = true)
    ClickableText(
        text = annotated,
        modifier = Modifier.fillMaxWidth().padding(top = padTop, bottom = base.value.dp * 0.2f),
        style = TextStyle(color = color, fontSize = size, lineHeight = size * 1.25f, fontWeight = weight),
        onClick = { offset -> annotated.linkAt(offset, onLinkClick) },
    )
}

@Composable
private fun MdParagraph(p: MdBlock.Paragraph, base: TextUnit, color: Color, onLinkClick: (String) -> Unit) {
    val annotated = buildInline(p.text, base, color)
    ClickableText(
        text = annotated,
        modifier = Modifier.fillMaxWidth().padding(vertical = base.value.dp * 0.16f),
        style = TextStyle(color = color, fontSize = base, lineHeight = base * 1.5f),
        onClick = { offset -> annotated.linkAt(offset, onLinkClick) },
    )
}

@Composable
private fun MdCode(c: MdBlock.Code, base: TextUnit) {
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = base.value.dp * 0.3f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
    ) {
        if (c.lang.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                androidx.compose.material3.Text(
                    c.lang,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = base * 0.72f,
                    ),
                )
            }
        }
        androidx.compose.material3.Text(
            c.code,
            modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(10.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = base * 0.86f,
                lineHeight = base * 1.18f,
                color = codeColor,
            ),
        )
    }
}

@Composable
private fun MdTable(t: MdBlock.Table, base: TextUnit, color: Color, onLinkClick: (String) -> Unit) {
    if (t.headers.isEmpty()) return
    val cols = t.headers.size
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.secondaryContainer
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(vertical = base.value.dp * 0.3f)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp)),
    ) {
        Row(modifier = Modifier.background(headerBg)) {
            t.headers.forEach { cell ->
                TableCell(cell, cols, base, color, onLinkClick, bold = true)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = borderColor)
        t.rows.forEachIndexed { ri, row ->
            Row(modifier = Modifier.background(
                if (ri % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest
            )) {
                for (ci in 0 until cols) {
                    TableCell(row.getOrNull(ci) ?: "", cols, base, color, onLinkClick)
                }
            }
            if (ri != t.rows.lastIndex) {
                HorizontalDivider(thickness = 1.dp, color = borderColor.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun TableCell(
    cell: String,
    cols: Int,
    base: TextUnit,
    color: Color,
    onLinkClick: (String) -> Unit,
    bold: Boolean = false,
) {
    val pad = if (cols >= 4) 5.dp else 8.dp
    val cellBase = base * if (cols >= 5) 0.82f else 0.9f
    val annotated = buildInline(cell, cellBase, color, bold = bold)
    ClickableText(
        text = annotated,
        modifier = Modifier.padding(horizontal = pad, vertical = 6.dp),
        style = TextStyle(
            color = color,
            fontSize = cellBase,
            lineHeight = cellBase * 1.35f,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
        onClick = { offset -> annotated.linkAt(offset, onLinkClick) },
    )
}

@Composable
private fun MdQuote(q: MdBlock.Quote, base: TextUnit, color: Color, onLinkClick: (String) -> Unit) {
    val annotated = buildInline(q.text, base, color)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = base.value.dp * 0.25f)) {
        Box(
            modifier = Modifier.padding(end = 8.dp).width(3.dp)
                .height(base.value.dp * 1.6f).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        ClickableText(
            text = annotated,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = base,
                lineHeight = base * 1.45f,
                fontStyle = FontStyle.Italic,
            ),
            onClick = { offset -> annotated.linkAt(offset, onLinkClick) },
        )
    }
}

@Composable
private fun MdList(l: MdBlock.ListBlock, base: TextUnit, color: Color, onLinkClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = base.value.dp * 0.16f)) {
        l.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = (item.indent * 14).dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val marker = when {
                    item.checked != null -> if (item.checked) "☑ " else "☐ "
                    l.ordered -> "${item.index}. "
                    else -> "• "
                }
                androidx.compose.material3.Text(
                    marker,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = base,
                        lineHeight = base * 1.45f,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                val annotated = buildInline(item.text, base, color, strike = item.checked == true)
                ClickableText(
                    text = annotated,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = color, fontSize = base, lineHeight = base * 1.45f),
                    onClick = { offset -> annotated.linkAt(offset, onLinkClick) },
                )
            }
        }
    }
}

private fun AnnotatedString.linkAt(offset: Int, onLinkClick: (String) -> Unit) {
    getStringAnnotations("URL", offset, offset).firstOrNull()?.let { onLinkClick(it.item) }
}

/* ──────────────────────────── 块级解析 ──────────────────────────── */

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(
        val text: String,
        val indent: Int,
        val ordered: Boolean,
        val index: Int,
        val checked: Boolean? = null,
    )
    data class ListBlock(val items: List<ListItem>, val ordered: Boolean) : MdBlock
    data object Divider : MdBlock
}

private fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val out = mutableListOf<MdBlock>()
    var i = 0

    fun isDivider(s: String): Boolean {
        val t = s.trim().replace(" ", "")
        return t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' })
    }
    fun isTableSep(s: String): Boolean {
        val t = s.trim()
        if (!t.contains("|")) return false
        val cells = t.split('|').map { it.trim() }
        return cells.isNotEmpty() && cells.all { it.isEmpty() || it.matches(Regex(":?-{2,}:?")) }
    }
    fun splitRow(s: String): List<String> {
        val t = s.trim()
        val inner = if (t.startsWith("|")) t.drop(1) else t
        val end = if (inner.endsWith("|")) inner.dropLast(1) else inner
        return end.split('|').map { it.trim() }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 代码块 ```
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                buf.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++
            out += MdBlock.Code(lang, buf.toString().trimEnd())
            continue
        }

        if (trimmed.isEmpty()) { i++; continue }

        if (isDivider(line)) { out += MdBlock.Divider; i++; continue }

        // 标题（支持 #标题 无空格）
        val headingLevel = headingLevelOf(trimmed)
        if (headingLevel > 0) {
            val content = trimmed.substring(headingLevel).trim()
            if (content.isNotEmpty()) out += MdBlock.Heading(headingLevel, content)
            i++; continue
        }

        // 表格
        if (trimmed.contains("|") && i + 1 < lines.size && isTableSep(lines[i + 1])) {
            val headers = splitRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().isNotEmpty() && lines[i].contains("|")) {
                rows += splitRow(lines[i])
                i++
            }
            out += MdBlock.Table(headers, rows)
            continue
        }

        // 引用块
        if (trimmed.startsWith(">")) {
            val buf = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                val t = lines[i].trim().removePrefix(">").trimStart()
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(t)
                i++
            }
            out += MdBlock.Quote(buf.toString())
            continue
        }

        // 列表
        if (isListStart(line)) {
            val items = mutableListOf<MdBlock.ListItem>()
            var counter = 0
            while (i < lines.size && (isListStart(lines[i]) ||
                        (lines[i].trim().isEmpty() && i + 1 < lines.size && isListStart(lines[i + 1])))) {
                val l = lines[i]
                if (l.trim().isEmpty()) { i++; continue }
                val indent = l.takeWhile { it == ' ' || it == '\t' }.length
                val level = indent / 2
                val content = l.trim()
                val orderedMatch = Regex("^(\\d+)[.)]\\s+(.*)$").find(content)
                val bulletMatch = Regex("^[-*+]\\s+(.*)$").find(content)
                var text = ""
                var isOrdered = false
                var checked: Boolean? = null
                if (bulletMatch != null) {
                    text = bulletMatch.groupValues[1]
                    val task = Regex("^\\[([ xX])\\]\\s+(.*)$").find(text)
                    if (task != null) {
                        checked = task.groupValues[1].lowercase() == "x"
                        text = task.groupValues[2]
                    }
                } else if (orderedMatch != null) {
                    text = orderedMatch.groupValues[2]
                    isOrdered = true
                    counter = orderedMatch.groupValues[1].toIntOrNull() ?: (counter + 1)
                }
                items += MdBlock.ListItem(
                    text = text,
                    indent = level.coerceIn(0, 3),
                    ordered = isOrdered,
                    index = counter,
                    checked = checked,
                )
                i++
            }
            out += MdBlock.ListBlock(items, items.any { it.ordered })
            continue
        }

        // 普通段落：合并连续的非空、非特殊起始行
        val buf = StringBuilder()
        while (i < lines.size && lines[i].trim().isNotEmpty() &&
            !lines[i].trim().startsWith("```") && !isDivider(lines[i]) &&
            !isSpecialStart(lines[i]) &&
            !(lines[i].contains("|") && i + 1 < lines.size && isTableSep(lines[i + 1]))) {
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(lines[i])
            i++
        }
        if (buf.isNotEmpty()) out += MdBlock.Paragraph(buf.toString())
        else i++
    }
    return out
}

/** 返回 1..6 表示标题级别，0 表示不是标题。与解析使用同一套判断，避免死循环。 */
private fun headingLevelOf(trimmed: String): Int {
    val hashes = trimmed.takeWhile { it == '#' }
    if (hashes.isEmpty() || hashes.length > 6) return 0
    if (hashes.length == trimmed.length || trimmed[hashes.length] == ' ') {
        // 允许 #标题 无空格，但 #话题 这类紧连的普通文本不误判（除非是纯 # 或后跟空格）
        return hashes.length
    }
    return 0
}

private fun isSpecialStart(line: String): Boolean {
    val t = line.trim()
    return headingLevelOf(t) > 0 || t.startsWith(">") || isListStart(line) || t.startsWith("```")
}

private fun isListStart(line: String): Boolean {
    val t = line.trimStart()
    return t.matches(Regex("^[-*+]\\s+.*$")) || t.matches(Regex("^\\d+[.)]\\s+.*$"))
}
