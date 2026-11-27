package com.renpy.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.renpy.mobile.ui.theme.SyntaxDark
import com.renpy.mobile.ui.theme.SyntaxLight

/**
 * Ren'Py 语法着色器（依据官方文档 Ren'Py 8：language_basics / text / statements）。
 *
 * - 语句关键字、属性关键字、Python 字面量分类着色；
 * - 注释 `#`、数字、三种引号字符串（可跨行）、`$` 单行 Python；
 * - 字符串内的 `{文本标签}` 与 `[变量插值]` 单独着色；
 * - `label` / `define` 等后紧跟的名称、函数调用/类名着色；
 * - 用逐字符状态机输出与原文等长的 AnnotatedString（OffsetMapping.Identity），光标不错位。
 */
object SyntaxHighlight {

    /** 语句关键字（引入语句或块）。来源：官方 reference manual keywords + 常用语句。 */
    private val STATEMENT_KEYWORDS = setOf(
        "label", "scene", "show", "hide", "with", "menu", "if", "elif", "else",
        "while", "for", "jump", "call", "return", "define", "default", "init",
        "python", "screen", "transform", "pause", "play", "stop", "queue", "voice",
        "window", "pass", "image", "style", "camera", "layer", "use", "extend",
        "nvl", "modal", "nointeract", "expression",
    )

    /** 属性 / 修饰关键字（语句参数）。 */
    private val PROPERTY_KEYWORDS = setOf(
        "at", "as", "behind", "onlayer", "zorder", "in", "from", "set",
        "if_changed", "loop", "noloop", "fadein", "fadeout",
    )

    /** Python 字面量与逻辑运算符。 */
    private val PYTHON_WORDS = setOf(
        "True", "False", "None", "and", "or", "not", "is",
    )

    /** 其后紧跟的标识符按“名称”着色（标签名 / 变量名 / 镜像名 / 屏幕名）。 */
    private val NAME_INTRODUCERS = setOf(
        "label", "define", "default", "image", "screen", "transform", "style",
    )

    /** 字符串引号：Ren'Py 支持 " ' ` 三种。 */
    private const val QUOTES = "\"'`"

    private data class Palette(
        val keyword: Color,
        val property: Color,
        val literal: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val tag: Color,
        val interpolation: Color,
        val name: Color,
        val builtin: Color,
        val plain: Color,
    ) {
        val keywordStyle = SpanStyle(color = keyword, fontWeight = FontWeight.Bold)
        val propertyStyle = SpanStyle(color = property, fontWeight = FontWeight.SemiBold)
        val literalStyle = SpanStyle(color = literal, fontWeight = FontWeight.Bold)
        val stringStyle = SpanStyle(color = string)
        val commentStyle = SpanStyle(color = comment)
        val numberStyle = SpanStyle(color = number)
        val tagStyle = SpanStyle(color = tag, fontWeight = FontWeight.SemiBold)
        val interpolationStyle = SpanStyle(color = interpolation, fontWeight = FontWeight.SemiBold)
        val nameStyle = SpanStyle(color = name)
        val builtinStyle = SpanStyle(color = builtin)
        val plainStyle = SpanStyle(color = plain)
        val pythonStyle = SpanStyle(color = interpolation)
    }

    private fun palette(dark: Boolean): Palette = if (dark) Palette(
        keyword = SyntaxDark.keyword,
        property = SyntaxDark.function,
        literal = SyntaxDark.number,
        string = SyntaxDark.string,
        comment = SyntaxDark.comment,
        number = SyntaxDark.number,
        tag = SyntaxDark.function,
        interpolation = SyntaxDark.variable,
        name = SyntaxDark.variable,
        builtin = SyntaxDark.function,
        plain = SyntaxDark.plain,
    ) else Palette(
        keyword = SyntaxLight.keyword,
        property = SyntaxLight.function,
        literal = SyntaxLight.number,
        string = SyntaxLight.string,
        comment = SyntaxLight.comment,
        number = SyntaxLight.number,
        tag = SyntaxLight.function,
        interpolation = SyntaxLight.variable,
        name = SyntaxLight.variable,
        builtin = SyntaxLight.function,
        plain = SyntaxLight.plain,
    )

