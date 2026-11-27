package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.renpy.mobile.project.ProjectManager
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.MessageTone
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.renpy.mobile.i18n.t
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.renpy.mobile.i18n.AppLanguage
import com.renpy.mobile.i18n.Strings
import com.renpy.mobile.i18n.TranslationPack
import com.renpy.mobile.i18n.resolveSystemLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    fontSize: FontSize,
    onFontSizeChange: (FontSize) -> Unit,
    palette: AppPalette,
    onPaletteChange: (AppPalette) -> Unit,
    editorLayout: EditorLayout,
    onEditorLayoutChange: (EditorLayout) -> Unit,
    onBack: () -> Unit,
    onOpenAiProviders: () -> Unit = {},
    onOpenAiPreferences: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // SDK 安装真实状态
    val context = androidx.compose.ui.platform.LocalContext.current
    val messenger = LocalMessenger.current
    val installer = com.renpy.mobile.installer.SdkInstaller
    val sdkStates by installer.states.collectAsState()
    val sdkVersion = installer.installedVersion()

    // 待卸载组件：点击已安装组件先弹确认，不直接删除
    var pendingUninstall by remember {
        mutableStateOf<com.renpy.mobile.installer.SdkInstaller.Component?>(null)
    }
    // 开源许可弹窗
    var showLicenses by remember { mutableStateOf(false) }

    // 协程作用域（提前声明，供更新检查等项目操作复用）
    val scope = rememberCoroutineScope()

    // ── 在线更新检查 ──
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.renpy.mobile.UpdateChecker.UpdateInfo?>(null) }
    var updateCheckMsg by remember { mutableStateOf<String?>(null) }
    val currentVersion = "0.3.7"

    // QQ 群链接
    val qqGroupUrl = "https://qun.qq.com/universal-share/share?ac=1&authKey=KsmxGLqEBrYZAzygUHZQUK3GMEqVGJ3sFqxNzIcf7pOs2iKJDhqilT9%2BfKdbJwQF&busi_data=eyJncm91cENvZGUiOiIxMTM0NTkxODk3IiwidG9rZW4iOiJockJySlZsbjNTNUUxdnZoZG94MFhlbmFUaHdqaWNGT0VpVk1UdTRKRElPSjZXTUxiUENJZWd1VmlMM3E1cnFlIiwidWluIjoiMzE5Njg3MzEwNiJ9&data=csQbFFezHEeIwp18kAk10L7Q1CNfVehZdt24BDwm-XrhsTTYqHGuVZ6tfVxhm8e4y5OwAXugOm4fXpcehUS9sw&svctype=4&tempid=h5_group_info"

    /** 触发更新检查。 */
    fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateCheckMsg = null
        scope.launch {
            val result = runCatching { com.renpy.mobile.UpdateChecker.check() }
            checkingUpdate = false
            result.onSuccess { info ->
                if (com.renpy.mobile.UpdateChecker.isNewer(info.edition, currentVersion)) {
                    updateInfo = info
                } else {
                    updateCheckMsg = "已是最新版本（${info.edition}）"
                }
            }.onFailure { e ->
                updateCheckMsg = "检查失败：${e.message ?: "网络错误"}"
            }
        }
    }

    // ── 项目存储位置：状态与切换/迁移逻辑 ──
    var projectsRootPath by remember { mutableStateOf(ProjectManager.projectsRoot(context).absolutePath) }
    var rootKindState by remember { mutableStateOf(ProjectManager.rootKind(context)) }
    var showFolderPicker by remember { mutableStateOf(false) }
    // 选好目标路径后，询问是否把已有项目迁移过去
    var pendingRootPath by remember { mutableStateOf<String?>(null) }
    var migrateWithSwitch by remember { mutableStateOf(true) }
    var migrateProgress by remember { mutableFloatStateOf(0f) }
    var migrating by remember { mutableStateOf(false) }
    // 当前根目录下的项目（用于展示数量、迁移时遍历）
    var projectList by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    LaunchedEffect(projectsRootPath, migrating) {
        projectList = withContext(Dispatchers.IO) { ProjectManager.listProjects(context) }
    }

    /** 真正应用新根目录：可选是否迁移已有项目。 */
    fun applyRoot(path: String, doMigrate: Boolean) {
        scope.launch {
            migrating = true
            migrateProgress = 0f
            val result = withContext(Dispatchers.IO) {
                val oldRoot = ProjectManager.projectsRoot(context)
                val targetRoot = File(path)
                val migratedNames = mutableListOf<String>()
                runCatching {
                    if (!targetRoot.isDirectory && !targetRoot.mkdirs())
                        throw IllegalStateException(t("无法创建目标目录"))
                    if (!ProjectManager.isWritableDir(path))
                        throw IllegalStateException(t("目标目录不可写，请确认已授予「所有文件访问」权限"))

                    if (doMigrate) {
                        val list = ProjectManager.listProjects(context)
                        list.forEachIndexed { index, p ->
                            // 已在目标位置的项目跳过
                            if (File(p.path).parentFile?.absolutePath != targetRoot.absolutePath) {
                                val moved = ProjectManager.migrateProject(context, p, targetRoot) { fraction ->
                                    migrateProgress = (index + fraction) / list.size.coerceAtLeast(1)
                                }
                                migratedNames += moved.name
                            }
                        }
                    }
                    ProjectManager.setProjectsRoot(context, path)
                    path
                }.onFailure {
                    // 迁移中途失败：把已迁到新位置的项目逐个搬回原根目录，避免数据"消失"
                    migratedNames.forEach { name ->
                        runCatching {
                            val moved = ProjectManager.projectInDir(targetRoot, name)
                            if (moved != null && oldRoot.absolutePath != targetRoot.absolutePath) {
                                ProjectManager.migrateProject(context, moved, oldRoot)
                            }
                        }
                    }
                }
            }
            result.onSuccess { applied ->
                projectsRootPath = applied
                rootKindState = ProjectManager.rootKind(context)
                messenger.show(if (doMigrate) t("位置已切换，项目已迁移") else t("项目默认位置已切换"), MessageTone.SUCCESS)
            }.onFailure { e ->
                messenger.show("切换失败，已回滚：${e.message}", MessageTone.ERROR, duration = androidx.compose.material3.SnackbarDuration.Long)
            }
            migrating = false
            migrateProgress = 0f
        }
    }

    /** 选中某个固定位置（私有/公共）：已在则提示，否则进入迁移确认。 */
    fun requestRootChange(path: String) {
        if (path == projectsRootPath) {
            messenger.show(t("当前已在此位置"), MessageTone.INFO)
        } else {
            migrateWithSwitch = true
            pendingRootPath = path
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        t("设置"),
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 语言与翻译：界面语言切换 + 自定义翻译包导入/导出 ──
            LanguageSection(delayBase = 0)

            Spacer(Modifier.height(24.dp))

            SettingSection(title = t("外观"), delayBase = 60) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        t("主题模式"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    // Pill 形状的主题选择器（Expressive 形状 + 颜色过渡 + spring）
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            val icon = when (mode) {
                                ThemeMode.SYSTEM -> Icons.Filled.Smartphone
                                ThemeMode.LIGHT -> Icons.Filled.LightMode
                                ThemeMode.DARK -> Icons.Filled.Nightlight
                            }
                            ThemePill(
                                icon = icon,
                                label = mode.label,
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                modifier = Modifier.weight(1f),
                                delayMs = index * 60,
                            )
                            if (index < ThemeMode.entries.size - 1) Spacer(Modifier.width(10.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 个性化：字体大小 + 主题配色 ──
            SettingSection(title = t("个性化"), delayBase = 140) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        t("字体大小"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        FontSize.entries.forEachIndexed { index, size ->
                            ChoicePill(
                                label = size.label,
                                selected = fontSize == size,
                                onClick = { onFontSizeChange(size) },
                                modifier = Modifier.weight(1f),
                                delayMs = index * 50,
                            )
                            if (index < FontSize.entries.size - 1) Spacer(Modifier.width(8.dp))
                        }
                    }

                    Spacer(Modifier.height(26.dp))
                    Text(
                        t("主题配色"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        AppPalette.entries.forEachIndexed { index, item ->
                            PaletteChoice(
                                palette = item,
                                selected = palette == item,
                                onClick = { onPaletteChange(item) },
                                modifier = Modifier.weight(1f),
                                delayMs = index * 50,
                            )
                            if (index < AppPalette.entries.size - 1) Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 项目存储位置：单选列表，三个位置常驻可见，可一键切回 ──
            SettingSection(title = t("项目存储"), delayBase = 180) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        t("新项目默认创建位置"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))

                    // 私有目录
                    StorageOptionItem(
                        icon = Icons.Filled.Smartphone,
                        title = t("私有目录"),
                        subtitle = ProjectManager.privateRoot(context).absolutePath,
                        selected = rootKindState == ProjectManager.RootKind.PRIVATE,
                        enabled = !migrating,
                        onClick = { requestRootChange(ProjectManager.privateRoot(context).absolutePath) },
                    )
                    // 公共目录
                    StorageOptionItem(
                        icon = Icons.Filled.MyLocation,
                        title = t("公共目录"),
                        subtitle = ProjectManager.publicRoot().absolutePath,
                        selected = rootKindState == ProjectManager.RootKind.PUBLIC,
                        enabled = !migrating,
                        onClick = { requestRootChange(ProjectManager.publicRoot().absolutePath) },
                    )
                    // 自定义文件夹（点击直接打开选择器）
                    StorageOptionItem(
                        icon = Icons.Filled.Folder,
                        title = t("自定义文件夹"),
                        subtitle = if (rootKindState == ProjectManager.RootKind.CUSTOM) projectsRootPath else t("点击选择任意文件夹"),
                        selected = rootKindState == ProjectManager.RootKind.CUSTOM,
                        enabled = !migrating,
                        onClick = { showFolderPicker = true },
                    )

                    if (projectList.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "当前位置有 ${projectList.size} 个项目，切换时可一并迁移",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }

                    if (migrating) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { migrateProgress },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(8.dp).clip(RoundedCornerShape(50)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "正在迁移项目 ${(migrateProgress * 100).toInt()}%…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingSection(title = t("环境与组件"), delayBase = 220) {
                // 编辑器布局偏好（智能双栏 / 始终抽屉）
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        t("编辑器布局"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        EditorLayout.entries.forEachIndexed { index, item ->
                            ChoicePill(
                                label = item.label,
                                selected = editorLayout == item,
                                onClick = { onEditorLayoutChange(item) },
                                modifier = Modifier.weight(1f),
                                delayMs = index * 50,
                            )
                            if (index < EditorLayout.entries.size - 1) Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        editorLayout.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // SDK 组件真实状态（来自 Ren'Py 官方站）
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t("SDK 组件"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (sdkVersion != null)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                sdkVersion?.let { "v$it 已安装" } ?: t("未安装"),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sdkVersion != null)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                installer.COMPONENTS.forEachIndexed { index, comp ->
                    val st = sdkStates.firstOrNull { it.id == comp.id }
                    val installed = st?.phase == com.renpy.mobile.installer.SdkInstaller.Phase.INSTALLED
                    val phaseLabel = when (st?.phase) {
                        com.renpy.mobile.installer.SdkInstaller.Phase.INSTALLED -> t("已安装")
                        com.renpy.mobile.installer.SdkInstaller.Phase.DOWNLOADING ->
                            "下载中 ${if (st.total > 0) ((st.downloaded * 100) / st.total).toInt() else 0}%"
                        com.renpy.mobile.installer.SdkInstaller.Phase.VERIFYING -> t("校验中")
                        com.renpy.mobile.installer.SdkInstaller.Phase.EXTRACTING -> t("解压中")
                        com.renpy.mobile.installer.SdkInstaller.Phase.FAILED -> t("失败")
                        com.renpy.mobile.installer.SdkInstaller.Phase.PAUSED -> t("已暂停")
                        else -> t("未安装")
                    }
                    SettingRow(
                        icon = when (comp.id) {
                            "sdk" -> Icons.Filled.Memory
                            "rapt" -> Icons.Filled.Build
                            else -> Icons.Filled.Extension
                        },
                        title = comp.name,
                        subtitle = comp.desc,
                        delayMs = 220 + index * 60,
                        value = phaseLabel,
                        onClick = {
                            if (installed) {
                                // 已安装：不直接删除，先弹出二次确认
                                pendingUninstall = comp
                            } else {
                                installer.retryFailed()
                                com.renpy.mobile.installer.SdkDownloadService.start(context)
                            }
                        },
                    )
                }
                SettingRow(
                    icon = Icons.Filled.DeleteSweep,
                    title = t("清除缓存"),
                    subtitle = t("清理编译产物与临时文件"),
                    delayMs = 400,
                    onClick = {},
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── AI 服务商：与其他分组一致的抽屉式分区，内含独立管理页入口 ──
            SettingSection(title = t("AI 服务商"), delayBase = 400) {
                SettingRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = t("管理 AI 服务商"),
                    subtitle = t("API 地址、密钥、模型与协议（各家 Key 独立保存）"),
                    delayMs = 401,
                    chevron = true,
                    onClick = onOpenAiProviders,
                )
                SettingRow(
                    icon = Icons.Filled.FormatSize,
                    title = t("AI 偏好"),
                    subtitle = t("字号、思考卡片透明度、思考强度（对所有服务商生效）"),
                    delayMs = 402,
                    chevron = true,
                    onClick = onOpenAiPreferences,
                )
            }

            Spacer(Modifier.height(24.dp))

            SettingSection(title = t("关于"), delayBase = 430) {
                SettingRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = t("检查更新"),
                    subtitle = if (checkingUpdate) t("正在检查…") else t("检查是否有新版本"),
                    delayMs = 430,
                    onClick = { checkUpdate() },
                )
                SettingRow(
                    icon = Icons.Filled.Group,
                    title = t("QQ 交流群"),
                    subtitle = t("加入 Ren'PyndIDE 用户群"),
                    delayMs = 431,
                    chevron = true,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(qqGroupUrl)
                                )
                            )
                        }
                    },
                )
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = t("开源许可"),
                    subtitle = t("查看本软件使用的开源组件与许可证"),
                    delayMs = 432,
                    onClick = { showLicenses = true },
                )
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "Ren'PyndIDE",
                    subtitle = t("版本 0.3.7 · 移动端 Ren'Py 开发环境"),
                    delayMs = 433,
                    onClick = {},
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 文件夹选择器
    if (showFolderPicker) {
        FolderPicker(
            initialPath = projectsRootPath,
            onDismiss = { showFolderPicker = false },
            onPick = { path ->
                showFolderPicker = false
                if (path == projectsRootPath) {
                    messenger.show(t("已在此位置"), MessageTone.INFO)
                } else {
                    migrateWithSwitch = true
                    pendingRootPath = path
                }
            },
        )
    }

    // 切换位置确认：M3 Dialog —— 标题 + 目标路径容器 + 迁移开关 + 主/次操作
    pendingRootPath?.let { target ->
        val hasProjects = projectList.isNotEmpty()
        AlertDialog(
            onDismissRequest = { pendingRootPath = null },
            icon = {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            },
            title = {
                Text(t("切换项目存储位置"), style = MaterialTheme.typography.headlineSmallEmphasized)
            },
            text = {
                Column {
                    Text(
                        t("新项目将创建到以下位置："),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 目标路径：单一语义容器，不嵌套卡片
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                target,
                                style = MaterialTheme.typography.bodyMediumEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (hasProjects) {
                        Spacer(Modifier.height(18.dp))
                        // 独立二元选择用 Switch（M3）：决定是否一并迁移
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { migrateWithSwitch = !migrateWithSwitch }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("同时迁移现有项目"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "共 ${projectList.size} 个，迁移到新位置",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = migrateWithSwitch, onCheckedChange = { migrateWithSwitch = it })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            t("关闭则仅切换默认位置，原项目将不再显示在列表中（文件不会丢失）。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    pendingRootPath = null
                    applyRoot(target, hasProjects && migrateWithSwitch)
                }) { Text(t("切换位置")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRootPath = null }) { Text(t("取消")) }
            },
        )
    }

    // 开源许可
    if (showLicenses) {
        LicenseDialog(onDismiss = { showLicenses = false })
    }

    // 更新检查提示（已是最新 / 检查失败）
    updateCheckMsg?.let { msg ->
        LaunchedEffect(msg) {
            messenger.show(msg, if (msg.contains("失败")) MessageTone.ERROR else MessageTone.SUCCESS)
            updateCheckMsg = null
        }
    }

    // 发现新版本对话框
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            currentVersion = currentVersion,
            onDismiss = { updateInfo = null },
        )
    }

    // 卸载二次确认：只有明确点「卸载」才真正删除，取消/返回都不会动已安装文件
    pendingUninstall?.let { comp ->
        DestructiveConfirmDialog(
            title = t("卸载该组件？"),
            message = "将删除「${comp.name}」已下载的文件，重新使用需要再次下载。",
            confirmLabel = t("卸载"),
            onDismiss = { pendingUninstall = null },
            onConfirm = {
                installer.uninstall(comp)
                pendingUninstall = null
            },
        )
    }
}

/**
 * 抽屉式设置分区：默认收起，点击标题栏展开/收起。
 * - 箭头旋转 + 标题栏底色变化做状态反馈
 * - 内容用纵向伸缩 + 淡入淡出，全部走 motionScheme（自动兼容 Reduce Motion）
 */
@Composable
private fun SettingSection(
    title: String,
    delayBase: Int,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    val expandSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val colorSpec: FiniteAnimationSpec<Color> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "drawerChevron",
    )
    val headerColor by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = colorSpec,
        label = "drawerHeader",
    )

    EnterBox(delayMs = delayBase) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                // 抽屉把手：点击切换展开/收起
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(headerColor)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起$title" else "展开$title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = rotation },
                    )
                }

                // 抽屉内容：默认隐藏，展开时纵向铺开
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = expandSpec) + fadeIn(animationSpec = effects),
                    exit = shrinkVertically(animationSpec = expandSpec) + fadeOut(animationSpec = effects),
                ) {
                    Column {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayMs: Int,
) {
    val effects: FiniteAnimationSpec<androidx.compose.ui.graphics.Color> =
        MaterialTheme.motionScheme.defaultEffectsSpec()
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = effects,
        label = "pillContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = effects,
        label = "pillContent",
    )
    EnterBox(modifier = modifier, delayMs = delayMs) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor)
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    delayMs: Int,
    selected: Boolean = false,
    value: String? = null,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    EnterBox(delayMs = delayMs) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val effects: FiniteAnimationSpec<androidx.compose.ui.graphics.Color> =
                MaterialTheme.motionScheme.defaultEffectsSpec()
            val iconTint by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = effects,
                label = "rowIconTint",
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (value != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.ExpandMore, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = -90f },
                )
            }
        }
    }
}

