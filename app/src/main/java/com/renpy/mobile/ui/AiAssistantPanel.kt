package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.renpy.mobile.ai.AgentLoop
import com.renpy.mobile.ai.AiConfig
import com.renpy.mobile.ai.AiPrefs
import com.renpy.mobile.ai.ChatHistory
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.ui.theme.reduceMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.renpy.mobile.i18n.t

/**
 * 侧边栏底部的 AI 助手面板（MD3 Expressive）。
 *
 * 功能：多会话历史 / 搜索 / 引用 / 单条删除(确认) / 文字选择复制 /
 * Markdown 渲染(字号档位+可点击链接) / 思考过程 / 流式输出 / ask_user 提问。
 * 遵循 material-design-3-ui skill：语义色角色、motionScheme、触摸目标≥44dp。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiAssistantPanel(
    project: ProjectInfo,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenPreferences: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current

    var configured by remember { mutableStateOf(AiConfig.isConfigured(context)) }
    var working by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var pendingQuestion by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var pendingAnswer by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<String>?>(null) }
    var agentRef by remember { mutableStateOf<AgentLoop?>(null) }
    var currentTurn by remember { mutableStateOf(-1) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    // ── 会话历史 ──
    val sessions = remember { mutableStateListOf<ChatHistory.Session>().apply { addAll(ChatHistory.load(context)) } }
    var activeSessionId by remember { mutableStateOf(ChatHistory.getActiveId(context)) }
    var showHistory by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatHistory.Session?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sessionDeleteTarget by remember { mutableStateOf<ChatHistory.Session?>(null) }

    // ── 搜索 / 引用 / 字号 / 删除确认 / 链接 ──
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingQuote by remember { mutableStateOf<String?>(null) }
    // 字号 / 思考透明度为全局偏好，统一从 AiPrefs 读取；返回面板时重新读取以反映偏好页改动。
    var fontSizeScale by remember { mutableStateOf(AiPrefs.getFontScale(context)) }
    var thinkCardAlpha by remember { mutableStateOf(AiPrefs.getThinkAlpha(context)) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    var linkTarget by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    // 自动跟随底部：只由「是否真的贴在底部」这一几何事实决定，不依赖手势类型。
    // 底部微调不打断、上翻才暂停、滑回底部自动恢复——全程无需任何按钮。
    var stickToBottom by remember { mutableStateOf(true) }
    // 程序化自动滚动期间置位，屏蔽滚动过程中「最后可见项暂时不是末尾」造成的误判。
    val autoScrolling = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastIndex = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || lastIndex >= info.totalItemsCount - 1
        }
    }

    fun persistCurrent() {
        val id = activeSessionId ?: return
        val session = sessions.firstOrNull { it.id == id } ?: return
        session.msgs.clear()
        messages.filter { it.kind.keepInHistory() }.forEach {
            session.msgs += ChatHistory.Msg(it.kind.toHistory(), it.text, it.quote, it.ts, it.thinkMs)
        }
        session.updatedAt = System.currentTimeMillis()
        if (session.msgs.isNotEmpty() && session.title == "新会话") {
            session.title = ChatHistory.titleFrom(
                session.msgs.firstOrNull { it.kind == ChatHistory.Kind.USER }?.text ?: ""
            )
        }
        ChatHistory.save(context, sessions)
    }

    /** 停止当前对话：真正打断流/工具，解除挂起，复位 UI。切会话前先调用以防串台。 */
    fun cancelActiveRun() {
        if (agentRef == null && !working) return
        agentRef?.cancel()
        agentRef = null
        // cancel 的 onCancelled 回调可能尚未执行到 UI 状态（或 agent 为 null），这里兜底复位。
        pendingAnswer?.complete("")
        pendingAnswer = null
        pendingQuestion = null
        working = false
    }

    fun loadSession(id: String?) {
        cancelActiveRun()
        persistCurrent()
        val target = sessions.firstOrNull { it.id == id }
        activeSessionId = target?.id
        ChatHistory.setActiveId(context, target?.id)
        messages.clear()
        currentTurn = -1
        stickToBottom = true
        if (target != null) {
            target.msgs.forEach {
                messages += ChatMessage(it.kind.fromHistory(), it.text, quote = it.quote, ts = it.ts, thinkMs = it.thinkMs)
            }
        }
    }

    fun newSession() {
        val s = ChatHistory.newSession()
        sessions.add(0, s)
        ChatHistory.save(context, sessions)
        loadSession(s.id)
        showHistory = false
    }

    // 首次加载：激活上次会话或新建
    LaunchedEffect(Unit) {
        configured = AiConfig.isConfigured(context)
        if (sessions.isEmpty()) {
            val s = ChatHistory.newSession()
            sessions.add(s)
            ChatHistory.save(context, sessions)
            loadSession(s.id)
        } else {
            val target = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.first()
            activeSessionId = target.id
            target.msgs.forEach {
                messages += ChatMessage(it.kind.fromHistory(), it.text, quote = it.quote, ts = it.ts, thinkMs = it.thinkMs)
            }
        }
    }

    // 每次回到面板时刷新配置状态
    LaunchedEffect(Unit) { configured = AiConfig.isConfigured(context) }

    // 跟随状态完全由几何位置驱动：滑离底部→暂停，滑回底部→自动恢复。
    // 用 distinctUntilChanged 去重，避免滚动过程中的重复发射频繁重组。
    LaunchedEffect(Unit) {
        snapshotFlow { atBottom }
            .distinctUntilChanged()
            .collect { bottom ->
                if (!autoScrolling.get()) stickToBottom = bottom
            }
    }
    // 内容指纹：文本长度 + 思考时长（按 0.5s 粒度）都算进去，这样思考卡片实时增长也会跟随。
    val scrollFingerprint = messages.fold(0L) { acc, m -> acc + m.text.length + m.thinkMs / 500 }
    // 新内容/思考增长时自动下滑到底部（含思考），仅当用户确实离开了底部才不跟随。
    LaunchedEffect(messages.size, scrollFingerprint) {
        if (stickToBottom && messages.isNotEmpty()) {
            autoScrolling.set(true)
            try {
                runCatching { listState.animateScrollToItem(messages.lastIndex) }
            } finally {
                autoScrolling.set(false)
            }
        }
    }

    fun ensureAgent(ownerSession: String?): AgentLoop {
        agentRef?.let { return it }
        // 捕获本 agent 所属会话 ID：事件回调据此过滤，切会话后旧 agent 的在途事件一律丢弃。
        val boundSession = ownerSession
        val a = AgentLoop(
            context = context,
            projectPath = project.path,
            onEvent = { ev ->
                // 串台防护：若当前会话已不是本 agent 所属会话，丢弃在途事件。
                if (boundSession != activeSessionId) return@AgentLoop
                when (ev) {
                    is AgentLoop.AgentEvent.TurnBegin -> {
                        // 新轮次：不在此处建消息，交由首个 Reasoning/TextDelta 按 turn 惰性创建。
                        // 仅记录当前轮，供需要时使用。
                        currentTurn = ev.turn
                    }
                    is AgentLoop.AgentEvent.Status -> {
                        val idx = messages.indexOfLast { it.kind == ChatKind.STATUS }
                        val item = ChatMessage(ChatKind.STATUS, ev.message)
                        if (idx >= 0) messages[idx] = item else messages.add(item)
                    }
                    is AgentLoop.AgentEvent.Reasoning -> {
                        // 按 turn 定位：每轮的思考是独立卡片，不再全部堆进同一条。
                        val idx = messages.indexOfLast {
                            it.kind == ChatKind.REASONING && it.turn == ev.turn
                        }
                        if (idx >= 0) {
                            val old = messages[idx]
                            messages[idx] = old.copy(
                                text = old.text + ev.text,
                                thinkMs = maxOf(old.thinkMs, ev.elapsedMs),
                            )
                        } else {
                            messages.add(ChatMessage(ChatKind.REASONING, ev.text, turn = ev.turn, thinkMs = ev.elapsedMs))
                        }
                    }
                    is AgentLoop.AgentEvent.TextDelta -> {
                        // 按 turn 定位：每轮正文是独立气泡；跨提问/跨轮不再 append 到一起。
                        val idx = messages.indexOfLast {
                            it.kind == ChatKind.ASSISTANT && it.turn == ev.turn
                        }
                        val duplicate = idx >= 0 && run {
                            val existing = messages[idx].text
                            existing.length >= 24 && ev.text.length >= 24 &&
                                (existing.startsWith(ev.text.take(24)) || ev.text.startsWith(existing.take(24)))
                        }
                        if (!duplicate) {
                            if (idx >= 0) messages[idx] = messages[idx].copy(text = messages[idx].text + ev.text)
                            else messages.add(ChatMessage(ChatKind.ASSISTANT, ev.text, turn = ev.turn))
                        }
                    }
                    is AgentLoop.AgentEvent.ToolStart -> {
                        if (ev.tool != AgentLoop.ASK_USER)
                            messages.add(ChatMessage(ChatKind.TOOL, "${ev.tool} ${ev.args.take(80)}"))
                    }
                    is AgentLoop.AgentEvent.ToolDone -> {
                        if (ev.tool != AgentLoop.ASK_USER)
                            messages.add(ChatMessage(ChatKind.TOOL_DONE, "${ev.tool}: ${ev.summary.take(80)}"))
                    }
                    is AgentLoop.AgentEvent.AskUser -> {
                        pendingQuestion = ev.question to ev.options
                        pendingAnswer = kotlinx.coroutines.CompletableDeferred()
                    }
                    is AgentLoop.AgentEvent.Error -> {
                        // 「已停止生成」是主动取消，不作为错误气泡展示。
                        if (ev.message != "已停止生成")
                            messages.add(ChatMessage(ChatKind.ERROR, ev.message))
                    }
                    is AgentLoop.AgentEvent.ContextCompressed -> {
                        // 用一个轻量状态条提示用户上下文已压缩（不入库）
                        val idx = messages.indexOfLast { it.kind == ChatKind.STATUS }
                        val item = ChatMessage(ChatKind.STATUS, "已压缩 ${ev.removedTurns} 轮历史以节省空间")
                        if (idx >= 0) messages[idx] = item else messages.add(item)
                    }
                    is AgentLoop.AgentEvent.Done -> {
                        val idx = messages.indexOfLast { it.kind == ChatKind.STATUS }
                        if (idx >= 0) messages.removeAt(idx)
                        // 消息级去重：若当前轮的正文与思考高度重合（中转站把同一段既塞
                        // reasoning_content 又塞 content），删除思考卡，保留正文答案。
                        // 逐字流式下片段级去重会漏判，必须在整轮结束后按整段相似度兜底。
                        dedupeReasonVsBody(currentTurn, messages)
                        val summary = if (ev.changedFiles.isNotEmpty())
                            "完成。改动文件：${ev.changedFiles.joinToString()}"
                        else ev.summary.ifBlank { t("完成") }
                        messages.add(ChatMessage(ChatKind.DONE, summary))
                    }
                }
            },
            onAskUser = { _, options ->
                val deferred = pendingAnswer
                if (deferred != null) deferred.await() else options.firstOrNull() ?: ""
            },
            onCancelled = {
                // 停止时解除挂起的 ask_user，避免协程永久等待；复位提问区。
                pendingAnswer?.complete("")
                pendingAnswer = null
                pendingQuestion = null
                working = false
            },
        )
        agentRef = a
        return a
    }

    fun send(text: String, quote: String? = null) {
        val goal = text.trim()
        if (goal.isEmpty() || working) return
        if (!AiConfig.isConfigured(context)) {
            configured = false
            onOpenSettings()
            return
        }
        focus.clearFocus()
        messages.add(ChatMessage(ChatKind.USER, goal, quote = quote))
        // 若在历史里尚无会话，建一个
        if (activeSessionId == null) {
            val s = ChatHistory.newSession()
            sessions.add(0, s)
            activeSessionId = s.id
            ChatHistory.setActiveId(context, s.id)
        }
        input = ""
        pendingQuote = null
        working = true
        // 捕获本轮会话：事件守卫用它过滤旧 agent 的在途事件；新一轮强制重建 agent 以清空内部 messages。
        val thisRunSession = activeSessionId
        agentRef = null
        scope.launch {
            val agent = ensureAgent(thisRunSession)
            agent.run(goal)
            // 仅当仍是本轮会话时才复位与落盘，避免切会话后旧协程误写。
            if (thisRunSession == activeSessionId) {
                working = false
                persistCurrent()
            }
        }
        persistCurrent()
    }

    // 搜索过滤后的消息
    val shownMessages = if (searchQuery.isBlank()) messages.toList()
    else messages.filter {
        it.text.contains(searchQuery, ignoreCase = true) ||
            (it.quote?.contains(searchQuery, ignoreCase = true) == true)
    }
    val searchMatchCount = shownMessages.size
    // 搜索结果定位：点击上一条/下一条时滚动到对应消息
    fun jumpSearch(delta: Int) {
        if (shownMessages.isEmpty()) return
        val currentId = runCatching { listState.firstVisibleItemIndex }
            .getOrDefault(0)
        val currentShown = shownMessages.getOrNull(currentId)?.id
        val idxInShown = shownMessages.indexOfFirst { it.id == currentShown }
        var next = (if (idxInShown >= 0) idxInShown else 0) + delta
        next = ((next % searchMatchCount) + searchMatchCount) % searchMatchCount
        val target = shownMessages[next]
        val realIndex = messages.indexOfFirst { it.id == target.id }
        if (realIndex >= 0) scope.launch { listState.animateScrollToItem(realIndex) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶部标题栏（标题独占一行，操作收进溢出菜单，避免窄栏里文字被挤成竖排）──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(t("AI 助手"), style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(
                        when { working -> t("正在处理…"); configured -> t("已就绪"); else -> t("未配置") },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(Icons.Filled.Search, t("搜索聊天记录"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Filled.History, t("历史会话"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, t("更多选项"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(t("AI 偏好（字号 / 思考）")) },
                            leadingIcon = { Icon(Icons.Filled.FormatSize, null) },
                            onClick = { showMoreMenu = false; onOpenPreferences() },
                        )
                        DropdownMenuItem(
                            text = { Text(t("AI 服务商设置")) },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { showMoreMenu = false; onOpenSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text(t("清空当前会话")) },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMoreMenu = false; clearAllConfirm = true },
                        )
                    }
                }
            }

            // ── 搜索栏（带结果计数与上下条定位）──
            AnimatedVisibility(visible = showSearch,
                enter = fadeIn() + slideInHorizontally { -it }, exit = fadeOut()) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(t("搜索聊天内容…")) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, t("清除搜索"))
                            }
                        },
                    )
                    if (searchQuery.isNotBlank()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End) {
                            Text(
                                if (searchMatchCount > 0) "找到 $searchMatchCount 条" else t("无匹配结果"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { jumpSearch(-1) }, enabled = searchMatchCount > 0,
                                modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowUp, t("上一条"))
                            }
                            IconButton(onClick = { jumpSearch(1) }, enabled = searchMatchCount > 0,
                                modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, t("下一条"))
                            }
                        }
                    }
                }
            }

            // ── 未配置引导 ──
            if (!configured) {
                Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("尚未配置 AI 助手"), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(t("请填写 API 地址、密钥和模型，支持 OpenAI / Claude / Gemini。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(10.dp))
                        AssistChip(onClick = onOpenSettings, label = { Text(t("去配置")) })
                    }
                }
            }

            // ── 消息列表 ──
            if (shownMessages.isEmpty() && configured) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Filled.Android, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        Spacer(Modifier.height(12.dp))
                        Text(if (searchQuery.isNotBlank()) t("没有匹配的消息") else t("让 AI 帮你写 Ren'Py"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shownMessages, key = { it.id }) { msg ->
                        val lastBodyId = messages.lastOrNull {
                            (it.kind == ChatKind.ASSISTANT || it.kind == ChatKind.REASONING) &&
                                (it.turn == currentTurn || currentTurn < 0)
                        }?.id
                        val isStreaming = working &&
                            (msg.kind == ChatKind.ASSISTANT || msg.kind == ChatKind.REASONING) &&
                            msg.id == lastBodyId
                        ChatBubble(
                            msg = msg,
                            isStreaming = isStreaming,
                            fontSizeScale = fontSizeScale,
                            thinkCardAlpha = thinkCardAlpha,
                            onQuote = { pendingQuote = it },
                            onDelete = { deleteTarget = it },
                            onCopy = { clipboard.setText(AnnotatedString(it)); },
                            onLinkClick = { linkTarget = it },
                        )
                    }
                }

                // ── 提问区：AI 请求用户在多个方案中选择 ──
                val question = pendingQuestion
                if (question != null) {
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 1.dp) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(t("需要你的选择"), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(question.first, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.height(12.dp))
                            FlowRow(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                question.second.forEachIndexed { idx, opt ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        shadowElevation = 1.dp,
                                        onClick = {
                                            pendingAnswer?.complete(opt)
                                            pendingAnswer = null
                                            pendingQuestion = null
                                        },
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier.size(22.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text("${idx + 1}", style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(opt, style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 输入区 ──
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    // 引用条
                    pendingQuote?.let { q ->
                        Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.FormatQuote, null, Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.width(6.dp))
                                Text(q, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { pendingQuote = null }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, t("取消引用"), Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp),
                            placeholder = { Text(t("描述你的需求…")) }, maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (working) {
                            FilledIconButton(onClick = { cancelActiveRun() },
                                shape = RoundedCornerShape(16.dp), modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Stop, t("停止生成"))
                            }
                        } else {
                            FilledIconButton(onClick = { send(input, pendingQuote) },
                                enabled = input.isNotBlank(), shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Send, t("发送"))
                            }
                        }
                    }
                }
            }
        }

        // ── 历史会话弹层 ──
        if (showHistory) {
            ModalBottomSheet(onDismissRequest = { showHistory = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("历史会话"), style = MaterialTheme.typography.titleLargeEmphasized,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { newSession() }) { Text(t("新建会话")) }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 按最近更新排序，活跃的排最前
                    val sortedSessions = sessions.sortedWith(
                        compareByDescending<ChatHistory.Session> { it.id == activeSessionId }
                            .thenByDescending { it.updatedAt }
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(sortedSessions, key = { it.id }) { s ->
                            val isActive = s.id == activeSessionId
                            val preview = s.msgs.lastOrNull { it.kind == ChatHistory.Kind.USER || it.kind == ChatHistory.Kind.ASSISTANT }
                                ?.text?.replace(Regex("\\s+"), " ")?.take(40)
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                onClick = { loadSession(s.id); showHistory = false },
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape,
                                        color = if (isActive) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.size(36.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp),
                                                tint = if (isActive) MaterialTheme.colorScheme.onPrimary
                                                       else MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(s.title, style = MaterialTheme.typography.bodyLarge,
                                                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                                        else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f))
                                            if (isActive) {
                                                Surface(shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primary) {
                                                    Text(t("当前"), style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                }
                                            }
                                        }
                                        if (!preview.isNullOrBlank()) {
                                            Text(preview, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text("${s.msgs.size} 条消息 · ${relativeTime(s.updatedAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { renameTarget = s }) {
                                        Icon(Icons.Filled.Edit, t("重命名"), Modifier.size(19.dp))
                                    }
                                    IconButton(onClick = { sessionDeleteTarget = s }) {
                                        Icon(Icons.Filled.Delete, t("删除会话"), Modifier.size(19.dp),
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 重命名弹窗 ──
        renameTarget?.let { target ->
            var text by remember(target.id) { mutableStateOf(target.title) }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text(t("重命名会话")) },
                text = {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text(t("会话名称")) })
                },
                confirmButton = {
                    TextButton(onClick = {
                        target.title = text.ifBlank { t("新会话") }
                        ChatHistory.save(context, sessions)
                        renameTarget = null
                    }) { Text(t("保存")) }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(t("取消")) } },
            )
        }

        // ── 删除会话确认 ──
        sessionDeleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { sessionDeleteTarget = null },
                icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(t("删除会话？")) },
                text = { Text("「${target.title}」及其全部聊天记录将被永久删除，无法恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        sessions.remove(target)
                        if (target.id == activeSessionId) {
                            if (sessions.isNotEmpty()) loadSession(sessions.first().id)
                            else newSession()
                        } else ChatHistory.save(context, sessions)
                        sessionDeleteTarget = null
                    }) { Text(t("删除"), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { sessionDeleteTarget = null }) { Text(t("取消")) } },
            )
        }

        // ── 删除单条消息确认 ──
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(t("删除这条消息？")) },
                text = { Text(t("删除后不可恢复。")) },
                confirmButton = {
                    TextButton(onClick = {
                        messages.remove(target)
                        persistCurrent()
                        deleteTarget = null
                    }) { Text(t("删除")) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(t("取消")) } },
            )
        }

        // ── 清空当前会话确认 ──
        if (clearAllConfirm) {
            AlertDialog(
                onDismissRequest = { clearAllConfirm = false },
                title = { Text(t("清空当前会话？")) },
                text = { Text(t("将删除当前会话里的所有聊天记录，不可恢复。")) },
                confirmButton = {
                    TextButton(onClick = {
                        cancelActiveRun()
                        messages.clear()
                        persistCurrent()
                        clearAllConfirm = false
                    }) { Text(t("清空")) }
                },
                dismissButton = { TextButton(onClick = { clearAllConfirm = false }) { Text(t("取消")) } },
            )
        }

        // ── 链接点击确认 ──
        linkTarget?.let { url ->
            AlertDialog(
                onDismissRequest = { linkTarget = null },
                title = { Text(t("打开链接")) },
                text = { Text(url) },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url))
                            context.startActivity(intent)
                        }
                        linkTarget = null
                    }) { Text(t("打开")) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(url))
                        linkTarget = null
                    }) { Text(t("复制")) }
                },
            )
        }
    }
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 日期。 */
/** 思考卡时间显示：HH:mm:ss。 */
private fun formatClock(ts: Long): String {
    if (ts <= 0) return ""
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
}

