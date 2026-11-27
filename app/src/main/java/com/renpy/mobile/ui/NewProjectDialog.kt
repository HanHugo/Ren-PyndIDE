package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.project.ProjectManager
import kotlinx.coroutines.delay
import com.renpy.mobile.i18n.t

private data class ProjectTemplate(
    val name: String,
    val desc: String,
    val icon: ImageVector,
)

private val TEMPLATES = listOf(
    ProjectTemplate(t("空白项目"), t("从零开始的纯净工程"), Icons.Filled.Description),
    ProjectTemplate(t("视觉小说"), t("含对话与立绘的模板"), Icons.Filled.ViewAgenda),
    ProjectTemplate(t("示例项目"), t("完整演示工程"), Icons.Filled.Extension),
)

private val STEP_TITLES = listOf(t("项目名称"), t("选择模板"), t("存储位置"), t("完成"))

/**
 * 新建项目弹窗 —— 4 步向导（名称 → 模板 → 路径 → 完成）。
 * 纯 UI 演示：点「创建」只播放打勾动画后关闭，不写真实项目创建逻辑。
 * - 顶部关闭按钮 + 步骤指示器；底部上一步/下一步
 * - 步骤切换方向感知（前进右入、后退左入），全走 motionScheme
 * - 点遮罩 / 关闭按钮都能关；创建成功后短暂展示打勾再自动关闭
 */
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreated: (ProjectInfo) -> Unit = {},
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var projectName by remember { mutableStateOf("MyFirstGame") }
    var templateIndex by remember { mutableIntStateOf(0) }
    var created by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 真正创建项目：建目录 + 写初始文件 + 元数据，成功后回调上层
    fun doCreate() {
        error = null
        try {
            val info = ProjectManager.createProject(context, projectName.trim(), templateIndex)
            created = true
            onCreated(info)
        } catch (e: Exception) {
            error = e.message ?: t("创建失败")
        }
    }

    // 创建成功后短暂停留展示打勾，再关闭
    LaunchedEffect(created) {
        if (created) {
            delay(1000)
            onDismiss()
        }
    }

    // AnimatedContent 的 spec 需在 composable 上下文提前算好（transitionSpec 非 composable）
    val spatial: FiniteAnimationSpec<IntOffset> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                // ── 顶部：关闭按钮 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("新建项目"),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = t("关闭"))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    STEP_TITLES[step],
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(14.dp))

                // ── 步骤指示器：当前段加宽高亮 ──
                StepIndicator(current = step, total = STEP_TITLES.size)
                Spacer(Modifier.height(22.dp))

                // ── 步骤内容（方向感知切换）──
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (forward) {
                            (slideInHorizontally(spatial) { it / 6 } + fadeIn(effects)) togetherWith
                                (slideOutHorizontally(spatial) { -it / 6 } + fadeOut(effects))
                        } else {
                            (slideInHorizontally(spatial) { -it / 6 } + fadeIn(effects)) togetherWith
                                (slideOutHorizontally(spatial) { it / 6 } + fadeOut(effects))
                        }
                    },
                    label = "newProjectStep",
                ) { currentStep ->
                    when (currentStep) {
                        0 -> NameStep(projectName) { projectName = it }
                        1 -> TemplateStep(templateIndex) { templateIndex = it }
                        2 -> PathStep(projectName)
                        3 -> FinishStep(projectName, TEMPLATES[templateIndex].name, created, error)
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── 底部操作区 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0 && !created) {
                        TextButton(onClick = {
                            forward = false
                            step--
                        }) {
                            Text(t("上一步"))
                        }
                    } else if (step == 0 && !created) {
                        TextButton(onClick = onDismiss) {
                            Text(t("取消"))
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Spacer(Modifier.weight(1f))

                    if (step < STEP_TITLES.lastIndex) {
                        Button(
                            onClick = {
                                forward = true
                                step++
                            },
                            enabled = projectName.isNotBlank(),
                        ) {
                            Text(t("下一步"))
                        }
                    } else if (!created) {
                        Button(onClick = { doCreate() }) {
                            Text(t("创建"))
                        }
                    }
                }
            }
        }
    }
}

/** 分段步骤指示器：当前段加宽并高亮。 */
@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        for (i in 0 until total) {
            val active = i == current
            val done = i < current
            val barColor = when {
                active || done -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(
                modifier = Modifier
                    .weight(if (active) 2f else 1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
            if (i < total - 1) Spacer(Modifier.width(6.dp))
        }
    }
}

/** 第 1 步：项目名称。 */
@Composable
private fun NameStep(name: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            t("给你的项目起个名字，它将作为工程目录。"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onChange,
            label = { Text(t("项目名称")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            t("例如 MyFirstGame、TheGreatAdventure"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 第 2 步：模板选择。 */
@Composable
private fun TemplateStep(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            t("选择一个起始模板，之后仍可调整。"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TEMPLATES.forEachIndexed { index, tpl ->
            val chosen = index == selected
            val container = if (chosen) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
            val content = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clickable { onSelect(index) },
                shape = RoundedCornerShape(18.dp),
                color = container,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tpl.icon, contentDescription = null, tint = content)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tpl.name, style = MaterialTheme.typography.titleSmall, color = content)
                        Text(tpl.desc, style = MaterialTheme.typography.bodySmall, color = content)
                    }
                    if (chosen) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = t("已选择"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 第 3 步：存储位置。 */
@Composable
private fun PathStep(name: String) {
    val context = LocalContext.current
    val path = "${ProjectManager.projectsRoot(context).absolutePath}/${name.trim()}"
    val rootLabel = when (ProjectManager.rootKind(context)) {
        ProjectManager.RootKind.PRIVATE -> t("App 私有目录")
        ProjectManager.RootKind.PUBLIC -> t("公共文档目录")
        ProjectManager.RootKind.CUSTOM -> t("自选文件夹")
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "项目将保存到$rootLabel（可在「设置 → 项目存储」中更改）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                path,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            t("项目名仅支持字母、数字、下划线与连字符。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 第 4 步：完成摘要 + 打勾动画。 */
@Composable
private fun FinishStep(
    name: String,
    templateName: String,
    created: Boolean,
    error: String? = null,
) {
    val context = LocalContext.current
    val path = "${ProjectManager.projectsRoot(context).absolutePath}/${name.trim()}"
    val checkScale by animateFloatAsState(
        targetValue = if (created) 1f else 0.7f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "projectCreatedCheck",
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (created) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "projectCreatedAlpha",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!created) {
            Text(
                t("准备就绪"),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            SummaryRow(t("名称"), name)
            SummaryRow(t("模板"), templateName)
            SummaryRow(t("位置"), path)
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "创建失败：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                        alpha = checkAlpha
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = t("创建成功"),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                t("项目已创建"),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
