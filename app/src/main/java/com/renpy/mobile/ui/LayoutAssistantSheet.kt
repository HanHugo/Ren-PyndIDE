package com.renpy.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.project.ProjectManager
import java.io.File
import kotlin.math.roundToInt
import com.renpy.mobile.i18n.t

/**
 * 布局助手面板：解析 rpy 布局 → 高保真示意画布 → 拖动/滑块编辑 → 精确回写。
 *
 * 保真：位置/大小/对齐/偏移按 Ren'Py 官方公式计算；图片用项目真实 PNG、
 * 字体用项目真实 TTF、颜色读 gui.* —— 接近真实游戏画面的布局预览。
 * 运行时动态逻辑（变量/条件/transform 动画）不还原，明确标注。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutAssistantSheet(
    project: ProjectInfo,
    currentFilePath: String?,
    onDismiss: () -> Unit,
    onApplyCode: (String) -> Unit,   // 回写主文件后的完整源码（供编辑器刷新）
    onToast: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var doc by remember {
        mutableStateOf(RpyLayoutParser.parseProject(project.path, currentFilePath))
    }
    // 撤销栈：保存每次编辑前的 sources 快照
    val undoStack = remember { mutableStateListOf<Map<String, String>>() }
    // 原始快照
    val originalSources by remember { mutableStateOf(doc.sources.toMap()) }

    var selectedKey by remember { mutableStateOf<String?>(null) }

    // 统一应用编辑：压栈 → 算新 sources → 写磁盘 → 更新 doc → 通知编辑器
    fun applyEdits(edits: List<RpyLayoutParser.ValueEdit>, toast: String? = null) {
        if (edits.isEmpty()) return
        val result = RpyLayoutParser.applyEdits(doc.sources, edits)
        if (result.changedPaths.isEmpty()) return
        undoStack.add(doc.sources.toMap())
        // 写磁盘
        result.changedPaths.forEach { path ->
            try { ProjectManager.writeFile(path, result.sources[path] ?: return@forEach) }
            catch (e: Exception) { onToast("写文件失败：${e.message}"); return }
        }
        doc = doc.copy(sources = result.sources)
        // 主文件变了就刷新编辑器
        if (doc.primaryPath in result.changedPaths) {
            onApplyCode(result.sources[doc.primaryPath] ?: "")
        }
        toast?.let { onToast(it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // ── 标题栏 ──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(t("布局助手"), style = MaterialTheme.typography.titleLargeEmphasized)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${doc.theme.width}×${doc.theme.height}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = t("关闭")) }
            }

            if (doc.warnings.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        doc.warnings.joinToString("；"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (doc.allElements.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("没有找到可编辑的布局块"), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "在 .rpy 里写 screen 块（如 screen say），\n并用 xpos / yalign / xsize 等属性定位后，即可可视化编辑。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                return@Column
            }

            // ── 画布 ──
            LayoutCanvas(
                doc = doc,
                projectPath = project.path,
                selectedKey = selectedKey,
                onSelect = { selectedKey = it },
                onCommitDrag = { key, nx, ny ->
                    // 拖动结束：把元素改成新的 xpos/ypos（按比例，转成 xalign/yalign 若原本是 align）
                    val el = doc.allElements.find { it.key == key } ?: return@LayoutCanvas
                    val edits = mutableListOf<RpyLayoutParser.ValueEdit>()
                    // 优先写 xalign/yalign（若存在），否则写 xpos/ypos
                    fun editFor(propName: String, frac: Float) {
                        val p = el.properties[propName]
                        if (p != null && p.isEditable) {
                            edits.add(RpyLayoutParser.ValueEdit(
                                el.sourcePath, p.startOffset, p.endOffset,
                                RpyLayoutParser.formatNumber(frac, RpyLayoutParser.ValueUnit.FRACTION),
                            ))
                        }
                    }
                    if (el.properties["xalign"]?.isEditable == true) editFor("xalign", nx)
                    else editFor("xpos", nx)
                    if (el.properties["yalign"]?.isEditable == true) editFor("yalign", ny)
                    else editFor("ypos", ny)
                    applyEdits(edits)
                    // 提交后清除临时拖动偏移，避免下一次拖动叠加
                    DragOffsets.map.remove(key)
                },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )

            Spacer(Modifier.height(12.dp))

            // ── 元素选择器 ──
            Text(t("编辑元素"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                doc.allElements.forEach { el ->
                    FilterChip(
                        selected = selectedKey == el.key,
                        onClick = { selectedKey = el.key },
                        label = { Text("${el.kind.name.lowercase()}·${el.label}") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 属性编辑区 ──
            val selected = doc.allElements.find { it.key == selectedKey }
            if (selected != null) {
                PropertyEditor(
                    element = selected,
                    onCommit = { propName, newNumber, unit ->
                        val edit = RpyLayoutParser.editFor(
                            selected, propName, RpyLayoutParser.formatNumber(newNumber, unit)
                        )
                        if (edit != null) applyEdits(listOf(edit))
                    },
                )
            } else {
                Text(
                    t("点上面的元素，或直接在画布上拖动方框来编辑"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 预设 ──
            Text(t("快捷预设"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                PRESETS.forEach { preset ->
                    Button(
                        onClick = {
                            val target = doc.allElements.find { it.key == selectedKey }
                                ?: doc.allElements.firstOrNull()
                            if (target == null) onToast(t("先选择一个元素"))
                            else applyEdits(buildPresetEdits(target, preset), "已应用：${preset.name}")
                        },
                        modifier = Modifier.padding(end = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) { Text(preset.name, style = MaterialTheme.typography.labelMedium) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 保底操作栏 ──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = {
                        if (undoStack.isNotEmpty()) {
                            val prev = undoStack.removeAt(undoStack.lastIndex)
                            prev.forEach { (path, text) ->
                                try { ProjectManager.writeFile(path, text) } catch (e: Exception) {}
                            }
                            doc = doc.copy(sources = prev)
                            if (doc.primaryPath in prev) onApplyCode(prev[doc.primaryPath] ?: "")
                            onToast(t("已撤销上一步"))
                        } else onToast(t("没有可撤销的操作"))
                    },
                    enabled = undoStack.isNotEmpty(),
                ) { Icon(Icons.Filled.Restore, contentDescription = t("撤销")) }
                Text("可撤销 ${undoStack.size} 步", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        undoStack.clear()
                        originalSources.forEach { (path, text) ->
                            try { ProjectManager.writeFile(path, text) } catch (e: Exception) {}
                        }
                        doc = doc.copy(sources = originalSources.toMap())
                        if (doc.primaryPath in originalSources) onApplyCode(originalSources[doc.primaryPath] ?: "")
                        onToast(t("已恢复原始代码"))
                    },
                    colors = ButtonDefaults.outlinedButtonColors(),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("恢复原始"))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 高保真画布：按官方公式把元素画成可拖动框，加载真实图片/字体/颜色。 */
