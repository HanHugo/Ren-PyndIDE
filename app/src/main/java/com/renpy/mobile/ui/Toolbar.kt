package com.renpy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.renpy.mobile.ui.theme.IdeShapes
import com.renpy.mobile.i18n.t

/**
 * 顶部工具栏（Expressive）：
 * - 左：项目树开关
 * - 右：手册 / 布局助手（占位）/ 扳手（弹出快捷菜单，含字号缩放）/ 保存 / 运行（无背景 CTA）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppToolbar(
    drawerOpen: Boolean,
    onToggleDrawer: () -> Unit,
    quickToolsOpen: Boolean = false,
    onToggleQuickTools: () -> Unit = {},
    onToggleLayoutAssistant: () -> Unit = {},
    onOpenManual: () -> Unit = {},
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onResetFont: () -> Unit = {},
    onRun: () -> Unit = {},
    onCancelCompile: () -> Unit = {},
    compiling: Boolean = false,
    onBuild: () -> Unit = {},
    building: Boolean = false,
    onSave: () -> Unit = {},
) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onToggleDrawer) {
                Icon(
                    imageVector = if (drawerOpen) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                    contentDescription = t("项目树"),
                )
            }
        },
        actions = {
            // 手册：打开应用内离线手册
            IconButton(onClick = onOpenManual) {
                Icon(
                    Icons.Filled.MenuBook,
                    contentDescription = t("手册"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 布局助手：占位入口，后续接入面板布局配置
            IconButton(onClick = onToggleLayoutAssistant) {
                Icon(
                    Icons.Filled.Dashboard,
                    contentDescription = t("布局助手"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 扳手：锚定一个竖向弹出菜单，展开时高亮
            Box {
                IconButton(onClick = onToggleQuickTools) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (quickToolsOpen) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = if (quickToolsOpen) t("收起快捷栏") else t("展开快捷栏"),
                            tint = if (quickToolsOpen) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                QuickToolsMenu(
                    expanded = quickToolsOpen,
                    onDismiss = onToggleQuickTools,
                    onZoomIn = onZoomIn,
                    onZoomOut = onZoomOut,
                    onResetFont = onResetFont,
                )
            }
            // 打包：把游戏打成 Android APK
            IconButton(onClick = onBuild, enabled = !building && !compiling) {
                if (building) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        Icons.Filled.Android,
                        contentDescription = t("打包 APK"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            // 保存：写回当前文件
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = t("保存"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 运行/编译：无背景 CTA；编译中变成「停止」按钮，点击可取消编译
            IconButton(onClick = if (compiling) onCancelCompile else onRun) {
                if (compiling) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = t("取消编译"),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = t("运行"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

/** 扳手锚定的竖向快捷菜单：撤销 / 缩进 / 搜索 / 跳行 / Lint / 字号缩放。 */
@Composable
private fun QuickToolsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetFont: () -> Unit,
) {
    val editItems: List<Pair<ImageVector, String>> = listOf(
        Icons.AutoMirrored.Filled.Undo to t("撤销"),
        Icons.AutoMirrored.Filled.FormatIndentIncrease to t("缩进"),
        Icons.Filled.FindInPage to t("搜索"),
        Icons.Filled.Numbers to t("跳行"),
        Icons.Filled.Checklist to "Lint",
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // 命令面板圆角对齐 M3 CornerMedium token（shape 为 DropdownMenu 稳定参数）
        shape = IdeShapes.Medium,
    ) {
        editItems.forEach { (icon, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                onClick = onDismiss,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        DropdownMenuItem(
            text = { Text(t("放大字号")) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = { onZoomIn(); onDismiss() },
            modifier = Modifier.padding(vertical = 2.dp),
        )
        DropdownMenuItem(
            text = { Text(t("缩小字号")) },
            leadingIcon = { Icon(Icons.Filled.Remove, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = { onZoomOut(); onDismiss() },
            modifier = Modifier.padding(vertical = 2.dp),
        )
        DropdownMenuItem(
            text = { Text(t("重置字号")) },
            leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = { onResetFont(); onDismiss() },
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
