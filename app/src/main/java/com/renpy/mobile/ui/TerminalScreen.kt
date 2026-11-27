package com.renpy.mobile.ui

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.renpy.mobile.termux.RedrawingSessionClient
import com.renpy.mobile.termux.TerminalViewClientImpl
import com.renpy.mobile.termux.TermuxBootstrap
import com.renpy.mobile.termux.TermuxSessionManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.launch
import com.renpy.mobile.i18n.t

/** 经典终端配色。 */
private val TermBg = Color(0xFF000000)
private val TermPanel = Color(0xFF000000)
private val TermMenu = Color(0xFF141414)
private val TermGreen = Color(0xFF00FF66)
private val TermText = Color(0xFFFFFFFF)
private val TermMuted = Color(0xFF6E8F79)

/** 单个 Termux 会话封装。client 负责把 PTY 输出转发为界面重绘。 */
private class TermuxTab(val id: Int, val session: TerminalSession, val client: RedrawingSessionClient)

/**
 * 终端页：真正的 Termux 环境。
 * - 首次进入安装 bootstrap（下载/校验/解压/符号链接），进度实时显示
 * - 装好后用 Termux TerminalView（真 PTY、颜色、长按选字、Ctrl 键）
 * - 扳手菜单：分享全部内容 / 新会话 / 切换会话
 * - 多会话各自独立；离开页面销毁全部进程
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by TermuxBootstrap.installed.collectAsState()
    val bsState by TermuxBootstrap.state.collectAsState()
    val installing by TermuxBootstrap.installing.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    val manager = remember { TermuxSessionManager(context) }

    // 会话列表（安装好后才创建）
    val tabs = remember { mutableStateListOf<TermuxTab>() }
    var currentIndex by remember { mutableIntStateOf(0) }
    // 触发 AndroidView 重建的版本号（切换/新建会话时 +1）
    var viewVersion by remember { mutableIntStateOf(0) }

    // 进入时刷新安装状态
    LaunchedEffect(Unit) { TermuxBootstrap.bind(context) }

    // 安装好后创建首个会话
    LaunchedEffect(installed) {
        if (installed && tabs.isEmpty()) {
            val (session, client) = createSession(manager, 1)
            tabs.add(TermuxTab(1, session, client))
            currentIndex = 0
            viewVersion++
        }
    }

    // 离开页面销毁所有会话进程
    DisposableEffect(Unit) {
        onDispose { tabs.forEach { it.session.finishIfRunning() } }
    }

    fun newSession() {
        val id = (tabs.maxOfOrNull { it.id } ?: 0) + 1
        val (session, client) = createSession(manager, id)
        tabs.add(TermuxTab(id, session, client))
        currentIndex = tabs.lastIndex
        viewVersion++
    }

    fun switchSession() {
        if (tabs.size < 2) return
        currentIndex = (currentIndex + 1) % tabs.size
        viewVersion++
    }

    fun shareAll() {
        val session = tabs.getOrNull(currentIndex)?.session ?: return
        val text = transcriptOf(session)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, t("分享终端内容")))
    }

    Column(modifier = Modifier.fillMaxSize().background(TermBg)) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        if (installed && tabs.isNotEmpty()) "Termux 终端 · 会话 ${tabs[currentIndex].id}" else t("Termux 终端"),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = TermGreen,
                    )
                    Text(
                        if (installed) "PREFIX: /data/user/0/${context.packageName}/files/usr" else t("环境未安装"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TermMuted,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"), tint = TermGreen)
                }
            },
            actions = {
                if (installed) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.Build, contentDescription = t("更多"), tint = TermGreen)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = TermMenu,
                        ) {
                            DropdownMenuItem(
                                text = { Text(t("分享终端全部内容"), color = Color.White) },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null, tint = TermGreen) },
                                onClick = { menuExpanded = false; shareAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(t("新会话"), color = Color.White) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = TermGreen) },
                                onClick = { menuExpanded = false; newSession() },
                            )
                            DropdownMenuItem(
                                text = { Text(t("切换会话"), color = Color.White) },
                                leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = TermGreen) },
                                onClick = { menuExpanded = false; switchSession() },
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = TermPanel,
                navigationIconContentColor = TermGreen,
                actionIconContentColor = TermGreen,
            ),
        )

        if (!installed) {
            // 安装界面
            BootstrapInstallPanel(
                state = bsState.phase,
                installing = installing,
                onInstall = {
                    // UI 层防重入：安装中直接忽略点击（逻辑层还有互斥锁兜底）
                    if (!installing) scope.launch { TermuxBootstrap.install(context) }
                },
            )
        } else if (tabs.isNotEmpty()) {
            // Termux 终端视图（key 绑定会话+版本，切换时重建）
            val tab = tabs[currentIndex]
            key("$viewVersion-${tab.id}") {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> buildTerminalView(ctx, tab) },
                    update = { view ->
                        tab.client.view = view
                        view.onScreenUpdated()
                    },
                )
            }
        }
    }
}

/** 创建一个 Termux 会话及配套的重绘 client。 */
private fun createSession(manager: TermuxSessionManager, id: Int): Pair<TerminalSession, RedrawingSessionClient> {
    val client = RedrawingSessionClient()
    val session = manager.createSession("会话 $id", client)
    return session to client
}