@Composable
private fun LayoutCanvas(
    doc: RpyLayoutParser.LayoutDoc,
    projectPath: String,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onCommitDrag: (key: String, newXFrac: Float, newYFrac: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val canvasRatio = doc.theme.width.toFloat() / doc.theme.height.toFloat()
    // 拖动时靠这个 tick 触发重组，让元素框跟手移动
    var dragTick by remember { mutableIntStateOf(0) }

    // 图片缓存：ref -> ImageBitmap
    val imageCache = remember(doc, projectPath) {
        doc.allElements.mapNotNull { el ->
            el.imageRef?.let { ref ->
                RpyLayoutParser.resolveImage(projectPath, ref)?.let { path ->
                    val bmp = try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        val sample = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) / 1600f).roundToInt())
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    } catch (e: Exception) { null }
                    ref to bmp?.asImageBitmap()
                }
            }
        }.toMap()
    }
    fun imageSize(ref: String): Pair<Int, Int>? {
        val bmp = imageCache[ref] ?: return null
        return bmp.width to bmp.height
    }

    // 主题字体（若项目里有对应 ttf）
    val textFontFamily = remember(doc, projectPath) {
        doc.theme.textFont?.let { fontName ->
            val path = File(File(projectPath, "game"), fontName).absolutePath
            if (File(path).isFile) {
                try { FontFamily(Font(File(path))) } catch (e: Exception) { null }
            } else null
        }
    }
    val textColor = remember(doc) { parseHexColor(doc.theme.textColor) ?: Color.White }

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cw = maxWidth
            val ch = maxHeight
            // 实际画布尺寸（保持宽高比，居中留边）
            val drawW: Float
            val drawH: Float
            if (cw / ch > canvasRatio) {
                drawH = (ch.value * density.density)
                drawW = drawH * canvasRatio
            } else {
                drawW = (cw.value * density.density)
                drawH = drawW / canvasRatio
            }
            val offsetX = ((cw.value * density.density) - drawW) / 2f
            val offsetY = ((ch.value * density.density) - drawH) / 2f

            // 背景：若有 main_menu/game_menu 背景图则用它，否则深色渐变底
            val bgRef = listOf("gui/main_menu.png", "gui/game_menu.png").firstOrNull { imageCache.containsKey(it) }
            val bgBitmap = bgRef?.let { imageCache[it] }

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color(0xFF1A1626),
                    topLeft = Offset(offsetX, offsetY),
                    size = Size(drawW, drawH),
                )
                if (bgBitmap != null) {
                    drawImage(
                        image = bgBitmap,
                        dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                        dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                    )
                }
                // 网格参考线
                for (i in 1..3) {
                    val gx = offsetX + drawW * i / 4f
                    val gy = offsetY + drawH * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(gx, offsetY), Offset(gx, offsetY + drawH))
                    drawLine(Color.White.copy(alpha = 0.08f), Offset(offsetX, gy), Offset(offsetX + drawW, gy))
                }
            }

            // 元素框（可拖动）
            @Suppress("UNUSED_VARIABLE") val tick = dragTick // 建立重组依赖
            doc.allElements.forEach { el ->
                val rect = RpyLayoutParser.computeRect(el, doc.theme, drawW, drawH) { imageSize(it) }
                // 叠加临时拖动偏移（像素），让框跟手移动
                val liveX = rect.x + el.dragOffsetX * drawW
                val liveY = rect.y + el.dragOffsetY * drawH
                val leftDp = with(density) { (offsetX + liveX).toDp() }
                val topDp = with(density) { (offsetY + liveY).toDp() }
                val wDp = with(density) { rect.w.toDp() }
                val hDp = with(density) { rect.h.toDp() }
                val isSelected = selectedKey == el.key
                val tint = elementColor(el.kind)

                Box(
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .size(width = wDp, height = hDp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color.White else tint,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .background(tint.copy(alpha = if (isSelected) 0.28f else 0.14f), RoundedCornerShape(4.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .pointerInput(el.key) {
                            detectDragGestures(
                                onDragStart = { onSelect(el.key) },
                                onDragEnd = {
                                    // 用当前框位置反算 frac 后提交
                                    onCommitDrag(el.key, el.currentXFrac(drawW), el.currentYFrac(drawH))
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                // 实时预览：更新临时偏移并触发重组，让框跟手移动
                                el.dragOffsetX += dragAmount.x / drawW
                                el.dragOffsetY += dragAmount.y / drawH
                                dragTick++
                            }
                        },
                    contentAlignment = Alignment.TopStart,
                ) {
                    // 真实图片
                    val img = el.imageRef?.let { imageCache[it] }
                    if (img != null) {
                        androidx.compose.foundation.Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // 文本预览（真实字体/颜色/字号）
                    if (el.textPreview != null && el.kind in setOf(
                            RpyLayoutParser.ElementKind.TEXT, RpyLayoutParser.ElementKind.TEXTBUTTON,
                            RpyLayoutParser.ElementKind.LABEL,
                        )) {
                        Text(
                            text = el.textPreview!!,
                            color = textColor,
                            fontFamily = textFontFamily,
                            fontSize = with(density) { (doc.theme.textSize * (drawH / doc.theme.height)).toSp() },
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                    // 元素标签
                    Text(
                        text = el.label,
                        color = Color.White,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .background(tint, RoundedCornerShape(bottomEnd = 4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** 元素拖动偏移量（临时预览状态，存在元素对象里）。 */
private var RpyLayoutParser.LayoutElement.dragOffsetX: Float
    get() = DragOffsets.map.getOrPut(key) { 0f }
    set(value) { DragOffsets.map[key] = value }
private var RpyLayoutParser.LayoutElement.dragOffsetY: Float
    get() = DragOffsets.map.getOrPut(key) { 0f }
    set(value) { DragOffsets.map[key] = value }
private object DragOffsets { val map = mutableMapOf<String, Float>() }

/** 元素当前 frac（含拖动偏移）。 */
private fun RpyLayoutParser.LayoutElement.currentXFrac(canvasW: Float): Float {
    val p = properties["xalign"] ?: properties["xpos"]
    val base = if (p?.unit == RpyLayoutParser.ValueUnit.FRACTION) (p?.number ?: 0f) else ((p?.number ?: 0f) / canvasW)
    return (base + dragOffsetX).coerceIn(0f, 1f)
}
private fun RpyLayoutParser.LayoutElement.currentYFrac(canvasH: Float): Float {
    val p = properties["yalign"] ?: properties["ypos"]
    val base = if (p?.unit == RpyLayoutParser.ValueUnit.FRACTION) (p?.number ?: 0f) else ((p?.number ?: 0f) / canvasH)
    return (base + dragOffsetY).coerceIn(0f, 1f)
}

/** 属性编辑器：滑块 + 数值，覆盖该元素所有可编辑的定位属性。 */
@Composable
private fun PropertyEditor(
    element: RpyLayoutParser.LayoutElement,
    onCommit: (propName: String, newNumber: Float, unit: RpyLayoutParser.ValueUnit) -> Unit,
) {
    val editable = element.properties.values.filter { it.isEditable }
    if (editable.isEmpty()) {
        Text(t("此元素没有可编辑的数值属性（可能是动态表达式）"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        editable.forEach { prop ->
            var value by remember(prop.name + element.key) { mutableStateOf(prop.number ?: 0f) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(prop.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                Slider(
                    value = value.coerceIn(0f, 1f),
                    onValueChange = { value = it },
                    onValueChangeFinished = { onCommit(prop.name, value, prop.unit) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(RpyLayoutParser.formatNumber(value, prop.unit),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(40.dp))
            }
        }
    }
}

/** 快捷预设。 */
private data class Preset(val name: String, val props: Map<String, Pair<Float, RpyLayoutParser.ValueUnit>>)
private val PRESETS = listOf(
    Preset(t("居中"), mapOf(
        "xalign" to Pair(0.5f, RpyLayoutParser.ValueUnit.FRACTION),
        "yalign" to Pair(0.5f, RpyLayoutParser.ValueUnit.FRACTION))),
    Preset(t("底部居中"), mapOf(
        "xalign" to Pair(0.5f, RpyLayoutParser.ValueUnit.FRACTION),
        "yalign" to Pair(1.0f, RpyLayoutParser.ValueUnit.FRACTION))),
    Preset(t("左上"), mapOf(
        "xalign" to Pair(0.0f, RpyLayoutParser.ValueUnit.FRACTION),
        "yalign" to Pair(0.0f, RpyLayoutParser.ValueUnit.FRACTION))),
    Preset(t("全宽"), mapOf(
        "xsize" to Pair(1.0f, RpyLayoutParser.ValueUnit.FRACTION),
        "xalign" to Pair(0.5f, RpyLayoutParser.ValueUnit.FRACTION))),
)

private fun buildPresetEdits(
    element: RpyLayoutParser.LayoutElement,
    preset: Preset,
): List<RpyLayoutParser.ValueEdit> = buildList {
    preset.props.forEach { (name, value) ->
        val prop = element.properties[name]
        if (prop != null && prop.isEditable) {
            add(RpyLayoutParser.ValueEdit(
                element.sourcePath, prop.startOffset, prop.endOffset,
                RpyLayoutParser.formatNumber(value.first, value.second),
            ))
        }
    }
}

/** 解析 #rrggbb / #rrggbbaa 颜色。 */
private fun parseHexColor(hex: String?): Color? {
    if (hex == null) return null
    val h = hex.trim().removePrefix("#")
    return try {
        when (h.length) {
            6 -> Color(("FF" + h).toLong(16))
            8 -> Color(h.toLong(16))
            else -> null
        }
    } catch (e: Exception) { null }
}

/** 按元素类型给一个区分色。 */
private fun elementColor(kind: RpyLayoutParser.ElementKind): Color = when (kind) {
    RpyLayoutParser.ElementKind.WINDOW, RpyLayoutParser.ElementKind.FRAME,
    RpyLayoutParser.ElementKind.FIXED, RpyLayoutParser.ElementKind.VIEWPORT -> Color(0xFF7B61FF)
    RpyLayoutParser.ElementKind.ADD, RpyLayoutParser.ElementKind.IMAGE,
    RpyLayoutParser.ElementKind.IMAGEBUTTON -> Color(0xFFFFB74D)
    RpyLayoutParser.ElementKind.TEXT, RpyLayoutParser.ElementKind.LABEL,
    RpyLayoutParser.ElementKind.TEXTBUTTON -> Color(0xFF4CAF50)
    else -> Color(0xFF03A9F4)
}
