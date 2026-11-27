package com.renpy.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.renpy.mobile.model.FileNode
import com.renpy.mobile.ui.AiPreferencesScreen
import com.renpy.mobile.ui.AiProvidersScreen
import com.renpy.mobile.ui.AppPalette
import com.renpy.mobile.ui.BuildScreen
import com.renpy.mobile.ui.EditorLayout
import com.renpy.mobile.ui.EditorScreen
import com.renpy.mobile.ui.FontSize
import com.renpy.mobile.ui.ManualScreen
import com.renpy.mobile.ui.SetupWizard
import com.renpy.mobile.ui.SettingsScreen
import com.renpy.mobile.ui.TerminalScreen
import com.renpy.mobile.ui.ThemeMode
import com.renpy.mobile.ui.WelcomeScreen
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.project.ProjectManager
import com.renpy.mobile.ui.theme.IdeSnackbarHost
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.RenPyMobileTheme
import com.renpy.mobile.ui.theme.Speed
import com.renpy.mobile.ui.theme.expressiveEffectsSpec
import com.renpy.mobile.ui.theme.expressiveSpatialSpec
import com.renpy.mobile.ui.theme.rememberSnackbarMessenger
import com.renpy.mobile.ui.theme.reduceMotion
import com.renpy.mobile.i18n.t

private enum class Stage { SETUP, WELCOME, EDITOR, SETTINGS, AI_PROVIDERS, AI_PREFERENCES, TERMINAL, MANUAL, BUILD }

private const val PREFS = "renpyndide_prefs"
private const val KEY_THEME = "theme_mode"
private const val KEY_FONT = "font_size"
private const val KEY_PALETTE = "palette"
private const val KEY_LAYOUT = "editor_layout"

class MainActivity : ComponentActivity() {
    /**
     * 终极拦截：屏蔽系统为文本选择弹出的浮动 ActionMode（复制/粘贴/全选黑条）。
     * 代码编辑器自绘了选择菜单，系统的这条浮动菜单必须彻底禁用。
     * 只拦截 TYPE_FLOATING，不影响其他类型的 ActionMode（如无障碍/搜索）。
     */
    override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? {
        return null
    }

