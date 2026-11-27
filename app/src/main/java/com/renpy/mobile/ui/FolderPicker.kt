package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.renpy.mobile.project.ProjectManager
import com.renpy.mobile.termux.TermuxBootstrap
import java.io.File
import com.renpy.mobile.i18n.t

/**
 * 文件夹选择器 —— 基于真实文件系统枚举（App 已拥有 MANAGE_EXTERNAL_STORAGE）。
 *
 * 之所以不用 SAF 的 ACTION_OPEN_DOCUMENT_TREE：本项目所有文件操作、native 编译、
 * Python 打包脚本都吃真实路径字符串，SAF 返回的是 content:// Uri 无法直接使用。
 *
 * - 顶部当前路径 + 返回上级 + 快捷入口（公共文档/内部存储/私有目录）
 * - 目录列表点击进入，只列出可进入的子目录
 * - 可新建文件夹
 * - 底部「选择当前目录」返回真实绝对路径；不可写目录给出提示但仍允许选（迁移时会校验）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(
    initialPath: String? = null,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val internalRoot = remember { File(EnvironmentCompat.externalStorageDir()) }
    val publicDocs = remember { ProjectManager.publicRoot().parentFile?.parentFile ?: ProjectManager.publicRoot() }
    val privateRoot = remember { TermuxBootstrap.homeDir(context) }

    var current by remember {
        mutableStateOf(
            initialPath?.let { File(it) }?.takeIf { it.isDirectory } ?: ProjectManager.publicRoot().parentFile
            ?: internalRoot
        )
    }
    var forward by remember { mutableStateOf(true) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var newFolderError by remember { mutableStateOf<String?>(null) }

    // 当前目录下的子目录（目录在前，按名排序），切换目录时刷新
    var children by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(current) {
        children = (current.listFiles { it.isDirectory } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun enter(dir: File) {
        if (dir.isDirectory) {
            forward = true
            current = dir
        }
    }

    fun goUp() {
        current.parentFile?.let {
            forward = false
            current = it
        }
    }

    val writable = ProjectManager.isWritableDir(current.absolutePath)
    val spatial: FiniteAnimationSpec<IntOffset> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

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
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                // ── 顶部标题 + 关闭 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("选择文件夹"),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = t("关闭"))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 当前路径 + 返回上级 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { goUp() },
                        enabled = current.parentFile != null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("上级目录"), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        current.absolutePath,
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── 快捷入口：M3 AssistChip（关联当前任务的导航动作）──
                Row(modifier = Modifier.fillMaxWidth()) {
                    AssistChip(
                        onClick = { enter(internalRoot) },
                        label = { Text(t("内部存储")) },
                        leadingIcon = { Icon(Icons.Filled.SdCard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = { enter(publicDocs) },
                        label = { Text(t("文档")) },
                        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = { enter(privateRoot) },
                        label = { Text(t("私有目录")) },
                        leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── 目录列表（方向感知切换）──
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp)),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    AnimatedContent(
                        targetState = current.absolutePath,
                        transitionSpec = {
                            if (forward) {
                                (slideInHorizontally(spatial) { it / 8 } + fadeIn(effects)) togetherWith
                                    (slideOutHorizontally(spatial) { -it / 8 } + fadeOut(effects))
                            } else {
                                (slideInHorizontally(spatial) { -it / 8 } + fadeIn(effects)) togetherWith
                                    (slideOutHorizontally(spatial) { it / 8 } + fadeOut(effects))
                            }
                        },
                        label = "folderList",
                    ) {
                        if (children.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    t("此文件夹为空"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(children, key = { it.absolutePath }) { dir ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { enter(dir) }
                                            .padding(horizontal = 16.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            dir.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (!writable) {
                    Text(
                        t("⚠ 此目录当前不可写，请确认已授予「所有文件访问」权限"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // ── 底部操作 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("新建文件夹"))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onPick(current.absolutePath) }) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("选择当前目录"))
                    }
                }
            }
        }
    }

    // 新建文件夹弹窗
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false; newFolderError = null },
            title = { Text(t("新建文件夹")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it; newFolderError = null },
                        label = { Text(t("文件夹名称")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (newFolderError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(newFolderError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFolderName.trim()
                    when {
                        name.isBlank() -> newFolderError = t("名称不能为空")
                        name.contains("/") || name.contains("\\") -> newFolderError = "名称不能包含 / 或 \\"
                        else -> {
                            val dir = File(current, name)
                            if (dir.exists()) newFolderError = t("同名文件夹已存在")
                            else if (dir.mkdirs()) {
                                showNewFolder = false; newFolderName = ""; newFolderError = null
                                children = (current.listFiles { it.isDirectory } ?: emptyArray())
                                    .sortedBy { it.name.lowercase() }.toList()
                            } else newFolderError = t("创建失败，请检查权限")
                        }
                    }
                }) { Text(t("创建")) }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false; newFolderError = null }) { Text(t("取消")) } },
        )
    }
}

/** 兼容取外部存储根目录，避免直接依赖已废弃的 Environment.getExternalStorageDirectory 常量告警。 */
private object EnvironmentCompat {
    fun externalStorageDir(): String = android.os.Environment.getExternalStorageDirectory().absolutePath
}