/** 通用文字药丸选择器（用于字体大小等）。 */
@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayMs: Int,
) {
    val effects: FiniteAnimationSpec<Color> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = effects,
        label = "choiceContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = effects,
        label = "choiceContent",
    )
    EnterBox(modifier = modifier, delayMs = delayMs) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = if (selected) MaterialTheme.typography.labelLargeEmphasized
                else MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

/** 配色选择器：色点 + 名称。DYNAMIC 用彩虹渐变色点。 */
@Composable
private fun PaletteChoice(
    palette: AppPalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayMs: Int,
) {
    val effects: FiniteAnimationSpec<Color> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = effects,
        label = "paletteBorder",
    )
    val dotBrush = paletteDot(palette)
    EnterBox(modifier = modifier, delayMs = delayMs) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .then(if (selected) Modifier.border(2.dp, borderColor, RoundedCornerShape(16.dp)) else Modifier)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(1.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(dotBrush),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                palette.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun paletteDot(palette: AppPalette): Brush = when (palette) {
    AppPalette.INDIGO -> Brush.linearGradient(listOf(Color(0xFF4A4AC9), Color(0xFF9C6BCF)))
    AppPalette.OCEAN -> Brush.linearGradient(listOf(Color(0xFF0B57D0), Color(0xFF4FC3F7)))
    AppPalette.FOREST -> Brush.linearGradient(listOf(Color(0xFF1B7D4F), Color(0xFF81C784)))
    AppPalette.SUNSET -> Brush.linearGradient(listOf(Color(0xFFB0410A), Color(0xFFFFB74D)))
    AppPalette.DYNAMIC -> Brush.sweepGradient(
        listOf(
            Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
            Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE53935),
        ),
    )
}