/** 把毫秒格式化为简短时长：<1s 显示"0.x秒"，<60s 显示"x.x秒"，否则"x分x秒"。 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return t("0秒")
    if (ms < 1000) return t("%.1f秒").format(ms / 1000f)
    if (ms < 60_000) return t("%.0f秒").format(ms / 1000f)
    val min = ms / 60_000
    val sec = (ms % 60_000) / 1000
    return "${min}分${sec}秒"
}

private fun relativeTime(ts: Long): String {
    if (ts <= 0) return ""
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60_000
    val hour = diff / 3_600_000
    val day = diff / 86_400_000
    return when {
        min < 1 -> t("刚刚")
        min < 60 -> "$min 分钟前"
        hour < 24 -> "$hour 小时前"
        day == 1L -> t("昨天")
        day < 7 -> "$day 天前"
        else -> java.text.SimpleDateFormat(t("M月d日"), java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}

/**
 * 一轮结束后的消息级去重：某些中转站把同一段正文同时塞进 reasoning_content 与
 * content，导致「思考卡(蓝框)」和「正文气泡」内容重复。逐字流式的片段级去重会漏判，
 * 这里按整段重合度兜底：若本轮正文与思考高度相似，删除思考卡（正文是答案，优先保留）。
 * 只处理当前轮、且两边都有实质内容时，避免误伤正常的推理-回答配对。
 */
