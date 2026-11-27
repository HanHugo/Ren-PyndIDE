package com.renpy.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.renpy.mobile.engine.ApkAssets
import com.renpy.mobile.engine.ApkBuilder
import com.renpy.mobile.installer.BuildToolchainInstaller
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.termux.TermuxBootstrap
import androidx.compose.material3.SnackbarDuration
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.MessageTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.renpy.mobile.i18n.t

/**
 * 独立打包界面（全屏，经导航栈跳转进入）。
 *
 * 相比旧的底部弹窗 BuildSheet，这里提供完整的 APK 定制能力：
 *  - 应用图标（点击选图 → 自动生成自适应图标各密度资源）
 *  - 启动图（点击选图 → presplash）
 *  - 应用名 / 包名 / 版本号 / 屏幕方向
 *  - 工具链安装引导、打包进度日志、完成后安装/分享
 *
 * 配置持久化到项目 android-build.json，下次进入自动回显。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(
    project: ProjectInfo,
    onBack: () -> Unit,
    /** 打包前确保脚本已编译为最新 rpyc；返回 true 表示可继续。 */
    onEnsureCompiled: suspend (
        onLog: (String) -> Unit,
        onProgress: (done: Int, total: Int, phase: String) -> Unit,
    ) -> Boolean,
) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { BuildToolchainInstaller.bind(context) }
    val toolState by BuildToolchainInstaller.state.collectAsState()

    // 从项目配置回显
    val saved = remember { ApkAssets.load(project.path) }
    var appName by remember { mutableStateOf(if (saved.appName.isNotBlank()) saved.appName else project.name) }
    var packageName by remember { mutableStateOf(if (saved.packageName.isNotBlank()) saved.packageName else suggestPackage(project.name)) }
    var version by remember { mutableStateOf(saved.version.ifEmpty { "1.0" }) }
    var orientation by remember { mutableStateOf(saved.orientation.ifEmpty { "sensorLandscape" }) }
    var iconPath by remember { mutableStateOf(saved.iconPath) }
    var splashPath by remember { mutableStateOf(saved.splashPath) }

    var bootstrapInstalled by remember { mutableStateOf(TermuxBootstrap.isInstalled(context)) }
    val toolReady = BuildToolchainInstaller.isInstalled()

    var building by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    val logs = remember { mutableStateListOf<String>() }
    var apkPath by remember { mutableStateOf<String?>(null) }
    // 打包成功后是否展示完成庆祝弹窗
    var showCompleteDialog by remember { mutableStateOf(false) }

    // 选图器：区分选图标 / 选启动图
    var pickingTarget by remember { mutableStateOf("") } // "icon" / "splash"
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null || pickingTarget.isEmpty()) return@rememberLauncherForActivityResult
        val target = pickingTarget
        scope.launch {
            val path = ApkAssets.importImage(context, project.path, uri, target)
            if (target == "icon") iconPath = path else splashPath = path
            // 立即持久化，避免选了图还没保存就退出
            ApkAssets.save(project.path, ApkAssets.Config(appName, packageName, version, orientation, iconPath, splashPath))
            withContext(Dispatchers.Main) {
                messenger.show(if (target == "icon") t("图标已设置") else t("启动图已设置"), MessageTone.SUCCESS)
            }
        }
    }

    fun addLog(s: String) {
        logs.add(s)
        while (logs.size > 300) logs.removeAt(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("打包 APK")) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !building) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 外观：图标 + 启动图 ──
            SectionLabel(t("外观"), Modifier.padding(start = 20.dp, top = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 图标
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { pickingTarget = "icon"; pickImage.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = remember(iconPath) { if (iconPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(iconPath) }.getOrNull() else null }
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), contentDescription = t("应用图标"), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Filled.Image, contentDescription = t("选择图标"), modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t("图标"), style = MaterialTheme.typography.labelMedium)
                }
                // 启动图
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .height(88.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { pickingTarget = "splash"; pickImage.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = remember(splashPath) { if (splashPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(splashPath) }.getOrNull() else null }
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), contentDescription = t("启动图"), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Filled.Image, contentDescription = t("选择启动图"), modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t("启动图"), style = MaterialTheme.typography.labelMedium)
                }
            }

            // ── 应用信息 ──
            SectionLabel(t("应用信息"), Modifier.padding(start = 20.dp, top = 8.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = appName, onValueChange = { appName = it },
                    label = { Text(t("应用名称")) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = packageName, onValueChange = { packageName = it },
                    label = { Text(t("包名")) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = !validPackage(packageName),
                    supportingText = if (!validPackage(packageName)) ({ Text(t("需含点，且每段以字母开头，仅字母/数字/下划线")) }) else null,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = version, onValueChange = { version = it },
                    label = { Text(t("版本号")) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(14.dp))
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
            }

            Spacer(Modifier.height(8.dp))

            when {
                building -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(stage, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        LogBox(logs, title = t("构建日志"))
                        Spacer(Modifier.height(24.dp))
                    }
                }

                apkPath != null -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        // ── 完成态卡片：图标 + 应用名 + 包名/版本 + 文件名 ──
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { showCompleteDialog = true },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val bmp = remember(iconPath) {
                                        if (iconPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(iconPath) }.getOrNull() else null
                                    }
                                    if (bmp != null) {
                                        Image(bmp.asImageBitmap(), contentDescription = t("应用图标"), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                                    }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(appName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    Spacer(Modifier.height(2.dp))
                                    Text("$packageName  ·  v$version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    Spacer(Modifier.height(2.dp))
                                    Text(apkPath!!.substringAfterLast("/"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { installApk(context, apkPath!!) { msg -> messenger.show(msg, MessageTone.ERROR, duration = SnackbarDuration.Long) } }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text(t("安装"))
                            }
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = { shareApk(context, apkPath!!) { msg -> messenger.show(msg, MessageTone.ERROR, duration = SnackbarDuration.Long) } }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text(t("分享"))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        LogBox(logs, title = t("构建日志"))
                        Spacer(Modifier.height(24.dp))
                    }
                }

                !bootstrapInstalled || !toolReady -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
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
                                    if (!bootstrapInstalled) {
                                        addLog(t("安装 Termux 运行环境…"))
                                        val ok = TermuxBootstrap.install(context)
                                        bootstrapInstalled = TermuxBootstrap.isInstalled(context)
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
                        if (toolState.logTail.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            LogBox(listOf(toolState.logTail), title = t("安装日志"))
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }

                else -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Button(
                            onClick = {
                                if (!validPackage(packageName)) {
                                    messenger.show(t("包名格式不正确"), MessageTone.ERROR)
                                    return@Button
                                }
                                // 保存配置
                                ApkAssets.save(project.path, ApkAssets.Config(appName.trim(), packageName.trim(), version.trim().ifEmpty { "1.0" }, orientation, iconPath, splashPath))
                                building = true
                                logs.clear(); apkPath = null
                                stage = t("准备中…"); progress = 0f
                                scope.launch {
                                    addLog(t("检查并编译最新脚本…"))
                                    val compiled = onEnsureCompiled(
                                        { msg -> scope.launch(Dispatchers.Main) { addLog(msg) } },
                                        { done, total, phase ->
                                            scope.launch(Dispatchers.Main) {
                                                stage = "编译：$phase"
                                                progress = if (total > 0) done.toFloat() / total else progress
                                            }
                                        },
                                    )
                                    if (!compiled) {
                                        addLog(t("编译失败，已中止打包"))
                                        building = false
                                        withContext(Dispatchers.Main) { messenger.show(t("编译失败，无法打包"), MessageTone.ERROR) }
                                        return@launch
                                    }
                                    val cfg = ApkBuilder.BuildConfig(
                                        packageName = packageName.trim(),
                                        appName = appName.trim(),
                                        version = version.trim().ifEmpty { "1.0" },
                                        orientation = orientation,
                                        iconName = appName.trim(),
                                        iconPath = iconPath,
                                        splashPath = splashPath,
                                    )
                                    val result = ApkBuilder.build(
                                        context = context, project = project, config = cfg,
                                        onLog = { s -> addLog(s) },
                                        onProgress = { st, pct -> stage = st.label; progress = pct / 100f },
                                    )
                                    building = false
                                    if (result.success) {
                                        apkPath = result.apkPath
                                        showCompleteDialog = true
                                    }
                                    withContext(Dispatchers.Main) {
                                        messenger.show(
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
        }
    }

    // ── 打包完成庆祝弹窗（MD3 Expressive）──
    if (showCompleteDialog && apkPath != null) {
        BuildCompleteDialog(
            appName = appName,
            packageName = packageName,
            version = version,
            iconPath = iconPath,
            apkPath = apkPath!!,
            onInstall = { installApk(context, apkPath!!) { msg -> messenger.show(msg, MessageTone.ERROR, duration = SnackbarDuration.Long) } },
            onShare = { shareApk(context, apkPath!!) { msg -> messenger.show(msg, MessageTone.ERROR, duration = SnackbarDuration.Long) } },
            onDismiss = { showCompleteDialog = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

private fun apkUri(context: Context, path: String): android.net.Uri =
    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(path))

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
