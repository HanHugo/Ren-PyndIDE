package com.renpy.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.renpy.mobile.model.FileNode
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.project.ProjectManager
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.MessageTone
import com.renpy.mobile.ui.theme.reduceMotion
import java.io.File
import com.renpy.mobile.i18n.t

/** 拍平后的可见节点：携带缩进层级。 */
private data class VisibleNode(val node: FileNode, val depth: Int, val path: String)

/** 命名对话框请求。 */
private data class NameRequest(
    val title: String,
    val label: String,
    val initial: String,
    val confirmText: String = t("创建"),
    val onConfirm: (String) -> Unit,
)

/**
 * 侧边项目树：顶部操作栏（新建文件夹/文件、导入图片/音频）+ 树行删除。
 * - 当前操作目录 [currentDir]：点击文件夹即切换，操作都落在该目录；
 * - 创建/导入走命名对话框，删除走确认对话框；
 * - 每次写操作后回调 [onTreeChanged] 让上层重建文件树。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectTree(
    project: ProjectInfo,
    nodes: List<FileNode>,
    selectedPath: String = "",
    onFileClick: (FileNode) -> Unit = {},
    onTreeChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val reduced = reduceMotion(context)
    val expanded = remember { mutableStateListOf<String>() }
    var currentDir by remember(project.path) { mutableStateOf(project.path) }
    var nameDialog by remember { mutableStateOf<NameRequest?>(null) }
    var deleteTarget by remember { mutableStateOf<FileNode?>(null) }

    // 默认展开第一层目录
    remember(nodes) {
        expanded.clear()
        fun seed(list: List<FileNode>, depth: Int, prefix: String) {
            list.forEach { n ->
                val path = "$prefix/${n.name}"
                if (n.isDirectory && depth < 1) {
                    expanded.add(path)
                    seed(n.children, depth + 1, path)
                }
            }
        }
        seed(nodes, 0, "")
    }

    // 系统文件选择器：图片 / 音频
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            nameDialog = NameRequest(
                title = t("导入图片"),
                label = t("图片名称（含扩展名）"),
                initial = "image_${System.currentTimeMillis()}.png",
                confirmText = t("导入"),
            ) { name -> doImport(context, project, currentDir, name, uri, onTreeChanged) { msg, tone -> messenger.show(msg, tone) } }
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            nameDialog = NameRequest(
                title = t("导入音频"),
                label = t("音频名称（含扩展名）"),
                initial = "audio_${System.currentTimeMillis()}.ogg",
                confirmText = t("导入"),
            ) { name -> doImport(context, project, currentDir, name, uri, onTreeChanged) { msg, tone -> messenger.show(msg, tone) } }
        }
    }

    // 根据展开状态，把树拍平成可见行
    val visible = mutableListOf<VisibleNode>()
    fun flatten(list: List<FileNode>, depth: Int, prefix: String) {
        list.forEach { n ->
            val path = "$prefix/${n.name}"
            visible.add(VisibleNode(n, depth, path))
            if (n.isDirectory && path in expanded) flatten(n.children, depth + 1, path)
        }
    }
    flatten(nodes, 0, "")

    val currentDirName = if (currentDir == project.path) t("项目根目录") else File(currentDir).name

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // ── 顶部操作栏 ──
        item(key = "tree_actions") {
            FileTreeActions(
                currentDirName = currentDirName,
                onNewFolder = {
                    nameDialog = NameRequest(t("新建文件夹"), t("文件夹名称"), "new_folder") { name ->
                        runFs(
                            context,
                            { ProjectManager.createFolder(project, currentDir, name); true },
                            t("文件夹已创建"),
                            onTreeChanged,
                            { msg, tone -> messenger.show(msg, tone) },
                        )
                    }
                },
                onNewFile = {
                    nameDialog = NameRequest(t("新建文件"), t("文件名称"), "new_file.rpy") { name ->
                        runFs(
                            context,
                            { ProjectManager.createFile(project, currentDir, name); true },
                            t("文件已创建"),
                            onTreeChanged,
                            { msg, tone -> messenger.show(msg, tone) },
                        )
                    }
                },
                onImportImage = { imageLauncher.launch("image/*") },
                onImportAudio = { audioLauncher.launch("audio/*") },
            )
        }

        items(items = visible, key = { it.path }) { item ->
            TreeRow(
                modifier = Modifier.animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
                item = item,
                expanded = item.path in expanded,
                selected = !item.node.isDirectory && item.node.path.isNotEmpty() && item.node.path == selectedPath,
                onToggle = {
                    if (item.node.isDirectory) {
                        // 点击文件夹：设为当前操作目录，并展开/折叠
                        currentDir = item.node.path
                        if (item.path in expanded) expanded.remove(item.path) else expanded.add(item.path)
                    } else {
                        onFileClick(item.node)
                    }
                },
                onDelete = { deleteTarget = item.node },
            )
        }
    }

    // ── 命名对话框（新建 / 导入）──
    nameDialog?.let { req ->
        NameDialog(request = req, onDismiss = { nameDialog = null })
    }

    // ── 删除确认对话框（全 App 统一样式）──
    deleteTarget?.let { target ->
        DestructiveConfirmDialog(
            title = "删除${if (target.isDirectory) "文件夹" else t("文件")}？",
            message = if (target.isDirectory)
                "将删除「${target.name}」及其中的全部内容，此操作不可撤销。"
            else
                "将删除「${target.name}」，此操作不可撤销。",
            confirmLabel = t("删除"),
            onDismiss = { deleteTarget = null },
            onConfirm = {
                runFs(
                    context,
                    { ProjectManager.deleteNode(project, target.path) },
                    t("已删除"),
                    onTreeChanged,
                    { msg, tone -> messenger.show(msg, tone) },
                )
                // 若删掉的正是当前目录，回退到项目根
                if (currentDir == target.path || currentDir.startsWith(target.path + File.separator)) {
                    currentDir = project.path
                }
                deleteTarget = null
            },
        )
    }
}

/** 执行文件系统操作，统一 Snackbar 反馈与树刷新。 */
private fun runFs(
    context: android.content.Context,
    action: () -> Boolean,
    successMsg: String,
    onChanged: () -> Unit,
    onMessage: (String, MessageTone) -> Unit,
) {
    try {
        if (action()) {
            onMessage(successMsg, MessageTone.SUCCESS)
            onChanged()
        } else {
            onMessage(t("操作失败"), MessageTone.ERROR)
        }
    } catch (e: Exception) {
        onMessage(e.message ?: t("操作失败"), MessageTone.ERROR)
    }
}