private fun dedupeReasonVsBody(turn: Int, messages: MutableList<ChatMessage>) {
    if (turn < 0) return
    val body = messages.lastOrNull { it.kind == ChatKind.ASSISTANT && it.turn == turn } ?: return
    val reason = messages.lastOrNull { it.kind == ChatKind.REASONING && it.turn == turn } ?: return
    val b = body.text.trim()
    val r = reason.text.trim()
    if (b.length < 24 || r.length < 24) return
    if (highOverlap(b, r)) {
        messages.removeAll { it.kind == ChatKind.REASONING && it.turn == turn }
    }
}

/** 判断两段中文文本是否高度重合：按行/句切分，统计短文本中出现在长文本里的比例。 */
private fun highOverlap(a: String, b: String): Boolean {
    val (short, long) = if (a.length <= b.length) a to b else b to a
    val norm = { s: String -> s.replace(Regex("\\s+"), "") }
    val s = norm(short)
    val l = norm(long)
    if (s.length < 24) return false
    // 整段包含
    if (l.contains(s)) return true
    // 按 12 字切片统计命中率
    val step = 12
    var total = 0
    var hit = 0
    var i = 0
    while (i + step <= s.length) {
        total++
        if (l.contains(s.substring(i, i + step))) hit++
        i += step
    }
    return total > 0 && hit.toFloat() / total >= 0.7f
}

