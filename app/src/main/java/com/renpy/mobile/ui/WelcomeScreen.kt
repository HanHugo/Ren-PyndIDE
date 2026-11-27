package com.renpy.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.project.ProjectManager
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.MessageTone
import java.util.Date
import java.util.Locale
import com.renpy.mobile.i18n.t

/**
 * 欢迎 / 项目主页 —— Material 3 Expressive 视觉改造。
 * 内容与入口完全保留：顶部工具栏、Hero、快捷操作、最近项目、设置入口。
 * 视觉升级：超大渐变 emphasized 标题、pill 标签、2×2 彩色卡片网格、
 * 品牌径向光晕、48 色角色分层、复合 spring 动效。
 */
@Composable
fun WelcomeScreen(
    onOpenProject: (ProjectInfo) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenManual: () -> Unit = {},
) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    // 真实项目列表（进入时读取，新建/删除后刷新）
    var projects by remember { mutableStateOf(ProjectManager.listProjects(context)) }
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTick) { projects = ProjectManager.listProjects(context) }

    // 新建项目弹窗（分步向导）
    var showNewProjectDialog by remember { mutableStateOf(false) }

    // SDK 管理器面板（真功能：统一管理引擎组件 + 打包工具链）
    var showSdkManager by remember { mutableStateOf(false) }

    // 光晕 spring 入场
    val glowSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.slowSpatialSpec()
    var glowStart by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { glowStart = true }
    val glow by animateFloatAsState(
        targetValue = if (glowStart) 1f else 0f,
        animationSpec = glowSpec,
        label = "welcomeGlow",
    )

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // 背景光晕
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = glow }) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.02f),
                    radius = size.minDimension * 0.75f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(tertiary.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.22f),
                    radius = size.minDimension * 0.62f,
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            // ── 顶部工具行：品牌 + 圆形图标组（形状对比）──
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(modifier = Modifier.size(42.dp))
                Spacer(Modifier.weight(1f))
                PressScale(onClick = onOpenManual) { CircleTool(Icons.Filled.MenuBook) }
                Spacer(Modifier.width(8.dp))
                PressScale(onClick = onOpenTerminal) { CircleTool(Icons.Filled.Terminal) }
                Spacer(Modifier.width(8.dp))
                PressScale(onClick = onOpenSettings) { CircleTool(Icons.Filled.Settings) }
            }

            Spacer(Modifier.height(40.dp))

            // ── 页面标题：单一清晰的标题作为无障碍锚点，不用装饰性标语 ──
            EnterBox(delayMs = 60) {
                Text(
                    text = t("项目"),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── 快捷操作：2×2 彩色卡片网格（不同色角色，避免千篇一律）──
            EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 260) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickCard(
                            icon = Icons.Filled.LibraryAdd,
                            title = t("新建项目"),
                            tint = CardTint.PRIMARY,
                            onClick = { showNewProjectDialog = true },
                            modifier = Modifier.weight(1f),
                            delayMs = 0,
                        )
                        QuickCard(
                            icon = Icons.Filled.Folder,
                            title = t("打开"),
                            tint = CardTint.SECONDARY,
                            onClick = { projects.firstOrNull()?.let(onOpenProject) },
                            modifier = Modifier.weight(1f),
                            delayMs = 60,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickCard(
                            icon = Icons.Filled.Build,
                            title = t("SDK 管理器"),
                            tint = CardTint.TERTIARY,
                            onClick = { showSdkManager = true },
                            modifier = Modifier.weight(1f),
                            delayMs = 120,
                        )
                        QuickCard(
                            icon = Icons.Filled.Extension,
                            title = t("插件"),
                            tint = CardTint.SURFACE,
                            onClick = { messenger.show(t("插件功能尚未开放"), MessageTone.INFO) },
                            modifier = Modifier.weight(1f),
                            delayMs = 180,
                        )
                    }
                }
            }

            Spacer(Modifier.height(44.dp))

            EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 340) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(t("最近的项目"))
                    Spacer(Modifier.weight(1f))
                    PressScale {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = t("搜索"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            if (projects.isEmpty()) {
                EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 390) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.LibraryAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = { showNewProjectDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t("新建项目"))
                        }
                    }
                }
            } else {
                projects.forEachIndexed { idx, p ->
                    EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 390 + idx * 50) {
                        RecentProjectCard(
                            project = p,
                            onOpen = { onOpenProject(p) },
                            onDelete = {
                                ProjectManager.deleteProject(context, p.name)
                                refreshTick++
                                messenger.show("已删除 ${p.name}", MessageTone.SUCCESS)
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            Spacer(Modifier.height(40.dp))
        }

        // 新建项目分步向导弹窗
        if (showNewProjectDialog) {
            NewProjectDialog(
                onDismiss = { showNewProjectDialog = false },
                onCreated = { info ->
                    refreshTick++
                    onOpenProject(info)
                },
            )
        }

        // SDK 管理器面板（统一管理引擎组件与打包工具链）
        SdkManagerSheet(
            visible = showSdkManager,
            onDismiss = { showSdkManager = false },
        )
    }
}

private enum class CardTint { PRIMARY, SECONDARY, TERTIARY, SURFACE }

/** 按压弹性缩放容器。 */
@Composable
private fun PressScale(
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale = pressScale(pressed)
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val consumed = tryAwaitRelease()
                        pressed = false
                        if (consumed) onClick()
                    },
                )
            },
    ) {
        content()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CircleTool(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 快捷卡片：图标容器 + 标题，标题自解释，不堆次要描述。 */
@Composable
private fun QuickCard(
    icon: ImageVector,
    title: String,
    tint: CardTint,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayMs: Int,
) {
    val container = when (tint) {
        CardTint.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
        CardTint.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer
        CardTint.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer
        CardTint.SURFACE -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainer = when (tint) {
        CardTint.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
        CardTint.SECONDARY -> MaterialTheme.colorScheme.onSecondaryContainer
        CardTint.TERTIARY -> MaterialTheme.colorScheme.onTertiaryContainer
        CardTint.SURFACE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    EnterBox(modifier = modifier, delayMs = delayMs) {
        var pressed by remember { mutableStateOf(false) }
        val scale = pressScale(pressed)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            val consumed = tryAwaitRelease()
                            pressed = false
                            if (consumed) onClick()
                        },
                    )
                },
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(container),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RecentProjectCard(
    project: ProjectInfo,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val time = remember {
        val fmt = java.text.SimpleDateFormat(t("M月d日"), Locale.getDefault())
        try { fmt.format(Date(project.createdAt)) } catch (e: Exception) { "" }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 不同项目用 tertiary / primary 容器色区分
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    project.template,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            PressScale(onClick = onDelete) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = t("移除"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
