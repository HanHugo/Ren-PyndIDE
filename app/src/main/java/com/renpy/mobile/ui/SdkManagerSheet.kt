package com.renpy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.renpy.mobile.installer.BuildToolchainInstaller
import com.renpy.mobile.installer.BuildToolchainInstaller.StepProgress
import com.renpy.mobile.installer.BuildToolchainInstaller.StepStatus
import com.renpy.mobile.installer.SdkDownloadService
import com.renpy.mobile.installer.SdkInstaller
import com.renpy.mobile.termux.TermuxBootstrap
import kotlinx.coroutines.launch
import com.renpy.mobile.i18n.t

/**
 * 统一 SDK 管理器。
 *
 * 信息架构优先：用「引擎组件 / 打包工具」两个分组、状态徽章、操作按钮和组件清单
 * 让页面自解释，不依赖标语、提示文字或次要描述。
 *
 *  1. 引擎组件（SdkInstaller）：SDK 核心 / RAPT / 原生符号库
 *  2. 打包工具（TermuxBootstrap + BuildToolchainInstaller）：
 *     Termux 运行环境、JDK 21、aapt2、apksigner、Python、Gradle、Android API 桩
 *
 * 所有状态来自真实 StateFlow / 文件检测，安装 / 卸载都是真功能。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SdkManagerSheet(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        SdkInstaller.bind(context)
        BuildToolchainInstaller.bind(context)
        TermuxBootstrap.bind(context)
    }

    val sdkStates by SdkInstaller.states.collectAsState()
    val toolState by BuildToolchainInstaller.state.collectAsState()
    val bootInstalled by TermuxBootstrap.installed.collectAsState()
    val bootState by TermuxBootstrap.state.collectAsState()

    var pendingRemove by remember { mutableStateOf<RemoveTarget?>(null) }

    // ── 引擎组件 ──
    val engineEntries = SdkInstaller.COMPONENTS.map { c ->
        val s = sdkStates.firstOrNull { it.id == c.id }
        val phase = s?.phase ?: SdkInstaller.Phase.IDLE
        val installed = phase == SdkInstaller.Phase.INSTALLED
        val busy = phase == SdkInstaller.Phase.DOWNLOADING ||
            phase == SdkInstaller.Phase.VERIFYING ||
            phase == SdkInstaller.Phase.EXTRACTING
        val status = when (phase) {
            SdkInstaller.Phase.INSTALLED -> t("已安装")
            SdkInstaller.Phase.DOWNLOADING ->
                "下载 ${if (s != null && s.total > 0) ((s.downloaded * 100) / s.total).toInt() else 0}%"
            SdkInstaller.Phase.VERIFYING -> t("校验中")
            SdkInstaller.Phase.EXTRACTING -> t("解压中")
            SdkInstaller.Phase.FAILED -> t("失败")
            SdkInstaller.Phase.PAUSED -> t("已暂停")
            else -> t("未安装")
        }
        val progress = when (phase) {
            SdkInstaller.Phase.DOWNLOADING ->
                if (s != null && s.total > 0) s.downloaded.toFloat() / s.total else -1f
            SdkInstaller.Phase.VERIFYING, SdkInstaller.Phase.EXTRACTING -> -1f
            else -> null
        }
        Entry(
            key = c.id,
            name = c.name,
            icon = when (c.id) {
                "sdk" -> Icons.Filled.Memory
                "rapt" -> Icons.Filled.Build
                else -> Icons.Filled.Hardware
            },
            status = status,
            accent = when (phase) {
                SdkInstaller.Phase.INSTALLED -> Accent.DONE
                SdkInstaller.Phase.FAILED -> Accent.ERROR
                SdkInstaller.Phase.DOWNLOADING, SdkInstaller.Phase.VERIFYING,
                SdkInstaller.Phase.EXTRACTING -> Accent.RUN
                else -> Accent.IDLE
            },
            progress = progress,
            installed = installed,
            busy = busy,
            canAct = !busy,
            retry = phase == SdkInstaller.Phase.PAUSED || phase == SdkInstaller.Phase.FAILED,
            onAction = {
                SdkInstaller.retryFailed()
                SdkDownloadService.start(context)
            },
            onRemove = if (installed) ({ pendingRemove = RemoveTarget.Sdk(c) }) else null,
        )
    }

    // ── Termux 运行环境 ──
    val bp = bootState.phase
    val bootBusy = bp is TermuxBootstrap.Phase.Downloading ||
        bp is TermuxBootstrap.Phase.Verifying ||
        bp is TermuxBootstrap.Phase.Extracting
    val bootStatus = when (bp) {
        is TermuxBootstrap.Phase.Downloading ->
            "下载 ${if (bp.total > 0) ((bp.downloaded * 100) / bp.total).toInt() else 0}%"
        TermuxBootstrap.Phase.Verifying -> t("校验中")
        is TermuxBootstrap.Phase.Extracting -> t("解压中")
        TermuxBootstrap.Phase.Installed, TermuxBootstrap.Phase.Idle -> if (bootInstalled) t("已安装") else t("未安装")
        is TermuxBootstrap.Phase.Failed -> t("失败")
    }
    val bootProgress: Float? = when (bp) {
        is TermuxBootstrap.Phase.Downloading -> if (bp.total > 0) bp.downloaded.toFloat() / bp.total else -1f
        is TermuxBootstrap.Phase.Extracting -> if (bp.total > 0) bp.entries.toFloat() / bp.total else -1f
        TermuxBootstrap.Phase.Verifying -> -1f
        else -> null
    }
    val bootEntry = Entry(
        key = "bootstrap",
        name = t("Termux 运行环境"),
        icon = Icons.Filled.Terminal,
        status = bootStatus,
        accent = when (bp) {
            is TermuxBootstrap.Phase.Failed -> Accent.ERROR
            is TermuxBootstrap.Phase.Downloading, TermuxBootstrap.Phase.Verifying,
            is TermuxBootstrap.Phase.Extracting -> Accent.RUN
            else -> if (bootInstalled) Accent.DONE else Accent.IDLE
        },
        progress = bootProgress,
        installed = bootInstalled,
        busy = bootBusy,
        canAct = !bootBusy,
        retry = bp is TermuxBootstrap.Phase.Failed,
        onAction = { scope.launch { TermuxBootstrap.install(context) } },
        onRemove = if (bootInstalled) ({ pendingRemove = RemoveTarget.Bootstrap }) else null,
    )

    // ── 打包工具链：组件名靠布局（清单）可见，而非散文描述 ──
    val toolInstalled = toolState.phase == BuildToolchainInstaller.Phase.DONE ||
        BuildToolchainInstaller.isInstalled()
    val toolBusy = toolState.phase == BuildToolchainInstaller.Phase.INSTALLING
    val toolStatus = when (toolState.phase) {
        BuildToolchainInstaller.Phase.DONE -> t("已就绪")
        BuildToolchainInstaller.Phase.INSTALLING -> toolState.stage.ifBlank { t("安装中") }
        BuildToolchainInstaller.Phase.FAILED -> t("失败")
        BuildToolchainInstaller.Phase.NEED_BOOTSTRAP -> t("需 Termux")
        else -> if (toolInstalled) t("已就绪") else if (bootInstalled) t("未安装") else t("需 Termux")
    }
    val toolParts = listOf("JDK 21", "aapt2", "apksigner", "Python", "Gradle", "Android API")
    val toolEntry = Entry(
        key = "buildtools",
        name = t("Android 打包工具链"),
        icon = Icons.Filled.Apps,
        status = toolStatus,
        accent = when (toolState.phase) {
            BuildToolchainInstaller.Phase.DONE -> Accent.DONE
            BuildToolchainInstaller.Phase.FAILED, BuildToolchainInstaller.Phase.NEED_BOOTSTRAP -> Accent.ERROR
            BuildToolchainInstaller.Phase.INSTALLING -> Accent.RUN
            else -> if (toolInstalled) Accent.DONE else Accent.IDLE
        },
        progress = null,
        steps = toolState.steps,
        installed = toolInstalled,
        busy = toolBusy,
        canAct = bootInstalled && !toolBusy,
        retry = toolState.phase == BuildToolchainInstaller.Phase.FAILED,
        parts = toolParts.map { it to toolInstalled },
        onAction = {
            scope.launch {
                if (!TermuxBootstrap.isInstalled(context)) {
                    if (!TermuxBootstrap.install(context)) return@launch
                }
                BuildToolchainInstaller.install()
            }
        },
        onRemove = if (toolInstalled) ({ pendingRemove = RemoveTarget.Toolchain }) else null,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 标题：一个名称即足够，无需副标题标语
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(t("SDK 管理器"), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = t("关闭")) }
            }
            Spacer(Modifier.height(12.dp))

            SectionLabel(t("引擎组件"))
            Spacer(Modifier.height(8.dp))
            engineEntries.forEach { EntryRow(it) }
            Spacer(Modifier.height(16.dp))

            SectionLabel(t("打包工具"))
            Spacer(Modifier.height(8.dp))
            EntryRow(bootEntry)
            Spacer(Modifier.height(10.dp))
            EntryRow(toolEntry)
            Spacer(Modifier.height(28.dp))
        }
    }

    pendingRemove?.let { target ->
        DestructiveConfirmDialog(
            title = t("卸载该组件？"),
            message = when (target) {
                is RemoveTarget.Sdk -> "将删除「${target.c.name}」已下载的文件，重新使用需要再次下载。"
                RemoveTarget.Toolchain -> t("将删除 JDK、aapt2、Gradle 等打包工具文件，重新使用需要再次下载。")
                RemoveTarget.Bootstrap -> t("将删除 Termux 运行环境，打包工具链也会失效。")
            },
            confirmLabel = t("卸载"),
            onDismiss = { pendingRemove = null },
            onConfirm = {
                when (target) {
                    is RemoveTarget.Sdk -> SdkInstaller.uninstall(target.c)
                    RemoveTarget.Toolchain -> BuildToolchainInstaller.uninstall()
                    RemoveTarget.Bootstrap -> scope.launch { TermuxBootstrap.uninstall(context) }
                }
                pendingRemove = null
            },
        )
    }
}

private sealed class RemoveTarget {
    data class Sdk(val c: SdkInstaller.Component) : RemoveTarget()
    object Toolchain : RemoveTarget()
    object Bootstrap : RemoveTarget()
}

private enum class Accent { DONE, RUN, ERROR, IDLE }

private data class Entry(
    val key: String,
    val name: String,
    val icon: ImageVector,
    val status: String,
    val accent: Accent,
    val progress: Float?,
    val installed: Boolean,
    val busy: Boolean,
    val canAct: Boolean,
    val retry: Boolean,
    val parts: List<Pair<String, Boolean>> = emptyList(),
    val steps: List<StepProgress> = emptyList(),
    val onAction: () -> Unit,
    val onRemove: (() -> Unit)?,
)

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(e: Entry) {
    val (container, content) = when (e.accent) {
        Accent.DONE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Accent.RUN -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        Accent.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Accent.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (e.installed && e.accent == Accent.DONE) Icons.Filled.Check else e.icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    e.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Surface(shape = RoundedCornerShape(50), color = container) {
                    Text(
                        e.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = content,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                e.busy -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                e.installed && e.onRemove != null -> IconButton(onClick = e.onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "卸载 ${e.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> FilledTonalButton(onClick = e.onAction, enabled = e.canAct) {
                    Icon(
                        if (e.retry) Icons.Filled.Refresh else Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (e.retry) t("重试") else t("安装"))
                }
            }
        }
        e.progress?.let { p ->
            Spacer(Modifier.height(8.dp))
            if (p < 0f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)))
            } else {
                LinearProgressIndicator(
                    progress = { p.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                )
            }
        }
        // 分段进度条：工具链安装的 6 个组件各自状态，替代单个无限转圈
        if (e.steps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SegmentProgress(e.steps)
        }
        // 组件清单：用布局表达"这一项包含什么"，替代散文式描述
        if (e.parts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                e.parts.forEach { (name, ready) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (ready) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 分段进度条：每个组件一段，颜色随状态变化（待装灰 / 进行中蓝 / 完成绿 / 失败红）。 */
@Composable
private fun SegmentProgress(steps: List<StepProgress>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        steps.forEach { step ->
            val color = when (step.status) {
                StepStatus.PENDING -> MaterialTheme.colorScheme.surfaceContainerHighest
                StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
                StepStatus.DONE -> MaterialTheme.colorScheme.tertiary
                StepStatus.FAILED -> MaterialTheme.colorScheme.error
            }
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color),
                )
                Spacer(Modifier.height(4.dp))
                val tick = when (step.status) {
                    StepStatus.DONE -> "✓ "
                    StepStatus.FAILED -> "✕ "
                    StepStatus.RUNNING -> "› "
                    else -> ""
                }
                Text(
                    "$tick${step.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step.status == StepStatus.PENDING)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