// ── 消息模型与历史映射 ──
private enum class ChatKind { USER, ASSISTANT, REASONING, TOOL, TOOL_DONE, STATUS, ERROR, DONE }

private data class ChatMessage(
    val kind: ChatKind,
    val text: String,
    val quote: String? = null,
    val id: Long = System.nanoTime() + kotlin.random.Random.nextLong(0, 1000),
    val ts: Long = System.currentTimeMillis(),
    /** 所属 LLM 轮次；同一轮的思考/正文各自聚合为一条消息，不同轮拆开。历史加载的消息为 -1。 */
    val turn: Int = -1,
    /** 思考卡片累计耗时（毫秒），随思考片段实时更新，结束后固定。仅 REASONING 使用。 */
    val thinkMs: Long = 0L,
)

private fun ChatKind.keepInHistory() = when (this) {
    ChatKind.USER, ChatKind.ASSISTANT, ChatKind.REASONING, ChatKind.TOOL,
    ChatKind.TOOL_DONE, ChatKind.ERROR -> true
    else -> false
}

private fun ChatKind.toHistory() = when (this) {
    ChatKind.USER -> ChatHistory.Kind.USER
    ChatKind.ASSISTANT -> ChatHistory.Kind.ASSISTANT
    ChatKind.REASONING -> ChatHistory.Kind.REASONING
    ChatKind.TOOL -> ChatHistory.Kind.TOOL
    ChatKind.TOOL_DONE -> ChatHistory.Kind.TOOL_DONE
    else -> ChatHistory.Kind.ERROR
}

