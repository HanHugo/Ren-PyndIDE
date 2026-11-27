package com.renpy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collect
import com.renpy.mobile.ui.theme.JetBrainsMono
import com.renpy.mobile.ui.theme.SyntaxDark
import com.renpy.mobile.ui.theme.SyntaxLight
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.renpy.mobile.i18n.t

const val EDITOR_MIN_FONT_SP = 6f
const val EDITOR_MAX_FONT_SP = 72f
const val EDITOR_DEFAULT_FONT_SP = 13f
/** 分块加载：每块行数。超过该文件行数的文件不会一次性读入内存。 */
const val EDITOR_CHUNK_LINES = 200

/**
 * 代码编辑区（真实可编辑 + 可缩放，性能版）：
 * - 行号合并为单个多行 Text，宽度按当前字号精确测量，永不换行 / 溢出；
 * - 语法高亮结果按 (code, darkTheme) 缓存，缩放不重新扫描全文；
 * - 单指拖动在 Initial 阶段接管，任意落点（含空白 / 行号区）都能滑动；
 * - 双指捏合缩放字号（6–72sp）。
 * - 文本选择菜单用 TextFieldValue + TextLayoutResult 自己算局部坐标，
 *   不依赖系统 TextToolbar 的全局 rect（新版 Compose 坐标系不一致会跑偏）。
 */
