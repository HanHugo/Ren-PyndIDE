package com.renpy.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.renpy.mobile.engine.ApkBuilder
import com.renpy.mobile.installer.BuildToolchainInstaller
import com.renpy.mobile.installer.BuildToolchainInstaller.StepProgress
import com.renpy.mobile.installer.BuildToolchainInstaller.StepStatus
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.termux.TermuxBootstrap
import com.renpy.mobile.ui.theme.IdeSnackbarHost
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.MessageTone
import com.renpy.mobile.ui.theme.rememberSnackbarMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.renpy.mobile.i18n.t

/**
 * 打包面板：把 Ren'Py 游戏打成 Android APK。
 *
 * - 工具链未装：引导安装 Termux 环境 + 打包工具链，展示实时日志
 * - 工具链就绪：配置包名/名称/版本/方向，开始打包
 * - 打包中：分阶段进度 + 关键日志
 * - 完成：安装 / 分享 APK
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildSheet(
    project: ProjectInfo,
    visible: Boolean,
    onDismiss: () -> Unit,
    /** 确保游戏脚本已编译；返回 true 表示可以打包。由编辑器提供（先保存+编译）。 */
    onEnsureCompiled: suspend () -> Boolean,
    onBuildingChange: (Boolean) -> Unit = {},
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 绑定安装器（幂等）
    LaunchedEffect(Unit) { BuildToolchainInstaller.bind(context) }

    val toolState by BuildToolchainInstaller.state.collectAsState()
    val bootstrapInstalled = remember { mutableStateOf(TermuxBootstrap.isInstalled(context)) }
    val toolReady = BuildToolchainInstaller.isInstalled()

    // 配置表单（用项目名做默认值）
    var appName by remember { mutableStateOf(project.name) }
    var packageName by remember { mutableStateOf(suggestPackage(project.name)) }
    var version by remember { mutableStateOf("1.0") }
    var orientation by remember { mutableStateOf("sensorLandscape") }

    var building by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    val logs = remember { mutableStateListOf<String>() }
    var apkPath by remember { mutableStateOf<String?>(null) }

    fun addLog(s: String) {
        logs.add(s)
        while (logs.size > 300) logs.removeAt(0)
    }

    // 独立 Snackbar 宿主：sheet 是浮在全局 host 之上的 dialog 窗口，
    // 全局 snackbar 会被它遮住，故在 sheet content 内再放一个 host。
    // 通过 LocalMessenger 覆盖，让 sheet 内所有 show() 自动路由至此。
    val sheetHostState = remember { SnackbarHostState() }
    val sheetMessenger = rememberSnackbarMessenger(sheetHostState)

    ModalBottomSheet(
        onDismissRequest = { if (!building) onDismiss() },
        sheetState = sheetState,
    ) {
        CompositionLocalProvider(LocalMessenger provides sheetMessenger) {
        Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 标题栏
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(t("打包成 Android APK"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, enabled = !building) {
                    Icon(Icons.Filled.Close, contentDescription = t("关闭"))
                }
            }
            Text(
                t("把当前游戏打包为可安装的 APK（设备端完成，无需电脑）"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            when {
                building -> {
                    // 打包中
                    Text(stage, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    LogBox(logs, title = t("构建日志"))
                    Spacer(Modifier.height(24.dp))
                }

                apkPath != null -> {
                    // 完成
                    Text(t("✓ 打包完成"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(apkPath!!.substringAfterLast("/"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { installApk(context, apkPath!!) { msg -> sheetMessenger.show(msg, MessageTone.ERROR, duration = SnackbarDuration.Long) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(t("安装"))
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { shareApk(context, apkPath!!) { msg -> sheetMessenger.show(msg, MessageTone.ERROR, duration = SnackbarDuration.Long) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(t("分享"))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    LogBox(logs, title = t("构建日志"))
                    Spacer(Modifier.height(24.dp))
                }

                !bootstrapInstalled.value || !toolReady -> {
                    // 需要安装环境
                    Text(t("首次打包需要安装构建环境（约 500 MB）"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("将自动安装：Termux 运行环境、JDK 21、aapt2、apksigner、Python，以及 Android API 桩。请保持网络畅通和屏幕常亮。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (toolState.phase == BuildToolchainInstaller.Phase.INSTALLING) {
                        Text(toolState.stage, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        InstallSegments(toolState.steps)
                    } else if (toolState.phase == BuildToolchainInstaller.Phase.FAILED) {
                        Text(toolState.error ?: t("安装失败"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                if (!bootstrapInstalled.value) {
                                    addLog(t("安装 Termux 运行环境…"))
                                    val ok = TermuxBootstrap.install(context)
                                    bootstrapInstalled.value = TermuxBootstrap.isInstalled(context)
                                    if (!ok) { addLog(t("Termux 安装失败")); return@launch }
                                    addLog(t("Termux 环境就绪"))
                                }
                                addLog(t("安装打包工具链…"))
                                BuildToolchainInstaller.install { ok ->
                                    scope.launch(Dispatchers.Main) { addLog(if (ok) t("工具链就绪") else t("工具链安装失败，请看日志")) }
                                }
                            }
                        },
                        enabled = toolState.phase != BuildToolchainInstaller.Phase.INSTALLING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (toolState.phase == BuildToolchainInstaller.Phase.INSTALLING) t("安装中…") else t("安装构建环境"))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (toolState.logTail.isNotBlank()) {
                        LogBox(listOf(toolState.logTail), title = t("安装日志"))
                    }
                    Spacer(Modifier.height(24.dp))
                }

                else -> {
                    // 配置表单
                    OutlinedTextField(
                        value = appName, onValueChange = { appName = it },
                        label = { Text(t("应用名称")) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = packageName, onValueChange = { packageName = it },
                        label = { Text(t("包名（如 com.example.game）")) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        isError = !validPackage(packageName),
                    )
                    if (!validPackage(packageName)) {
                        Text(t("包名需含至少一个点，且只含字母/数字/下划线"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = version, onValueChange = { version = it },
                        label = { Text(t("版本号（如 1.0）")) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(t("屏幕方向"), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        listOf("sensorLandscape" to t("横屏"), "portrait" to t("竖屏"), "sensor" to t("跟随系统")).forEach { (v, label) ->
                            FilterChip(
                                selected = orientation == v,
                                onClick = { orientation = v },
                                label = { Text(label) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (!validPackage(packageName)) {
                                sheetMessenger.show(t("包名格式不正确"), MessageTone.ERROR)
                                return@Button
                            }
                            building = true
                            onBuildingChange(true)
                            logs.clear(); apkPath = null
                            stage = t("准备中…"); progress = 0f
                            scope.launch {
                                // 先确保编译
                                addLog(t("检查并编译最新脚本…"))
                                val compiled = onEnsureCompiled()
                                if (!compiled) {
                                    addLog(t("编译失败，已中止打包"))
                                    building = false; onBuildingChange(false)
                                    withContext(Dispatchers.Main) { sheetMessenger.show(t("编译失败，无法打包"), MessageTone.ERROR) }
                                    return@launch
                                }
                                val cfg = ApkBuilder.BuildConfig(
                                    packageName = packageName.trim(),
                                    appName = appName.trim(),
                                    version = version.trim().ifEmpty { "1.0" },
                                    orientation = orientation,
                                    iconName = appName.trim(),
                                )
                                val result = ApkBuilder.build(
                                    context = context, project = project, config = cfg,
                                    onLog = { s -> addLog(s) },
                                    onProgress = { st, pct ->
                                        stage = st.label
                                        progress = pct / 100f
                                    },
                                )
                                building = false; onBuildingChange(false)
                                if (result.success) apkPath = result.apkPath
                                withContext(Dispatchers.Main) {
                                    sheetMessenger.show(
                                        result.message,
                                        if (result.success) MessageTone.SUCCESS else MessageTone.ERROR,
                                        duration = SnackbarDuration.Long,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Android, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text(t("开始打包"))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        // 独立 snackbar 宿主，绘制在 sheet content 内（不被 dialog 窗口遮挡）
        IdeSnackbarHost(
            hostState = sheetHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }
        }
    }
}

/** 工具链安装分段进度：6 个组件各自的完成状态。 */
@Composable
fun InstallSegments(steps: List<StepProgress>) {
    if (steps.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        .height(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color),
                )
                Spacer(Modifier.height(4.dp))
                val tick = when (step.status) {
                    StepStatus.DONE -> "✓"
                    StepStatus.FAILED -> "✕"
                    StepStatus.RUNNING -> "›"
                    else -> ""
                }
                Text(
                    if (tick.isEmpty()) step.label else "$tick ${step.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step.status == StepStatus.PENDING)
                        MaterialTheme.colorScheme.onSurfaceVariant else color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun LogBox(lines: List<String>, title: String? = null) {
    val messenger = LocalMessenger.current
    val clipboard = LocalClipboardManager.current
    val text = lines.takeLast(300).joinToString("\n")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = {
                if (text.isBlank()) {
                    messenger.show(t("暂无日志内容"), MessageTone.WARNING)
                } else {
                    clipboard.setText(AnnotatedString(text))
                    messenger.show(t("日志已复制到剪贴板"), MessageTone.SUCCESS)
                }
            }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = t("复制日志"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun validPackage(p: String): Boolean {
    val t = p.trim()
    if (!t.contains('.') || t.startsWith('.') || t.endsWith('.') || t.contains("..")) return false
    return t.split('.').all { part -> part.isNotEmpty() && part[0].isLetter() && part.all { c -> c.isLetterOrDigit() || c == '_' } }
}

fun suggestPackage(name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "").take(20).ifEmpty { "renpygame" }
    return "org.renpy.$slug"
}

private fun apkUri(context: Context, path: String): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))

private fun installApk(context: Context, path: String, onError: (String) -> Unit) {
    try {
        val uri = apkUri(context, path)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        onError("安装失败：${e.message}")
    }
}

private fun shareApk(context: Context, path: String, onError: (String) -> Unit) {
    try {
        val uri = apkUri(context, path)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, t("分享 APK")))
    } catch (e: Exception) {
        onError("分享失败：${e.message}")
    }
}