/** 构造并配置一个 Termux TerminalView。 */
private fun buildTerminalView(context: Context, tab: TermuxTab): TerminalView {
    val view = TerminalView(context, null)
    view.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    // 回填 sessionClient 的 view 引用：PTY 输出时它据此触发 onScreenUpdated() 重绘。
    tab.client.view = view
    val viewClient = TerminalViewClientImpl(context, view)
    view.setTerminalViewClient(viewClient)
    view.setTextSize(16)
    view.attachSession(tab.session)
    view.setFocusable(true)
    view.isFocusableInTouchMode = true
    view.requestFocus()
    // 视图完成布局后再拉起软键盘，确保第一次进入即可输入
    view.post { viewClient.showKeyboard() }
    return view
}

/** 通过反射拿到会话的完整终端文本（mEmulator 是包可见字段）。 */
private fun transcriptOf(session: TerminalSession): String = try {
    val f = TerminalSession::class.java.getDeclaredField("mEmulator")
    f.isAccessible = true
    val emulator = f.get(session)
    val screen = emulator.javaClass.getMethod("getScreen").invoke(emulator)
    screen.javaClass.getMethod("getTranscriptText").invoke(screen) as? String ?: ""
} catch (_: Exception) {
    t("(无法提取终端内容)")
}

/** bootstrap 安装面板：未安装时显示，含真实下载/解压进度。 */
@Composable
private fun BootstrapInstallPanel(
    state: TermuxBootstrap.Phase,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text("⚙", fontSize = 48.sp, color = TermGreen)
        Spacer(Modifier.height(16.dp))
        Text(
            t("安装 Termux 环境"),
            color = TermText,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "内置约 32MB 的 Linux 用户态，首次进入解压到私有目录，\n即可获得 bash / apt / pkg 真终端，无需联网。",
            color = TermMuted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        when (state) {
            is TermuxBootstrap.Phase.Downloading -> {
                val pct = if (state.total > 0) state.downloaded.toFloat() / state.total else 0f
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = TermGreen,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "释放内置环境 ${(pct * 100).toInt()}%  ·  ${formatSize(state.downloaded)} / ${formatSize(state.total)}",
                    color = TermText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is TermuxBootstrap.Phase.Verifying -> {
                CircularProgressIndicator(color = TermGreen)
                Spacer(Modifier.height(10.dp))
                Text(t("校验文件中…"), color = TermText, style = MaterialTheme.typography.bodySmall)
            }
            is TermuxBootstrap.Phase.Extracting -> {
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.entries.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = TermGreen,
                )
                Spacer(Modifier.height(10.dp))
                Text("解压安装中… ${state.entries}/${state.total}", color = TermText, style = MaterialTheme.typography.bodySmall)
            }
            is TermuxBootstrap.Phase.Failed -> {
                Text(
                    "安装失败：${state.reason}",
                    color = Color(0xFFFF6B6B),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onInstall,
                    enabled = !installing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TermGreen,
                        contentColor = TermBg,
                        disabledContainerColor = TermMenu,
                        disabledContentColor = TermMuted,
                    ),
                ) {
                    Text(if (installing) t("正在安装…") else t("重试"))
                }
            }
            else -> {
                Button(
                    onClick = onInstall,
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TermGreen,
                        contentColor = TermBg,
                        disabledContainerColor = TermMenu,
                        disabledContentColor = TermMuted,
                    ),
                ) {
                    Text(if (installing) t("正在安装…") else t("开始安装"), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