@Composable
fun CodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    darkTheme: Boolean,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** 文件总行数（分块模式）；<=0 表示全文已加载。 */
    totalLines: Int = -1,
    /** 当前已加载行数；<=0 表示全文已加载。 */
    loadedLines: Int = -1,
    /** 是否正在加载下一块（底部显示进度）。 */
    loadingMore: Boolean = false,
    /** 滚动接近底部时请求加载下一块。 */
    onLoadMore: () -> Unit = {},
) {
    val gutterColor = if (darkTheme) SyntaxDark.gutter else SyntaxLight.gutter
    val plainColor = if (darkTheme) SyntaxDark.plain else SyntaxLight.plain
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current

    // 用 TextFieldValue 掌握选区（String 重载拿不到 selection）
    var fieldValue by remember { mutableStateOf(TextFieldValue(code)) }
    // 外部 code 变化（切标签 / 保存回写 / 分块加载）时在「组合阶段」同步文本。
    // 关键一：判断用 fieldValue.text != code——打字时两者相等，不会重建 TextFieldValue，
    //         从而不打断中文输入法组合；只有外部文本真的不同（切文件等）才同步。
    // 关键二：必须在组合期间同步，不能再用 LaunchedEffect 异步同步。
    //         否则切换文件会出现一帧「code 已是新文件、fieldValue 还是旧文件」的中间态，
    //         高亮的 TransformedText 与 BasicTextField 的 value 长度不一致，
    //         Compose 用 OffsetMapping.Identity 定位光标时越界 → 打开其他文件直接闪退。
    if (fieldValue.text != code) {
        val safeStart = fieldValue.selection.start.coerceIn(0, code.length)
        val safeEnd = fieldValue.selection.end.coerceIn(0, code.length)
        fieldValue = TextFieldValue(code, TextRange(safeStart, safeEnd))
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 屏蔽系统原生选择菜单
    val emptyToolbar = remember { EmptyTextToolbar() }

    // 根 Box 与 BasicTextField 各自的窗口坐标，用于把选区局部矩形换算到根 Box
    var rootWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var fieldWindowOrigin by remember { mutableStateOf(Offset.Zero) }

    val lineHeightSp = fontSizeSp * 1.62f
    // 行号必须基于实际显示的文本，否则切文件中间态会因行数不一致而错位/越界
    val lineCount = remember(fieldValue.text) { fieldValue.text.count { it == '\n' } + 1 }
    val vertScroll = rememberScrollState()
    val horizScroll = rememberScrollState()

    // 分块加载：滚动接近底部时请求下一块。用 rememberUpdatedState 避免捕获旧回调。
    val liveHasMore by rememberUpdatedState(totalLines > loadedLines && loadedLines > 0)
    val liveLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(Unit) {
        snapshotFlow { vertScroll.value }.collect { pos ->
            val max = vertScroll.maxValue
            if (liveHasMore && max > 0 && pos >= max - 400f) liveLoadMore()
        }
    }

    // pointerInput 的 lambda 捕获实时值，避免"回弹/卡住"
    val liveFont by rememberUpdatedState(fontSizeSp)
    val liveOnChange by rememberUpdatedState(onFontSizeChange)
    val liveSelecting by rememberUpdatedState(
        fieldValue.selection.start != fieldValue.selection.end,
    )

    // 高亮结果缓存：必须基于 fieldValue.text（实际喂给 BasicTextField 的文本），
    // 保证 TransformedText 与原文本永远等长，避免 OffsetMapping.Identity 在切文件时越界崩溃。
    // 只在文本或主题变化时重新扫描，缩放不触发。
    val highlighted = remember(fieldValue.text, darkTheme) {
        SyntaxHighlight.highlight(fieldValue.text, darkTheme, currentLine = 0)
    }
    val syntaxTransformation = remember(highlighted) {
        VisualTransformation { TransformedText(highlighted, OffsetMapping.Identity) }
    }

    val textStyle = remember(fontSizeSp, lineHeightSp, plainColor) {
        TextStyle(
            fontFamily = JetBrainsMono,
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            color = plainColor,
        )
    }

    // 行号：单个多行 Text，右对齐，禁止换行；宽度按字号估算并留余量
    val lineNumbers = remember(lineCount) {
        (1..lineCount).joinToString("\n") { it.toString() }
    }
    val digits = lineCount.toString().length.coerceAtLeast(2)
    // 等宽字体单字符约 0.6em，乘 0.72 留余量；左右各 12dp padding
    val gutterWidth = (digits * 0.72f * fontSizeSp + 24f).dp

    // ── 选区矩形（相对根 Box 的局部坐标），用于定位自绘菜单 ──
    val selectionRect: Rect? = remember(
        fieldValue.selection, layoutResult, fieldWindowOrigin, rootWindowOrigin, fontSizeSp,
    ) {
        val sel = fieldValue.selection
        val lr = layoutResult
        if (sel.start == sel.end || lr == null) {
            null
        } else {
            runCatching {
                val s = min(sel.start, sel.end)
                val e = max(sel.start, sel.end) - 1
                val first = lr.getBoundingBox(s.coerceIn(0, lr.layoutInput.text.length - 1))
                val last = lr.getBoundingBox(e.coerceIn(0, lr.layoutInput.text.length - 1))
                val leftPx = min(first.left, last.left)
                val rightPx = max(first.right, last.right)
                val topPx = min(first.top, last.top)
                val bottomPx = max(first.bottom, last.bottom)
                // BasicTextField 相对根 Box 的偏移 + 其 12dp 内边距
                val offsetX = fieldWindowOrigin.x - rootWindowOrigin.x + with(density) { 12.dp.toPx() }
                val offsetY = fieldWindowOrigin.y - rootWindowOrigin.y + with(density) { 8.dp.toPx() }
                Rect(
                    left = leftPx + offsetX,
                    top = topPx + offsetY,
                    right = rightPx + offsetX,
                    bottom = bottomPx + offsetY,
                )
            }.getOrNull()
        }
    }

    fun updateText(newValue: TextFieldValue) {
        fieldValue = newValue
        if (newValue.text != code) onCodeChange(newValue.text)
    }

    fun copySelection() {
        val sel = fieldValue.selection
        if (sel.start != sel.end) {
            clipboard.setText(
                AnnotatedString(fieldValue.text.substring(min(sel.start, sel.end), max(sel.start, sel.end))),
            )
        }
    }

    fun cutSelection() {
        val sel = fieldValue.selection
        if (sel.start != sel.end) {
            val s = min(sel.start, sel.end)
            val e = max(sel.start, sel.end)
            clipboard.setText(AnnotatedString(fieldValue.text.substring(s, e)))
            updateText(TextFieldValue(fieldValue.text.removeRange(s, e), TextRange(s)))
        }
    }

    fun pasteClipboard() {
        val pasted = clipboard.getText()?.text ?: return
        val sel = fieldValue.selection
        val s = min(sel.start, sel.end)
        val e = max(sel.start, sel.end)
        val newText = fieldValue.text.substring(0, s) + pasted + fieldValue.text.substring(e)
        updateText(TextFieldValue(newText, TextRange(s + pasted.length)))
    }

    fun selectAll() {
        fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
    }

    CompositionLocalProvider(
        // 旧路径：TextFieldValue 重载的 BasicTextField 仍走 LocalTextToolbar
        LocalTextToolbar provides emptyToolbar,
        // 新路径：Compose 1.10+/BOM 2026 的系统文本菜单走这两个新 Local，
        // 只屏蔽旧的已不够——这正是一直弹出系统菜单的原因。
        LocalTextContextMenuToolbarProvider provides NoOpTextContextMenuProvider,
        LocalTextContextMenuDropdownProvider provides NoOpTextContextMenuProvider,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { rootWindowOrigin = it.positionInRoot() }
                // ── 统一手势：单指拖动 → 手动驱动滚动；双指 → 捏合缩放 ──
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var prevDist: Float? = null
                        var dragId: Long? = null
                        var downPos = Offset.Zero
                        var lastPos = Offset.Zero
                        var dragging = false
                        val touchSlop = 8f

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val active = event.changes.filter { it.pressed }

                            if (active.size >= 2) {
                                dragging = false
                                dragId = null
                                val a = active[0].position
                                val b = active[1].position
                                val dist = hypot(a.x - b.x, a.y - b.y)
                                prevDist?.let { prev ->
                                    if (prev > 0f) {
                                        val ratio = dist / prev
                                        val next = (liveFont * ratio).coerceIn(
                                            EDITOR_MIN_FONT_SP, EDITOR_MAX_FONT_SP,
                                        )
                                        if (abs(next - liveFont) > 0.01f) liveOnChange(next)
                                    }
                                }
                                prevDist = dist
                                active.forEach { it.consume() }
                            } else if (active.size == 1) {
                                prevDist = null
                                val p = active[0]
                                // 选区激活时放行给 BasicTextField（拖选区手柄 / 光标）
                                if (liveSelecting) {
                                    dragId = null
                                    dragging = false
                                } else if (dragId == null) {
                                    dragId = p.id.value
                                    downPos = p.position
                                    lastPos = p.position
                                    dragging = false
                                } else if (dragId == p.id.value) {
                                    val moved = hypot(
                                        p.position.x - downPos.x,
                                        p.position.y - downPos.y,
                                    )
                                    if (!dragging && moved > touchSlop) dragging = true
                                    if (dragging) {
                                        val dx = p.position.x - lastPos.x
                                        val dy = p.position.y - lastPos.y
                                        vertScroll.dispatchRawDelta(-dy)
                                        horizScroll.dispatchRawDelta(-dx)
                                        p.consume()
                                    }
                                    lastPos = p.position
                                }
                            } else {
                                prevDist = null
                                dragId = null
                                dragging = false
                            }
                        }
                    }
                },
        ) {
            // ── 底层：贯穿整个编辑区高度的行号槽背景 ──
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(gutterWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )

            // ── 滚动内容：行号文字与代码正文一起滚动，天然对齐 ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(vertScroll),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    // 行号文字：固定宽度、右对齐、禁止换行
                    Text(
                        text = lineNumbers,
                        fontFamily = JetBrainsMono,
                        fontSize = fontSizeSp.sp,
                        lineHeight = lineHeightSp.sp,
                        color = gutterColor,
                        textAlign = TextAlign.End,
                        maxLines = lineCount,
                        modifier = Modifier
                            .width(gutterWidth)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )

                    // ── 可编辑代码正文 ──
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { updateText(it) },
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizScroll)
                            .onGloballyPositioned { fieldWindowOrigin = it.positionInRoot() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        onTextLayout = { layoutResult = it },
                        textStyle = textStyle,
                        visualTransformation = syntaxTransformation,
                        maxLines = Int.MAX_VALUE,
                    )
                }

                // ── 分块加载状态：底部提示已加载进度 / 加载中 ──
                if (totalLines > 0 && (loadingMore || totalLines > loadedLines)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        if (loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (loadingMore) t("正在加载更多行…")
                            else "已加载 $loadedLines / $totalLines 行 · 继续下滑加载",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 自绘文本选择菜单：浮在选中文本附近，替换系统原生菜单 ──
            // BoxWithConstraints 拿到与根 Box 相同的尺寸，供菜单做边界夹取，
            // 保证菜单既不压住选中文本，也不溢出屏幕左右/底部。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                CustomSelectionMenu(
                    anchorRect = selectionRect,
                    containerSize = IntSize(constraints.maxWidth, constraints.maxHeight),
                    onCopy = { copySelection(); fieldValue = fieldValue.copy(selection = TextRange(fieldValue.selection.end)) },
                    onCut = { cutSelection() },
                    onPaste = { pasteClipboard() },
                    onSelectAll = { selectAll() },
                )
            }
        }
    }
}

/**
 * 快捷工具小栏：由顶栏扳手展开，悬浮在代码区上方。
 * 撤销 / 缩进 / 搜索 / 跳行 / Lint，纯展示。
 */
@Composable
fun QuickToolsBar(modifier: Modifier = Modifier) {
    val items = listOf(
        Icons.AutoMirrored.Filled.Undo to t("撤销"),
        Icons.AutoMirrored.Filled.FormatIndentIncrease to t("缩进"),
        Icons.Filled.FindInPage to t("搜索"),
        Icons.Filled.Numbers to t("跳行"),
        Icons.Filled.Checklist to "Lint",
    )
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, (icon, label) ->
                if (index > 0) Spacer(Modifier.width(4.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Clip)
                }
            }
        }
    }
}