private fun ChatHistory.Kind.fromHistory() = when (this) {
    ChatHistory.Kind.USER -> ChatKind.USER
    ChatHistory.Kind.ASSISTANT -> ChatKind.ASSISTANT
    ChatHistory.Kind.REASONING -> ChatKind.REASONING
    ChatHistory.Kind.TOOL -> ChatKind.TOOL
    ChatHistory.Kind.TOOL_DONE -> ChatKind.TOOL_DONE
    ChatHistory.Kind.ERROR -> ChatKind.ERROR
}

/** 单条消息气泡。 */
@Composable
private fun ChatBubble(
    msg: ChatMessage,
    isStreaming: Boolean = false,
    fontSizeScale: Float = 1f,
    thinkCardAlpha: Float = 0.5f,
    onQuote: (String) -> Unit = {},
    onDelete: (ChatMessage) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onLinkClick: (String) -> Unit = {},
) {
    when (msg.kind) {
        ChatKind.STATUS -> {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                ThinkingDots()
                Spacer(Modifier.width(8.dp))
                Text(msg.text, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ChatKind.REASONING -> {
            var expanded by remember { mutableStateOf(false) }
            val cardColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = thinkCardAlpha)
            val onCard = MaterialTheme.colorScheme.onTertiaryContainer
            Surface(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = cardColor,
                onClick = { expanded = !expanded }) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(15.dp), tint = onCard)
                        Spacer(Modifier.width(6.dp))
                        Text(t("思考过程"), style = MaterialTheme.typography.labelLarge, color = onCard)
                        Spacer(Modifier.width(6.dp))
                        Text(formatClock(msg.ts), style = MaterialTheme.typography.labelSmall,
                            color = onCard.copy(alpha = 0.7f))
                        // 累计思考时长：流式时实时跳动，结束后固定。
                        if (msg.thinkMs > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("· ${formatDuration(msg.thinkMs)}", style = MaterialTheme.typography.labelSmall,
                                color = onCard.copy(alpha = 0.85f))
                        }
                        Spacer(Modifier.weight(1f))
                        if (isStreaming) { StreamingCaret(); Spacer(Modifier.width(6.dp)) }
                        val rotation by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (expanded) 180f else 0f, label = "expandArrow")
                        Icon(Icons.Filled.KeyboardArrowDown, null,
                            Modifier.size(18.dp).graphicsLayer { rotationZ = rotation }, tint = onCard)
                    }
                    // 懒加载：折叠时完全不组合 MarkdownText，避免历史消息里海量思考
                    // 在打开面板/滚动时全部解析渲染（性能主因之一）。展开才解析。
                    if (expanded) {
                        AnimatedVisibility(visible = true) {
                            SelectionContainer {
                                val fadePx = 22.dp
                                Box(
                                    modifier = Modifier.padding(top = 8.dp)
                                        .heightIn(max = 320.dp)
                                        // 用绘制层叠加上下渐隐，不拦截子内容的纵向滚动与文字选择。
                                        .drawWithContent {
                                            drawContent()
                                            val fh = fadePx.toPx()
                                            val h = size.height
                                            if (h > fh * 2) {
                                                drawRect(
                                                    brush = Brush.verticalGradient(
                                                        listOf(cardColor, Color.Transparent),
                                                        startY = 0f, endY = fh,
                                                    ),
                                                    topLeft = Offset.Zero,
                                                    size = Size(size.width, fh),
                                                )
                                                drawRect(
                                                    brush = Brush.verticalGradient(
                                                        listOf(Color.Transparent, cardColor),
                                                        startY = h - fh, endY = h,
                                                    ),
                                                    topLeft = Offset(0f, h - fh),
                                                    size = Size(size.width, fh),
                                                )
                                            }
                                        }
                                ) {
                                    MarkdownText(msg.text, fontSizeScale = fontSizeScale * 0.85f,
                                        color = onCard, onLinkClick = onLinkClick,
                                        modifier = Modifier.verticalScroll(rememberScrollState()))
                                }
                            }
                        }
                    }
                }
            }
        }
        ChatKind.TOOL, ChatKind.TOOL_DONE -> {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (msg.kind == ChatKind.TOOL) "▸ " else "✓ ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold)
                        Text(msg.text, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        ChatKind.DONE -> {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(msg.text, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        ChatKind.ERROR -> {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer) {
                Text(msg.text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
        ChatKind.USER, ChatKind.ASSISTANT -> {
            val isUser = msg.kind == ChatKind.USER
            var showActions by remember { mutableStateOf(false) }
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                Row(verticalAlignment = Alignment.Top) {
                    if (!isUser) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(28.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Column(modifier = Modifier.widthIn(max = 620.dp)) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 16.dp,
                            ),
                            color = if (isUser) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showActions = !showActions })
                            },
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                // 引用来源
                                msg.quote?.let { q ->
                                    Row(modifier = Modifier.padding(bottom = 6.dp)) {
                                        Surface(shape = RoundedCornerShape(6.dp),
                                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceContainerHighest) {
                                            Text(q, style = MaterialTheme.typography.labelSmall,
                                                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Box(modifier = Modifier.weight(1f, fill = false)) {
                                        SelectionContainer {
                                            if (isUser) {
                                                Text(msg.text, style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onPrimary)
                                            } else {
                                                MarkdownText(msg.text, fontSizeScale = fontSizeScale,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    onLinkClick = onLinkClick)
                                            }
                                        }
                                    }
                                    if (isStreaming && !isUser) {
                                        Spacer(Modifier.width(3.dp))
                                        StreamingCaret()
                                    }
                                }
                            }
                        }
                        // 长按弹出的操作条：引用 / 复制 / 删除
                        AnimatedVisibility(visible = showActions) {
                            Row(modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = { onQuote(msg.text); showActions = false },
                                    modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.FormatQuote, t("引用"), Modifier.size(16.dp))
                                }
                                IconButton(onClick = { onCopy(msg.text); showActions = false },
                                    modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.ContentCopy, t("复制"), Modifier.size(16.dp))
                                }
                                IconButton(onClick = { onDelete(msg); showActions = false },
                                    modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, t("删除"), Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    if (isUser) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(28.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Person, null, Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** M3 Expressive 思考占位：三个错峰脉冲圆点。 */
@Composable
private fun ThinkingDots() {
    val reduced = reduceMotion(LocalContext.current)
    val transition = rememberInfiniteTransition(label = "thinking")
    // Reduce Motion 时 target 固定为初值：三个点静态显示不同透明度，仍表达"思考中"但不循环
    val dots = listOf(
        transition.animateFloat(0.3f, if (reduced) 0.3f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "dot0"),
        transition.animateFloat(0.6f, if (reduced) 0.6f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "dot1"),
        transition.animateFloat(0.9f, if (reduced) 0.9f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "dot2"),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dots.forEach { alpha ->
            Box(modifier = Modifier.size(6.dp).graphicsLayer { this.alpha = alpha.value }
                .clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** 流式输出光标。 */
@Composable
private fun StreamingCaret() {
    val reduced = reduceMotion(LocalContext.current)
    val transition = rememberInfiniteTransition(label = "caret")
    // Reduce Motion 时光标保持常亮（静态），仍标示流式位置但不闪烁
    val alpha by transition.animateFloat(1f, if (reduced) 1f else 0f, infiniteRepeatable(tween(500), RepeatMode.Reverse), "caretAlpha")
    Box(modifier = Modifier.size(width = 2.dp, height = 15.dp).graphicsLayer { this.alpha = alpha }
        .clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.primary))
}