/** 导入 Uri 到当前目录并命名。 */
private fun doImport(
    context: android.content.Context,
    project: ProjectInfo,
    parentPath: String,
    name: String,
    uri: Uri,
    onChanged: () -> Unit,
    onMessage: (String, MessageTone) -> Unit,
) {
    try {
        ProjectManager.importAsset(context, project, parentPath, name, uri)
        onMessage("已导入 $name", MessageTone.SUCCESS)
        onChanged()
    } catch (e: Exception) {
        onMessage("导入失败：${e.message}", MessageTone.ERROR)
    }
}

/** 顶部操作栏（Expressive）：pill 容器 + 4 个带标签的圆角方形操作块。 */
@Composable
private fun FileTreeActions(
    currentDirName: String,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
) {
    val actionScroll = rememberScrollState()
    val reduced = reduceMotion(LocalContext.current)
    // 还能继续向右滑时显示渐隐边缘 + 摆动箭头，明确告诉用户这一行可以滑动
    val canScrollMore by remember {
        derivedStateOf {
            actionScroll.maxValue > 0 && actionScroll.value < actionScroll.maxValue - 4f
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Box {
            // 横向滚动区：每个 tile 固定宽度，窄屏不挤压变形，可左右滑动查看全部操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(actionScroll)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionTile(Icons.Filled.CreateNewFolder, t("新建文件夹"),
                    MaterialTheme.colorScheme.primary, onNewFolder)
                ActionTile(Icons.Filled.NoteAdd, t("新建文件"),
                    MaterialTheme.colorScheme.primary, onNewFile)
                ActionTile(Icons.Filled.Image, t("导入图片"),
                    MaterialTheme.colorScheme.tertiary, onImportImage)
                ActionTile(Icons.Filled.Audiotrack, t("导入音频"),
                    MaterialTheme.colorScheme.tertiary, onImportAudio)
            }

            // ── 可滑动提示：右侧渐隐遮罩 + 左右摆动的箭头 ──
            androidx.compose.animation.AnimatedVisibility(
                visible = canScrollMore,
                enter = androidx.compose.animation.fadeIn(
                    MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
                exit = androidx.compose.animation.fadeOut(
                    MaterialTheme.motionScheme.fastEffectsSpec(),
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            ) {
                val infinite = rememberInfiniteTransition(label = "scrollHint")
                // Reduce Motion 时固定为静态位置，箭头仍可见提示可滑动，但不做循环位移
                val dx by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = if (reduced) 0f else 8f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "scrollHintDx",
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(52.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = t("向右滑动查看更多"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(18.dp)
                            .offset(x = dx.dp),
                    )
                }
            }
        }
    }

    // 当前目录 chip：pill 形 + 文件夹图标 + 强调标签
    Surface(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = currentDirName,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** 单个操作块：圆角方形图标容器 + 下方小标签，按压弹性缩放。 */
@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tileScale = pressScale(pressed)
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                scaleX = tileScale
                scaleY = tileScale
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = tint.copy(alpha = 0.14f),
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = tint)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        )
    }
}

