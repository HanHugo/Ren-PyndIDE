package com.renpy.mobile.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.renpy.mobile.engine.EngineBridge
import com.renpy.mobile.model.CodeTab
import com.renpy.mobile.model.FileKind
import com.renpy.mobile.model.FileNode
import com.renpy.mobile.model.LogEntry
import com.renpy.mobile.model.LogLevel
import com.renpy.mobile.model.kindOf
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.project.ProjectManager
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.MessageTone
import com.renpy.mobile.ui.theme.expressiveEffectsSpec
import com.renpy.mobile.ui.theme.reduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.renpy.mobile.i18n.t

/**
 * 编辑器主界面骨架（Expressive）+ 真实文件系统。
 * - 项目树来自 [ProjectManager.projectTree]，点文件真实读取内容；
 * - 标签页跟踪真实文件，编辑实时改内存，切换/保存/退出时写回磁盘；
 * - 布局：智能适配(AUTO) 宽屏双栏、窄屏抽屉；始终抽屉(DRAWER) 全尺寸抽屉。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun EditorScreen(
    project: ProjectInfo,
    darkTheme: Boolean,
    editorLayout: EditorLayout = EditorLayout.AUTO,
    onBack: () -> Unit = {},
    onOpenManual: () -> Unit = {},
    onNavigateToBuild: () -> Unit = {},
    onOpenAiProviders: () -> Unit = {},
    onOpenAiPreferences: () -> Unit = {},
    // 侧栏当前 pane 由外层持有：从 AI 面板跳转到 AI 偏好/服务商再返回时，
    // EditorScreen 会被 AnimatedContent 销毁重建，若状态存在这里会退回文件树。
    sidePane: Int = 0,
    onSidePaneChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val reduced = reduceMotion(context)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showQuickTools by remember { mutableStateOf(false) }
    var showLayoutAssistant by remember { mutableStateOf(false) }
    var compiling by remember { mutableStateOf(false) }
    // 编译进度：(已编译数, 总数, 当前脚本, 阶段)
    var compileDone by remember { mutableIntStateOf(0) }
    var compileTotal by remember { mutableIntStateOf(0) }
    var compileFile by remember { mutableStateOf("") }
    var compilePhase by remember { mutableStateOf("") }
    // 字号由编辑器层持有，工具栏菜单与捏合手势共享
    var editorFontSize by remember { mutableFloatStateOf(EDITOR_DEFAULT_FONT_SP) }

    // ── 真实文件系统状态 ──
    var treeVersion by remember { mutableIntStateOf(0) }
    val tree = remember(project.path, treeVersion) { ProjectManager.projectTree(project) }
    val tabs = remember { mutableStateListOf<CodeTab>() }
    val logs = remember { mutableStateListOf<LogEntry>() }
    // 分块加载：每个标签独立追踪"是否正在加载下一块"，按 path 记录
    val loadingChunks = remember { mutableStateListOf<String>() }

    fun nowTime() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    fun addLog(level: LogLevel, msg: String) {
        logs.add(LogEntry(level, nowTime(), msg))
    }

    // 按文件类型构造标签：图片/音频不读文本；文本只加载第一块，其余按需加载
    fun makeTab(node: FileNode): CodeTab {
        val kind = kindOf(node.name)
        if (kind != FileKind.TEXT && kind != FileKind.UNKNOWN) {
            return CodeTab(node.name, "", node.path, kind = kind)
        }
        return try {
            val total = ProjectManager.countLines(node.path)
            if (total <= EDITOR_CHUNK_LINES) {
                // 小文件：一次性全文加载，走普通模式
                CodeTab(node.name, ProjectManager.readFile(node.path), node.path, kind = kind)
            } else {
                // 大文件：只加载第一块，剩余滚动时再读
                val head = ProjectManager.readLines(node.path, 1, EDITOR_CHUNK_LINES)
                CodeTab(
                    node.name, head, node.path, kind = kind,
                    totalLines = total, loadedLines = EDITOR_CHUNK_LINES,
                )
            }
        } catch (e: Exception) {
            CodeTab(node.name, "", node.path, kind = kind)
        }
    }

    // 追加下一块到标签。读取在 IO 线程，写回 tabs 在主线程。
    fun loadMore(tab: CodeTab) {
        if (!tab.hasMoreLines || tab.path.isEmpty()) return
        if (tab.path in loadingChunks) return
        loadingChunks.add(tab.path)
        scope.launch {
            val nextCount = minOf(EDITOR_CHUNK_LINES, tab.totalLines - tab.loadedLines)
            val result = withContext(Dispatchers.IO) {
                runCatching { ProjectManager.readLines(tab.path, tab.loadedLines + 1, nextCount) }
            }
            val chunk = result.getOrNull()
            if (!chunk.isNullOrEmpty()) {
                val idx = tabs.indexOfFirst { it.path == tab.path }
                if (idx >= 0) {
                    val cur = tabs[idx]
                    tabs[idx] = cur.copy(
                        content = cur.content + chunk,
                        loadedLines = cur.loadedLines + nextCount,
                    )
                }
            } else if (chunk == null) {
                addLog(LogLevel.ERROR, "加载文件失败：${result.exceptionOrNull()?.message}")
            }
            loadingChunks.remove(tab.path)
        }
    }

    // 首次进入：自动打开 game/script.rpy（若存在）
    remember(project.path) {
        if (tabs.isEmpty()) {
            val first = findFile(tree, "script.rpy")
                ?: flattenFiles(tree).firstOrNull { kindOf(it.name) == FileKind.TEXT }
            if (first != null) {
                tabs.add(makeTab(first))
                addLog(LogLevel.INFO, "已打开 ${first.name}")
            }
        }
    }

    // 打开文件（去重）：已在标签中则切换，否则按类型读取加入
    fun openFile(node: FileNode) {
        if (node.path.isEmpty()) return
        val idx = tabs.indexOfFirst { it.path == node.path }
        if (idx >= 0) {
            selectedTab = idx
        } else {
            tabs.add(makeTab(node))
            selectedTab = tabs.lastIndex
            addLog(LogLevel.INFO, "已打开 ${node.name}")
        }
    }

    // 保存单个标签，返回是否真正写入。
    // 分块模式下只加载了前 loadedLines 行：直接写回会截断未加载部分，
    // 因此保存时从磁盘补上剩余的行，再整体写回，避免数据丢失。
    fun saveTab(tab: CodeTab): Boolean {
        if (tab.path.isEmpty() || !tab.dirty) return false
        return try {
            var full = tab.content
            if (tab.hasMoreLines) {
                val tail = ProjectManager.readLines(
                    tab.path, tab.loadedLines + 1, tab.totalLines - tab.loadedLines,
                )
                full += tail
            }
            ProjectManager.writeFile(tab.path, full)
            val i = tabs.indexOfFirst { it.path == tab.path }
            if (i >= 0) tabs[i] = tabs[i].copy(dirty = false)
            addLog(LogLevel.SUCCESS, "已保存 ${tab.fileName}")
            true
        } catch (e: Exception) {
            addLog(LogLevel.ERROR, "保存失败：${e.message}")
            false
        }
    }

    // 保存全部
    fun saveAll() {
        tabs.filter { it.dirty }.forEach { saveTab(it) }
    }

    // ── 布局计算 ──
    val activity = LocalContext.current as Activity
    val windowSize = calculateWindowSizeClass(activity)
    val useDualPane = editorLayout == EditorLayout.AUTO &&
        windowSize.widthSizeClass != WindowWidthSizeClass.Compact
    // 侧栏宽度：文件树占 0.72，AI 助手占 0.94（对话/表格需要更大空间）。
    // 关键：宽度不做逐帧动画。宽度变化会让 AI 消息 LazyColumn 每一帧重新测量所有
    // Markdown span，这是切换卡顿的主因之一。改为离散切换，过渡感由 pane 的
    // alpha/位移 180ms 动画承担，视觉依旧顺滑但零重测量抖动。
    val drawerRatio = if (sidePane == 1) 0.94f else 0.72f
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val drawerWidth = (screenWidthDp * drawerRatio).dp
    // 不可见 pane 整体平移到屏幕外：这样它既保留在组合中（状态/测量不丢，
    // 切回不卡顿），又完全不参与命中测试——不会拦截可见 pane 的点击，
    // 也不会出现「点击穿透到下层 pane」的问题。graphicsLayer 的平移会连续
    // 跟随 aiProgress，切换动画平滑；同时该平移也反映到命中测试（移出即不拦截）。
    val offscreenPx = with(androidx.compose.ui.platform.LocalDensity.current) { screenWidthDp.dp.toPx() }
    // 双栏模式下侧栏也随 pane 变化：AI 助手给更宽的固定宽度。
    val dualPaneWidth = if (sidePane == 1) 420.dp else 280.dp

    val toggleDrawer: () -> Unit = {
        scope.launch {
            if (drawerState.isOpen) drawerState.close() else drawerState.open()
        }
    }

    BackHandler(enabled = true) {
        showExitDialog = !showExitDialog
    }

    if (showExitDialog) {
        ExitProjectDialog(
            onDismiss = { showExitDialog = false },
            onSaveExit = {
                saveAll()
                showExitDialog = false
                onBack()
            },
            projectName = project.name,
        )
    }

    val currentTab = tabs.getOrNull(selectedTab)

    // 侧边面板：顶部内容随底部导航切换（文件树 / AI 助手），底部为 M3 NavigationBar。
    // 两个页面同属「项目工作台」上下文，是同级视图，符合 M3 对 NavigationBar 的定位。
    val treeContent: @Composable () -> Unit = {
        // 应用启用了 edge-to-edge，内容会绘制到状态栏下面；侧栏必须自己避开状态栏，
        // 否则顶部标题（含 AI 助手标题栏）会和状态栏时间/电量重叠。
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 文件树 / AI 助手切换：两个 pane 常驻组合，仅用 alpha + 位移动画切换显隐。
            // 关键：绝不能用 Crossfade/Events 销毁重建 AiAssistantPanel——那会让它所有
            // remember 状态丢失，每次切换都重新从磁盘加载历史 + 重新解析上千个 Markdown span，
            // 这正是「反复切 AI 助手卡顿」的根因。常驻后切换零重建、零重解析。
            val aiProgress by animateFloatAsState(
                targetValue = if (sidePane == 1) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 180),
                label = "aiPaneProgress",
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // ── 文件树 pane（progress=0 可见）──
                // 不可见时整体移出屏幕（offset），彻底退出命中测试。既保留组合状态
                // （切回不重建、LazyColumn 不重测量），又不会拦截/穿透可见 pane 的点击。
                Column(modifier = Modifier.fillMaxSize()
                    .zIndex(if (sidePane == 0) 1f else 0f)
                    .graphicsLayer {
                        alpha = 1f - aiProgress
                        translationX = -aiProgress * offscreenPx
                    }) {
                    Text(
                        text = t("项目文件"),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    )
                    HorizontalDivider()
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        ProjectTree(
                            project = project,
                            nodes = tree,
                            selectedPath = currentTab?.path ?: "",
                            onFileClick = { openFile(it) },
                            onTreeChanged = { treeVersion++ },
                        )
                    }
                }
                // ── AI 助手 pane（progress=1 可见）——常驻，状态不丢失 ──
                Box(modifier = Modifier.fillMaxSize()
                    .zIndex(if (sidePane == 1) 1f else 0f)
                    .graphicsLayer {
                        alpha = aiProgress
                        translationX = (1f - aiProgress) * offscreenPx
                    }) {
                    AiAssistantPanel(
                        project = project,
                        modifier = Modifier.fillMaxSize(),
                        onOpenSettings = onOpenAiProviders,
                        onOpenPreferences = onOpenAiPreferences,
                    )
                }
            }

            // 底部导航：文件树 / AI 助手
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = sidePane == 0,
                    onClick = { onSidePaneChange(0) },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text(t("文件树")) },
                    alwaysShowLabel = true,
                )
                NavigationBarItem(
                    selected = sidePane == 1,
                    onClick = { onSidePaneChange(1) },
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                    label = { Text(t("AI 助手")) },
                    alwaysShowLabel = true,
                )
            }
        }
    }

    // 编辑区
    val editorContent: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                AppToolbar(
                    drawerOpen = drawerState.isOpen,
                    onToggleDrawer = if (useDualPane) { {} } else toggleDrawer,
                    quickToolsOpen = showQuickTools,
                    onToggleQuickTools = { showQuickTools = !showQuickTools },
                    onToggleLayoutAssistant = {
                        // 打开布局助手前先保存当前文件，确保解析器读到最新脚本
                        saveAll()
                        showLayoutAssistant = true
                        addLog(LogLevel.INFO, t("布局助手已打开"))
                    },
                    onOpenManual = onOpenManual,
                    onZoomIn = { editorFontSize = (editorFontSize + 1f).coerceAtMost(EDITOR_MAX_FONT_SP) },
                    onZoomOut = { editorFontSize = (editorFontSize - 1f).coerceAtLeast(EDITOR_MIN_FONT_SP) },
                    onResetFont = { editorFontSize = EDITOR_DEFAULT_FONT_SP },
                    onSave = {
                        val tab = currentTab
                        when {
                            tab == null ->
                                messenger.show(t("没有打开的文件"), MessageTone.WARNING)
                            !tab.dirty ->
                                messenger.show("「${tab.fileName}」没有未保存的更改", MessageTone.INFO)
                            saveTab(tab) ->
                                messenger.show("已保存 ${tab.fileName}", MessageTone.SUCCESS)
                            else ->
                                messenger.show(t("保存失败，请查看日志"), MessageTone.ERROR)
                        }
                    },
                    compiling = compiling,
                    onCancelCompile = {
                        if (!compiling) return@AppToolbar
                        addLog(LogLevel.WARNING, t("正在取消编译…"))
                        com.renpy.mobile.engine.CompileService.cancel(context)
                        messenger.show(t("已请求取消编译"), MessageTone.INFO)
                    },
                    onBuild = {
                        // 进入打包界面前先保存，避免打包到旧脚本
                        saveAll()
                        onNavigateToBuild()
                    },
                    building = false,
                    onRun = {
                        if (compiling) return@AppToolbar
                        compiling = true
                        // 重置进度显示
                        compileDone = 0
                        compileTotal = 0
                        compileFile = ""
                        compilePhase = t("准备编译环境")
                        // 清空上次日志：否则上轮的错误会被计入本次，
                        // 导致编译成功后胶囊仍显示"构建失败"（或反之）。
                        logs.clear()
                        // 编译前先保存全部脏文件，保证编译器读到的是最新脚本
                        saveAll()
                        scope.launch {
                            // 唯一编译路径：内置 librenpython.so 引擎（无 Termux / pkg / hook / root）
                            val runCompile = suspend {
                                EngineBridge.compile(
                                    context, project,
                                    onLog = { level, msg ->
                                        scope.launch(Dispatchers.Main) { addLog(level, msg) }
                                    },
                                    onProgress = { done, total, file, phase ->
                                        scope.launch(Dispatchers.Main) {
                                            compileDone = done
                                            compileTotal = total
                                            compileFile = file
                                            compilePhase = phase
                                        }
                                    },
                                )
                            }
                            var result = runCompile()
                            // 偶发 native SIGSEGV 重试一次（子进程崩溃后状态干净，重跑通常能过）。
                            // 只重试一次，避免确定性崩溃掩盖真实错误、让等待翻倍。
                            if (!result.success && result.message.contains("NATIVE_CRASH")) {
                                addLog(LogLevel.WARNING, t("引擎 native 崩溃，自动重试一次…"))
                                result = runCompile()
                            }
                            messenger.show(
                                result.message,
                                if (result.success) MessageTone.SUCCESS else MessageTone.ERROR,
                            )
                            compiling = false
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                TabBar(
                    tabs = tabs,
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                    onClose = { idx ->
                        if (idx in tabs.indices) {
                            saveTab(tabs[idx])
                            tabs.removeAt(idx)
                            selectedTab = (selectedTab.coerceAtMost(tabs.lastIndex)).coerceAtLeast(0)
                        }
                    },
                )
                HorizontalDivider()

                // 上下分区：编辑器占满剩余空间，构建状态栏在底部独立占位。
                // 旧的 Box 叠加方案里 CodeEditor 用 fillMaxSize() 铺满整个 Box，
                // 底部胶囊/展开抽屉必然压在代码最后几行上。改成 Column 后两者互不重叠。
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        if (currentTab != null) {
                            when (currentTab.kind) {
                                FileKind.IMAGE -> ImageViewer(
                                    path = currentTab.path,
                                    fileName = currentTab.fileName,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                FileKind.AUDIO -> AudioViewer(
                                    path = currentTab.path,
                                    fileName = currentTab.fileName,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                else -> CodeEditor(
                                    code = currentTab.content,
                                    onCodeChange = { newText ->
                                        tabs[selectedTab] = currentTab.copy(content = newText, dirty = true)
                                    },
                                    darkTheme = darkTheme,
                                    fontSizeSp = editorFontSize,
                                    onFontSizeChange = { editorFontSize = it },
                                    modifier = Modifier.fillMaxSize(),
                                    totalLines = currentTab.totalLines,
                                    loadedLines = currentTab.loadedLines,
                                    loadingMore = currentTab.path in loadingChunks,
                                    onLoadMore = { loadMore(currentTab) },
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(t("从左侧项目树打开一个文件"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 日志面板只在代码类文件显示，媒体查看器不叠加
                    if (currentTab == null || currentTab.kind == FileKind.TEXT || currentTab.kind == FileKind.UNKNOWN) {
                        LogPanel(
                            logs = if (logs.isEmpty()) defaultLogs() else logs,
                            compiling = compiling,
                            compileDone = compileDone,
                            compileTotal = compileTotal,
                            compileFile = compileFile,
                            compilePhase = compilePhase,
                            onClear = { logs.clear() },
                            onLocate = { entry ->
                                // 跳转到错误关联的文件与行号
                                val node = FileNode(
                                    name = entry.file.substringAfterLast('/'),
                                    isDirectory = false,
                                    path = entry.file,
                                )
                                openFile(node)
                                addLog(LogLevel.INFO, "已定位到 ${node.name}:${entry.line}")
                            },
                        )
                    }
                }
            }

            // 布局助手：解析 rpy 布局并可视化编辑（ModalBottomSheet overlay）
            if (showLayoutAssistant && currentTab != null) {
                LayoutAssistantSheet(
                    project = project,
                    currentFilePath = currentTab.path,
                    onDismiss = { showLayoutAssistant = false },
                    onApplyCode = { newCode ->
                        // 布局助手回写了当前打开的文件：同步内存标签 + 标记脏
                        val idx = tabs.indexOfFirst { it.path == currentTab.path }
                        if (idx >= 0) {
                            tabs[idx] = tabs[idx].copy(content = newCode, dirty = true)
                        }
                    },
                    onToast = { msg ->
                        addLog(LogLevel.INFO, msg)
                        messenger.show(msg, MessageTone.INFO)
                    },
                )
            }

        }
    }

    // 抽屉打开时把代码区模糊并压暗，形成"焦点转移到侧边面板"的层次感（双栏模式无需模糊）。
    // 模糊/压暗属于效果属性，走 effects spring（damping 1.0 不回弹）；Reduce Motion 时 snap。
    val drawerFocus by animateFloatAsState(
        targetValue = if (!useDualPane && drawerState.isOpen) 1f else 0f,
        animationSpec = expressiveEffectsSpec(reduced),
        label = "drawerFocus",
    )
    val codeBlur = drawerFocus * 9f

    if (useDualPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.width(dualPaneWidth).fillMaxSize(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 4.dp,
            ) {
                treeContent()
            }
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                editorContent()
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                Surface(
                    modifier = Modifier.width(drawerWidth).fillMaxSize(),
                    shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 8.dp,
                ) {
                    treeContent()
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().blur(codeBlur.dp)) {
                    editorContent()
                }
                // 半透明压暗层：让代码区退到后景，抽屉更有"浮起"感。alpha 与模糊同步。
                if (drawerFocus > 0.001f) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * drawerFocus)),
                    )
                }
            }
        }
    }
}

/** 在项目树中按文件名找第一个匹配文件。 */
private fun findFile(nodes: List<FileNode>, name: String): FileNode? {
    nodes.forEach { n ->
        if (!n.isDirectory && n.name.equals(name, ignoreCase = true)) return n
        if (n.isDirectory) findFile(n.children, name)?.let { return it }
    }
    return null
}

/** 拍平所有文件节点。 */
private fun flattenFiles(nodes: List<FileNode>): List<FileNode> = buildList {
    nodes.forEach { n ->
        if (!n.isDirectory) add(n)
        else addAll(flattenFiles(n.children))
    }
}

private fun defaultLogs() = listOf(
    LogEntry(LogLevel.INFO, t("现在"), t("项目已加载，可以开始编辑")),
)