/**
 * 存储位置单选列表项（M3 radio list item）：
 * leading 图标 + 主/副文本 + trailing RadioButton，选中行用 secondaryContainer 容器色。
 * 用语义色与 RadioButton 表达选中状态，而非仅靠颜色；整行可点击、有 disabled 态。
 */
@Composable
private fun StorageOptionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val effects: FiniteAnimationSpec<Color> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = effects,
        label = "storageOptionContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = effects,
        label = "storageOptionContent",
    )
    val subColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = effects,
        label = "storageOptionSub",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = contentColor)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = selected, onClick = if (enabled) onClick else null)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 语言与翻译分区
// ─────────────────────────────────────────────────────────────

/**
 * 界面语言切换 + 自定义翻译包管理。
 * 语言切换即时生效（字典变更触发订阅 t() 的 Composable 重组）；
 * 导入的翻译包按 code 覆盖内置译文，可删除恢复。
 */
@Composable
private fun LanguageSection(delayBase: Int = 0) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()

    var showPicker by remember { mutableStateOf(false) }
    val currentLang = Strings.language
    val effectiveLang = Strings.effective
    val loadedPacks = Strings.loadedPacks
    val hasUserPack = Strings.hasUserPack(context, effectiveLang.code)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { t("无法读取所选文件") }
                    val json = stream.bufferedReader().use { it.readText() }
                    TranslationPack.fromJson(json)
                }
            }
            result.onSuccess { pack ->
                Strings.saveUserPack(context, pack).onSuccess {
                    messenger.show(t("翻译包已导入：{0}（{1} 条）", pack.name, pack.entries.size), MessageTone.SUCCESS)
                }.onFailure { e ->
                    messenger.show(t("导入失败：{0}", e.message ?: ""), MessageTone.ERROR)
                }
            }.onFailure { e ->
                messenger.show(t("翻译包格式错误：{0}", e.message ?: t("无效的 JSON")), MessageTone.ERROR)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val pack = TranslationPack(
                    code = effectiveLang.code,
                    name = effectiveLang.nativeName,
                    entries = Strings.dict.ifEmpty { mapOf("设置" to "Settings") },
                )
                context.contentResolver.openOutputStream(uri).use { out ->
                    requireNotNull(out) { t("无法写入文件") }
                    out.write(TranslationPack.toJson(pack).toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                messenger.show(t("翻译包模板已导出"), MessageTone.SUCCESS)
            }.onFailure { e ->
                messenger.show(t("导出失败：{0}", e.message ?: ""), MessageTone.ERROR)
            }
        }
    }

    SettingSection(title = t("语言与翻译"), delayBase = delayBase) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingRow(
                icon = Icons.Filled.Translate,
                title = t("界面语言"),
                subtitle = if (currentLang == AppLanguage.AUTO) {
                    t("跟随系统") + " · " + effectiveLang.nativeName
                } else currentLang.nativeName,
                delayMs = 0,
                chevron = true,
                onClick = { showPicker = true },
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { importLauncher.launch("*/*") }, modifier = Modifier.weight(1f)) {
                    Text(t("导入翻译包"))
                }
                TextButton(onClick = { exportLauncher.launch("renpyide-${effectiveLang.code}.json") }, modifier = Modifier.weight(1f)) {
                    Text(t("导出模板"))
                }
                if (hasUserPack) {
                    TextButton(onClick = {
                        Strings.deleteUserPack(context, effectiveLang.code)
                        messenger.show(t("已恢复内置翻译包"), MessageTone.SUCCESS)
                    }) { Text(t("删除用户包")) }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = t("已加载 {0} 套译文", loadedPacks.size) + if (hasUserPack) " · " + t("用户包已生效") else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(t("选择界面语言")) },
            text = {
                Column {
                    (listOf(AppLanguage.AUTO) + AppLanguage.translatable).forEach { lang ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                Strings.setLanguage(context, lang); showPicker = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = currentLang == lang, onClick = {
                                Strings.setLanguage(context, lang); showPicker = false
                            })
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (lang == AppLanguage.AUTO) {
                                    t("跟随系统") + " · " + resolveSystemLanguage().nativeName
                                } else lang.nativeName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text(t("关闭")) } },
        )
    }
}