    override fun startActionMode(
        callback: android.view.ActionMode.Callback?,
        type: Int,
    ): android.view.ActionMode? {
        if (type == android.view.ActionMode.TYPE_FLOATING) return null
        return super.startActionMode(callback, type)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 初始化各安装器（读取持久化安装状态，供引导页/主界面 SDK 管理器/设置页/打包面板使用）
        com.renpy.mobile.installer.SdkInstaller.bind(this)
        com.renpy.mobile.installer.BuildToolchainInstaller.bind(this)
        com.renpy.mobile.termux.TermuxBootstrap.bind(this)
        // i18n：在 setContent 之前初始化语言与字典，确保首帧即为目标语言
        com.renpy.mobile.i18n.Strings.init(this)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContent {
            // 个性化设置状态（从 SharedPreferences 恢复，重启后保留）
            var themeMode by remember {
                mutableStateOf(
                    ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeMode.LIGHT }
                )
            }
            var fontSize by remember {
                mutableStateOf(
                    FontSize.entries.getOrElse(prefs.getInt(KEY_FONT, 1)) { FontSize.NORMAL }
                )
            }
            var palette by remember {
                mutableStateOf(
                    AppPalette.entries.getOrElse(prefs.getInt(KEY_PALETTE, 0)) { AppPalette.INDIGO }
                )
            }
            var editorLayout by remember {
                mutableStateOf(
                    EditorLayout.entries.getOrElse(prefs.getInt(KEY_LAYOUT, 0)) { EditorLayout.AUTO }
                )
            }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            RenPyMobileTheme(darkTheme = darkTheme, fontSize = fontSize, palette = palette) {
                // 全局 Snackbar 宿主 + 消息通道：放 AnimatedContent 之外，
                // 页面转场时 snackbar 不跟随滑动；各屏幕通过 LocalMessenger 取用。
                val snackbarHostState = remember { SnackbarHostState() }
                val messenger = rememberSnackbarMessenger(snackbarHostState)
                CompositionLocalProvider(LocalMessenger provides messenger) {
                    Box(Modifier.fillMaxSize()) {
                        AppNav(
                            themeMode = themeMode,
                            onThemeModeChange = {
                                themeMode = it
                                prefs.edit().putInt(KEY_THEME, it.ordinal).apply()
                            },
                            fontSize = fontSize,
                            onFontSizeChange = {
                                fontSize = it
                                prefs.edit().putInt(KEY_FONT, it.ordinal).apply()
                            },
                            palette = palette,
                            onPaletteChange = {
                                palette = it
                                prefs.edit().putInt(KEY_PALETTE, it.ordinal).apply()
                            },
                            editorLayout = editorLayout,
                            onEditorLayoutChange = {
                                editorLayout = it
                                prefs.edit().putInt(KEY_LAYOUT, it.ordinal).apply()
                            },
                            darkTheme = darkTheme,
                        )
                        IdeSnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 导航中枢：用返回栈管理界面层级。
 * - 进入新界面 → 压栈；返回 → 弹栈回到上一级
 * - 系统返回键由 BackHandler 接管：非根界面回退，根界面（主界面）双击退出
 * - 前进 / 后退动画方向相反，符合 Expressive 的 slow spatial 规范
 */
@Composable
private fun AppNav(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    fontSize: FontSize,
    onFontSizeChange: (FontSize) -> Unit,
    palette: AppPalette,
    onPaletteChange: (AppPalette) -> Unit,
    editorLayout: EditorLayout,
    onEditorLayoutChange: (EditorLayout) -> Unit,
    darkTheme: Boolean,
) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current

    // 返回栈：初始只有引导页；当前界面 = 栈顶
    val stack = remember { mutableStateListOf(Stage.SETUP) }
    val stage = stack.last()

    // 当前打开的项目（由欢迎页选择/新建时设置，供编辑器读取真实文件）
    var currentProject by remember { mutableStateOf<ProjectInfo?>(null) }

    // 打开项目时的进度弹窗状态：projectName 非空表示正在打开
    var openingProjectName by remember { mutableStateOf<String?>(null) }
    var openProgress by remember { mutableFloatStateOf(0f) }
    var openPhase by remember { mutableStateOf("") }
    val openScope = rememberCoroutineScope()

    // 标记本次切换是前进还是后退，用于决定动画方向
    var forward by remember { mutableStateOf(true) }

    // 编辑器侧栏当前 pane（0 文件树 / 1 AI 助手）提升到导航层持有：
    // 从 AI 面板跳到 AI 偏好/服务商再返回时，EditorScreen 会被 AnimatedContent
    // 销毁重建；状态保存在这里，返回后仍能回到原来的对话界面。
    var editorSidePane by remember { mutableIntStateOf(0) }

    fun navigate(to: Stage) {
        if (to != stage) {
            forward = true
            stack.add(to)
        }
    }

    fun goBack() {
        if (stack.size > 1) {
            forward = false
            stack.removeAt(stack.lastIndex)
        }
    }

    // 打开项目：先在 IO 线程真实扫描项目树、统计源文件，分阶段推进度，
    // 完成后再切到编辑器。这样用户能看到明确的加载反馈，而不会点了没反应。
    fun openProject(project: ProjectInfo) {
        if (openingProjectName != null) return // 防止连点重复打开
        openingProjectName = project.name
        openProgress = 0f
        openPhase = t("正在扫描项目文件…")
        openScope.launch {
            // 阶段 1（0 → 0.45）：扫描目录树
            val tree = withContext(Dispatchers.IO) {
                runCatching { ProjectManager.projectTree(project) }.getOrElse { emptyList() }
            }
            openProgress = 0.45f

            // 阶段 2（0.45 → 0.9）：统计脚本文件并读取行数，预热即将打开的首个文件
            openPhase = t("正在加载脚本…")
            withContext(Dispatchers.IO) {
                fun flatten(nodes: List<FileNode>): List<FileNode> = buildList {
                    nodes.forEach { if (it.isDirectory) addAll(flatten(it.children)) else add(it) }
                }
                val files = flatten(tree)
                val scripts = files.filter { it.name.endsWith(".rpy", true) }
                scripts.forEach { runCatching { ProjectManager.countLines(it.path) } }
            }
            openProgress = 0.9f

            // 阶段 3（0.9 → 1）：准备界面
            openPhase = t("即将进入编辑器…")
            delay(120)
            openProgress = 1f

            // 记录打开时间，供首页「最近的项目」按最近打开排序
            withContext(Dispatchers.IO) {
                ProjectManager.markOpened(context, project.name)
            }
            currentProject = project.copy(lastOpenedAt = System.currentTimeMillis())
            openingProjectName = null
            navigate(Stage.EDITOR)
        }
    }

    // 双击退出：记录上次点击返回的时间戳
    var lastBackPress by remember { mutableStateOf(0L) }

    BackHandler(enabled = true) {
        when (stage) {
            Stage.SETUP -> {
                // 引导流程中不响应返回，避免中途退出
            }
            Stage.WELCOME -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000L) {
                    (context as? ComponentActivity)?.finish()
                } else {
                    lastBackPress = now
                    messenger.show(t("再按一次退出 Ren'PyndIDE"))
                }
            }
            else -> goBack()
        }
    }

    // 全屏页面切换用 slowSpatialSpec（Expressive 速度分级：全屏 = slow）；
    // 位移走 spatial、淡入淡出走 effects；系统「移除动画」时降级为 snap。
    val reduced = reduceMotion(context)
    val spec: FiniteAnimationSpec<IntOffset> =
        expressiveSpatialSpec(reduced, Speed.SLOW)
    val effectsSpec: FiniteAnimationSpec<Float> =
        expressiveEffectsSpec(reduced, Speed.DEFAULT)

    AnimatedContent(
        targetState = stage,
        transitionSpec = {
            if (forward) {
                // 前进：新页面从右入，旧页面向左出
                (slideInHorizontally(spec) { it / 6 } + fadeIn(effectsSpec)) togetherWith
                    (slideOutHorizontally(spec) { -it / 10 } + fadeOut(effectsSpec))
            } else {
                // 后退：新页面从左入，旧页面向右出
                (slideInHorizontally(spec) { -it / 6 } + fadeIn(effectsSpec)) togetherWith
                    (slideOutHorizontally(spec) { it / 10 } + fadeOut(effectsSpec))
            }
        },
        label = "stageNav",
    ) { target ->
        when (target) {
            Stage.SETUP -> SetupWizard(onEnter = { navigate(Stage.WELCOME) })
            Stage.WELCOME -> WelcomeScreen(
                onOpenProject = { project -> openProject(project) },
                onOpenSettings = { navigate(Stage.SETTINGS) },
                onOpenTerminal = { navigate(Stage.TERMINAL) },
                onOpenManual = { navigate(Stage.MANUAL) },
            )
            Stage.EDITOR -> {
                val project = currentProject
                if (project != null) {
                    EditorScreen(
                        project = project,
                        darkTheme = darkTheme,
                        editorLayout = editorLayout,
                        onBack = { goBack() },
                        onOpenManual = { navigate(Stage.MANUAL) },
                        onNavigateToBuild = { navigate(Stage.BUILD) },
                        onOpenAiProviders = { navigate(Stage.AI_PROVIDERS) },
                        onOpenAiPreferences = { navigate(Stage.AI_PREFERENCES) },
                        sidePane = editorSidePane,
                        onSidePaneChange = { editorSidePane = it },
                    )
                } else {
                    // 兜底：无项目时退回欢迎页
                    LaunchedEffect(Unit) { goBack() }
                }
            }
            Stage.SETTINGS -> SettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                fontSize = fontSize,
                onFontSizeChange = onFontSizeChange,
                palette = palette,
                onPaletteChange = onPaletteChange,
                editorLayout = editorLayout,
                onEditorLayoutChange = onEditorLayoutChange,
                onBack = { goBack() },
                onOpenAiProviders = { navigate(Stage.AI_PROVIDERS) },
                onOpenAiPreferences = { navigate(Stage.AI_PREFERENCES) },
            )
            Stage.AI_PROVIDERS -> AiProvidersScreen(onBack = { goBack() })
            Stage.AI_PREFERENCES -> AiPreferencesScreen(onBack = { goBack() })
            Stage.TERMINAL -> TerminalScreen(
                onBack = { goBack() },
            )
            Stage.MANUAL -> ManualScreen(
                darkTheme = darkTheme,
                onBack = { goBack() },
            )
            Stage.BUILD -> {
                val project = currentProject
                if (project != null) {
                    BuildScreen(
                        project = project,
                        onBack = { goBack() },
                        onEnsureCompiled = { onLog, onProgress ->
                            // 打包前把脚本编译到最新 rpyc（全局引擎调用，不依赖编辑器局部状态）
                            // 关键：把编译日志与进度回传到打包界面，否则用户只能看到
                            // "检查并编译最新脚本…"一行，编译完成/崩溃都没有反馈。
                            val runCompile = suspend {
                                com.renpy.mobile.engine.EngineBridge.compile(
                                    context, project,
                                    onLog = { level, msg -> onLog("[${level.name}] $msg") },
                                    onProgress = { done, total, _file, phase -> onProgress(done, total, phase) },
                                )
                            }
                            var r = runCompile()
                            // librenpython.so 在 arm64 上偶发 native SIGSEGV（崩溃后 :compiler 子进程
                            // 已死，状态是干净的）。这类偶发崩溃重跑一次通常就能过。只重试一次，
                            // 避免确定性崩溃（如某脚本必崩）把用户等待时间翻倍且掩盖真实错误。
                            if (!r.success && r.message.contains("NATIVE_CRASH")) {
                                onLog(t("[WARNING] 引擎 native 崩溃，自动重试一次…"))
                                r = runCompile()
                            }
                            r.success
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { goBack() }
                }
            }
        }
    }

    // 打开项目进度弹窗：不可点外部取消（加载完成后自动关闭并进入编辑器）
    openingProjectName?.let { name ->
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = t("正在打开项目"),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(
                        progress = { openProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = openPhase,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
