package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.renpy.mobile.ui.theme.IdeSemanticColors
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renpy.mobile.model.LogEntry
import com.renpy.mobile.model.LogLevel
import com.renpy.mobile.i18n.t

/**
 * 底部构建日志（Expressive 重构版）：
 * - 收起态是一条贴底胶囊：状态点 + 标题 + 错误/警告计数 + 条数 + 展开箭头；
 * - 展开态是抽屉：拖拽把手 + 复制/清空操作 + 分级日志列表；
 * - 日志分级：过程信息淡色小字，错误用等宽字体 + 浅红底 + 图标，可点击跳转源码；
 * - 高度最高半屏，形状/高度过渡走 motionScheme，兼容 Reduce Motion。
 */
@Composable
fun LogPanel(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier,
    compiling: Boolean = false,
    compileDone: Int = 0,
    compileTotal: Int = 0,
    compileFile: String = "",
    compilePhase: String = "",
    onClear: () -> Unit = {},
    onLocate: (LogEntry) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val spatial: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val corner by animateDpAsState(
        targetValue = if (expanded) 24.dp else 28.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "logCorner",
    )

    val errors = logs.count { it.level == LogLevel.ERROR }
    val warnings = logs.count { it.level == LogLevel.WARNING }
    // 编译进行中不能判定成功/失败：此时 errors 天然为 0，旧逻辑会把"构建成功"
    // 提前显示出来，让用户误以为编译已完成却卡在 0%。只有编译结束后才下定论。
    val buildOk = !compiling && errors == 0
    val buildLabel = if (compiling) t("构建中…") else if (buildOk) t("构建成功") else t("构建失败")
    val statusColor = when {
        compiling -> MaterialTheme.colorScheme.primary
        buildOk -> IdeSemanticColors.success
        else -> MaterialTheme.colorScheme.error
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(corner),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            tonalElevation = 3.dp,
        ) {
            Column {
                // ── 拖拽把手（仅展开态）──
                if (expanded) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 8.dp, bottom = 2.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
                    )
                }

                // ── 胶囊状态行：点击切换 + 上滑拉出 / 下滑收起 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -6f) expanded = true
                                else if (dragAmount > 6f) expanded = false
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        buildLabel,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    if (errors > 0) CountBadge(errors, t("错误"), MaterialTheme.colorScheme.error)
                    if (warnings > 0) {
                        Spacer(Modifier.width(6.dp))
                        CountBadge(warnings, t("警告"), IdeSemanticColors.warning)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${logs.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.ExpandLess,
                        contentDescription = if (expanded) t("收起日志") else t("展开日志"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = if (expanded) 0f else 180f },
                    )
                }

                // ── 编译进度（编译中显示；收起态也可见，无需展开日志）──
                // 刻意不用 AnimatedVisibility/高度动画：编译期间每 600ms 更新一次，
                // 反复触发布局重测与帧提交曾在 Mali GPU 上触发 RenderThread fence abort。
                // 进度区用静态 if + 确定性进度条，无任何 spring/高度动画。
                if (compiling) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = compilePhase.ifBlank { t("编译中…") },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Spacer(Modifier.weight(1f))
                            if (compileTotal > 0) {
                                Text(
                                    text = "${compileDone} / ${compileTotal}  ·  ${compileDone * 100 / compileTotal}%",
                                    style = MaterialTheme.typography.labelMediumEmphasized,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    text = t("准备中…"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (compileFile.isNotBlank()) {
                            Text(
                                text = compileFile,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        val progress = if (compileTotal > 0)
                            (compileDone.toFloat() / compileTotal).coerceIn(0f, 1f) else 0f
                        if (compileTotal > 0) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        }
                    }
                }

                // ── 展开的汇总操作行 ──
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = spatial) + fadeIn(animationSpec = effects),
                    exit = shrinkVertically(animationSpec = spatial) + fadeOut(animationSpec = effects),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                val text = logs.joinToString("\n") { "[${it.time}] ${it.message}" }
                                clipboard.setText(AnnotatedString(text))
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy, contentDescription = t("复制全部日志"),
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Filled.DeleteSweep, contentDescription = t("清空日志"),
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            t("下滑收起"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── 分级日志列表 ──
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = spatial) + fadeIn(animationSpec = effects),
                    exit = shrinkVertically(animationSpec = spatial) + fadeOut(animationSpec = effects),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        // 稳定 key（日志只追加/全清，索引即可）；避免新增日志时全量重测导致 RenderThread 压力
                        items(count = logs.size, key = { it }) { idx ->
                            LogRow(logs[idx], onLocate)
                        }
                        item { Spacer(Modifier.height(6.dp)) }
                    }
                }
            }
        }
    }
}

/** 单条日志：ERROR 用等宽块 + 浅红底 + 图标，可点击跳转；其他级别用紧凑行。 */
@Composable
private fun LogRow(log: LogEntry, onLocate: (LogEntry) -> Unit) {
    if (log.level == LogLevel.ERROR) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                .then(if (log.hasLocation) Modifier.clickable { onLocate(log) } else Modifier)
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Error, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    log.time,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log.hasLocation) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${log.file.substringAfterLast('/')}:${log.line}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                log.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                logIcon(log.level), contentDescription = null,
                tint = logColor(log.level),
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                log.time,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                log.message,
                fontSize = 12.sp,
                color = logColor(log.level),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CountBadge(count: Int, label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count", style = MaterialTheme.typography.labelSmall, color = color)
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun logIcon(level: LogLevel): ImageVector = when (level) {
    LogLevel.INFO -> Icons.Filled.Info
    LogLevel.WARNING -> Icons.Filled.Warning
    LogLevel.ERROR -> Icons.Filled.Error
    LogLevel.SUCCESS -> Icons.Filled.TaskAlt
}

@Composable
private fun logColor(level: LogLevel): Color = when (level) {
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.WARNING -> IdeSemanticColors.warning
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.SUCCESS -> IdeSemanticColors.success
}
