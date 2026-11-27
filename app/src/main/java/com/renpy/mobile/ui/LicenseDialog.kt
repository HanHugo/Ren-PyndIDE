package com.renpy.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.renpy.mobile.i18n.t

/** 一条开源组件许可信息。 */
private data class LicenseItem(
    val name: String,
    val license: String,
    val use: String,
    val note: String,
    val url: String,
)

/** 清单：Ren'Py（用得最多、最核心）放第一位。 */
private val LICENSES = listOf(
    LicenseItem(
        t("Ren'Py 引擎 / SDK"),
        t("MIT + 部分 LGPL-2.1（模板 CC0）"),
        t("编译引擎、SDK、RAPT 打包工具链——本软件最核心的依赖"),
        t("Ren'Py 大部分代码遵循 MIT 许可证，部分组件源自 LGPL-2.1 代码；SDK 生成的模板文件（gui.rpy / script.rpy 等）为 CC0 1.0。本软件包含遵循多种许可证的开源软件，包括 GNU 宽松通用公共许可证。"),
        "https://www.renpy.org/doc/html/license.html",
    ),
    LicenseItem(
        "renpy-mcp",
        "AGPL-3.0",
        t("AI 助手工具逻辑的移植来源（读取/修改 .rpy 的工具集）"),
        t("AI 助手把 renpy-mcp 的工具逻辑移植到内置 Python 运行。作为独立进程调用，其 AGPL-3.0 许可在此如实标注；若用于闭源分发请自行评估合规。"),
        "https://github.com/fracturedring/renpy-mcp",
    ),
    LicenseItem(
        "CPython 3.12",
        "PSF License",
        t("内置 Python 运行时（librenpython.so 静态链接，用于编译与 AI 工具执行）"),
        t("Python Software Foundation License，允许自由使用与再分发，需保留版权与许可声明。"),
        "https://docs.python.org/3/license.html",
    ),
    LicenseItem(
        "Termux bootstrap",
        "GPL-3.0",
        t("终端与打包环境的 Linux 用户态（bash / apt 等）"),
        t("Termux 官方 aarch64 bootstrap，遵循 GNU GPL v3。"),
        "https://github.com/termux/termux-packages",
    ),
    LicenseItem(
        "JetBrains Mono",
        "OFL-1.1",
        t("代码编辑器字体"),
        t("SIL Open Font License 1.1，可自由使用与嵌入。"),
        "https://www.jetbrains.com/lp/mono/",
    ),
    LicenseItem(
        "Montserrat",
        "OFL-1.1",
        t("界面字体"),
        t("SIL Open Font License 1.1，可自由使用与嵌入。"),
        "https://fonts.google.com/specimen/Montserrat",
    ),
    LicenseItem(
        "Jetpack Compose / AndroidX",
        "Apache-2.0",
        t("UI 框架与 Android 基础库"),
        t("Apache License 2.0，允许自由使用与再分发。"),
        "https://developer.android.com/jetpack",
    ),
)

/**
 * 开源许可弹窗（MD3）：组件列表 + 点击展开许可证要点与官方链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 40.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
        ) {
            Column {
                TopAppBar(
                    title = { Text(t("开源许可"), style = MaterialTheme.typography.titleLargeEmphasized) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = t("关闭"))
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    item {
                        Text(
                            t("本软件包含以下开源组件，感谢它们的贡献。点击查看详情。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    items(LICENSES, key = { it.name }) { item ->
                        val isOpen = expanded == item.name
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { expanded = if (isOpen) null else item.name },
                            shape = RoundedCornerShape(18.dp),
                            color = if (isOpen) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isOpen) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            item.license,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isOpen) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.ExpandMore,
                                        contentDescription = if (isOpen) t("收起") else t("展开"),
                                        modifier = Modifier
                                            .size(22.dp)
                                            .graphicsLayer { rotationZ = if (isOpen) 180f else 0f },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                AnimatedVisibility(
                                    visible = isOpen,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut(),
                                ) {
                                    Column(modifier = Modifier.padding(top = 10.dp)) {
                                        Text(
                                            t("用途：") + item.use,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            item.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        TextButton(onClick = {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                                            }
                                        }) {
                                            Text(t("查看官方许可页"))
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