/** 通用命名对话框。 */
@Composable
private fun NameDialog(request: NameRequest, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(request.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(request.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) {
                    request.onConfirm(text.trim())
                    onDismiss()
                }
            }) { Text(request.confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消")) } },
    )
}

@Composable
private fun TreeRow(
    modifier: Modifier = Modifier,
    item: VisibleNode,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val node = item.node
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rotationSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = rotationSpec,
        label = "treeChevron",
    )

    val selectedColor = MaterialTheme.colorScheme.secondaryContainer
    val rowShape = RoundedCornerShape(16.dp)
    val rowScale = pressScale(pressed)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(rowShape)
            .background(if (selected) selectedColor else Color.Transparent)
            .graphicsLayer {
                scaleX = rowScale
                scaleY = rowScale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(start = (4 + item.depth * 16).dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 目录：展开箭头（spring 旋转）
            if (node.isDirectory) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(16.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
                Spacer(Modifier.width(2.dp))
            } else {
                Spacer(Modifier.width(18.dp))
            }

            // 图标容器：圆角方形 tonal 底 + 类型色图标（Expressive 不用统一圆角矩形）
            val iconTint = if (node.isDirectory) MaterialTheme.colorScheme.primary
            else fileIconTint(node.name)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = iconTint.copy(alpha = 0.13f),
            ) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (node.isDirectory)
                            (if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder)
                        else fileIcon(node.name),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            Text(
                text = node.name,
                style = if (selected) MaterialTheme.typography.bodyMediumEmphasized
                else MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // 删除按钮：默认弱化，按压/选中时显现（48dp 触摸目标）
            val deleteInteraction = remember { MutableInteractionSource() }
            val deletePressed by deleteInteraction.collectIsPressedAsState()
            val deleteAlpha = if (selected || deletePressed) 1f else 0.45f
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .graphicsLayer { alpha = deleteAlpha }
                    .clickable(interactionSource = deleteInteraction,
                        indication = null, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除 ${node.name}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // 选中态：左侧 primary 指示条
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun fileIcon(name: String) = when {
    name.endsWith(".png", true) || name.endsWith(".jpg", true) -> Icons.Filled.Image
    name.endsWith(".ogg", true) || name.endsWith(".wav", true) -> Icons.Filled.MusicNote
    else -> Icons.Filled.Description
}

@Composable
private fun fileIconTint(name: String): Color = when {
    name.endsWith(".rpy", true) -> MaterialTheme.colorScheme.primary
    name.endsWith(".png", true) || name.endsWith(".jpg", true) -> Color(0xFF4CAF50)
    name.endsWith(".ogg", true) || name.endsWith(".wav", true) -> Color(0xFFFF9800)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