    /** currentLine 保留参数以兼容调用方；当前不做整行底色。 */
    fun highlight(code: String, dark: Boolean, currentLine: Int = 0): AnnotatedString {
        val p = palette(dark)
        return buildAnnotatedString {
            var i = 0
            val len = code.length
            var atLineStart = true
            var lineHasContent = false
            var prevWord = ""

            while (i < len) {
                val c = code[i]

                // 换行：重置行状态
                if (c == '\n') {
                    append('\n')
                    i++
                    atLineStart = true
                    lineHasContent = false
                    continue
                }

                // 空白：原样输出，不改变行首标记
                if (c == ' ' || c == '\t' || c == '\r') {
                    append(c)
                    i++
                    continue
                }

                // 注释：字符串外的 # 到行尾
                if (c == '#') {
                    val end = indexOfLineEnd(code, i)
                    withStyle(p.commentStyle) { append(code, i, end) }
                    i = end
                    continue
                }

                // $ 单行 Python：行首首个非空字符为 $
                if (c == '$' && atLineStart && !lineHasContent) {
                    val end = indexOfLineEnd(code, i)
                    withStyle(p.pythonStyle) { append(code, i, end) }
                    i = end
                    lineHasContent = true
                    atLineStart = false
                    continue
                }

                // 字符串：三种引号，支持跨行；内部对 {tag} 与 [var] 着色
                if (c in QUOTES) {
                    i = appendString(code, i, c, p)
                    lineHasContent = true
                    atLineStart = false
                    continue
                }

                // 数字：数字或 .数字 开头
                if (c.isDigit() || (c == '.' && i + 1 < len && code[i + 1].isDigit())) {
                    val end = scanNumber(code, i)
                    withStyle(p.numberStyle) { append(code, i, end) }
                    i = end
                    lineHasContent = true
                    atLineStart = false
                    continue
                }

                // 标识符 / 关键字
                if (isNameStart(c)) {
                    val end = scanName(code, i)
                    val word = code.substring(i, end)
                    val lower = word.lowercase()
                    val style = when {
                        lower in STATEMENT_KEYWORDS -> p.keywordStyle
                        lower in PROPERTY_KEYWORDS -> p.propertyStyle
                        word in PYTHON_WORDS -> p.literalStyle
                        prevWord.lowercase() in NAME_INTRODUCERS -> p.nameStyle
                        isCallOrClass(code, end) -> p.builtinStyle
                        word[0].isUpperCase() -> p.builtinStyle
                        else -> p.plainStyle
                    }
                    withStyle(style) { append(word) }
                    prevWord = word
                    i = end
                    lineHasContent = true
                    atLineStart = false
                    continue
                }

                // 其它符号：原样输出
                append(c)
                i++
                lineHasContent = true
                atLineStart = false
            }
        }
    }

    /** 输出一个字符串（含引号），返回扫描结束位置。内部标签/插值为等长着色。 */
    private fun AnnotatedString.Builder.appendString(
        code: String,
        start: Int,
        quote: Char,
        p: Palette,
    ): Int {
        var i = start
        withStyle(p.stringStyle) { append(quote) }
        i++
        val len = code.length
        while (i < len) {
            val c = code[i]
            // 转义序列：原样按字符串色输出两个字符（保证 \{ \[ 不被误判为标签/插值）
            if (c == '\\' && i + 1 < len) {
                withStyle(p.stringStyle) { append(code, i, i + 2) }
                i += 2
                continue
            }
            // 闭合引号
            if (c == quote) {
                withStyle(p.stringStyle) { append(c) }
                return i + 1
            }
            // 文本标签 {b} {/b} {color=#f00} …
            if (c == '{') {
                val end = code.indexOf('}', i + 1)
                if (end != -1) {
                    withStyle(p.tagStyle) { append(code, i, end + 1) }
                    i = end + 1
                    continue
                }
            }
            // 变量插值 [name] [a.b] [[ 为字面量转义
            if (c == '[') {
                if (i + 1 < len && code[i + 1] == '[') {
                    withStyle(p.stringStyle) { append("[[") }
                    i += 2
                    continue
                }
                val end = code.indexOf(']', i + 1)
                if (end != -1) {
                    withStyle(p.interpolationStyle) { append(code, i, end + 1) }
                    i = end + 1
                    continue
                }
            }
            // 字符串其余字符（含跨行换行）
            withStyle(p.stringStyle) { append(c) }
            i++
        }
        return i
    }

    private fun indexOfLineEnd(code: String, from: Int): Int {
        val idx = code.indexOf('\n', from)
        return if (idx == -1) code.length else idx
    }

    private fun scanNumber(code: String, from: Int): Int {
        var i = from
        while (i < code.length && (code[i].isDigit() || code[i] == '.')) i++
        return i
    }

    private fun isNameStart(c: Char): Boolean =
        c == '_' || c.isLetter() || c.code in 0x00A0..0xFFFD

    private fun scanName(code: String, from: Int): Int {
        var i = from
        while (i < code.length) {
            val ch = code[i]
            if (ch == '_' || ch.isLetterOrDigit() || ch.code in 0x00A0..0xFFFD) i++
            else break
        }
        return i
    }

    /** 标识符后（可含空格）紧跟 '(' 视为函数/类调用。 */
    private fun isCallOrClass(code: String, end: Int): Boolean {
        var i = end
        while (i < code.length && (code[i] == ' ' || code[i] == '\t')) i++
        return i < code.length && code[i] == '('
    }
}
