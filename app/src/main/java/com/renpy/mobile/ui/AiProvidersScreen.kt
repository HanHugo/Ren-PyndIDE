package com.renpy.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.renpy.mobile.ai.AgentLoop
import com.renpy.mobile.ai.AiConfig
import com.renpy.mobile.ai.LlmClient
import com.renpy.mobile.project.ProjectManager
import com.renpy.mobile.ui.theme.LocalMessenger
import com.renpy.mobile.ui.theme.MessageTone
import com.renpy.mobile.ui.theme.Speed
import com.renpy.mobile.ui.theme.expressiveEffectsSpec
import com.renpy.mobile.ui.theme.expressiveSpatialSpec
import com.renpy.mobile.ui.theme.reduceMotion
import kotlinx.coroutines.launch
import com.renpy.mobile.i18n.t

/**
 * AI 服务商管理页（MD3 Expressive），两页式：
 * - 列表页：竖向服务商卡片，显示名称/模型/协议/Key 状态/激活标记；点卡片进详情，+ 新增；
 * - 详情页：选中档案的连接/密钥/高级分组编辑 + 保存/测试；模型列表、余额、日志放进弹层，
 *   不再把主表单撑成长滚动条。
 * 思考强度、字号、透明度已移到「AI 偏好」页统一设置，此处不再重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProvidersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val reduced = reduceMotion(context)
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<AiConfig.Profile>>(emptyList()) }
    var activeId by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf("") }
    // 当前是否处于详情页（selectedId 为要编辑的档案）
    var detail by remember { mutableStateOf(false) }

    // 编辑态（针对 selectedId 的档案）
    var editName by remember { mutableStateOf("") }
    var editBaseUrl by remember { mutableStateOf("") }
    var editModel by remember { mutableStateOf("") }
    var editApiKey by remember { mutableStateOf("") }
    var editUserAgent by remember { mutableStateOf(AiConfig.DEFAULT_USER_AGENT) }
    var uaPresetEnabled by remember { mutableStateOf(true) }
    var editFormat by remember { mutableStateOf(AiConfig.ApiFormat.OPENAI) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val logs = remember { mutableStateListOf<String>() }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var modelList by remember { mutableStateOf<List<String>?>(null) }
    var balanceText by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var loadingBalance by remember { mutableStateOf(false) }

    // 结果弹层
    var showResultSheet by remember { mutableStateOf(false) }
    var resultTab by remember { mutableStateOf(0) } // 0=模型 1=余额 2=日志

    fun refresh() {
        profiles = AiConfig.listProfiles(context)
        activeId = AiConfig.getActiveId(context)
    }

    fun loadIntoEditor(id: String) {
        val p = profiles.firstOrNull { it.id == id } ?: return
        selectedId = id
        editName = p.name
        editBaseUrl = p.baseUrl
        editModel = p.model
        editApiKey = p.apiKey
        editUserAgent = p.userAgent
        uaPresetEnabled = AiConfig.UA_PRESETS.any { it.second == p.userAgent }
        editFormat = p.format
        logs.clear(); resultMsg = null; modelList = null; balanceText = null
    }

    fun openDetail(id: String) {
        loadIntoEditor(id)
        detail = true
    }

    LaunchedEffect(Unit) {
        refresh()
        val target = AiConfig.getActiveId(context).ifBlank { profiles.firstOrNull()?.id ?: "" }
        if (target.isNotBlank()) loadIntoEditor(target)
    }

    fun currentConfig(): AiConfig.Config = AiConfig.Config(
        baseUrl = editBaseUrl.trim(),
        model = editModel.trim(),
        apiKey = editApiKey.trim(),
        enabled = true,
        format = editFormat,
        userAgent = editUserAgent,
        profileId = selectedId,
        profileName = editName,
    )

    fun saveProfile(quiet: Boolean = false) {
        if (editName.isBlank() || editBaseUrl.isBlank()) {
            resultMsg = t("✗ 名称与地址不能为空")
            return
        }
        AiConfig.saveProfile(context, AiConfig.Profile(
            id = selectedId,
            name = editName.trim(),
            baseUrl = editBaseUrl.trim(),
            model = editModel.trim(),
            apiKey = editApiKey.trim(),
            enabled = profiles.firstOrNull { it.id == selectedId }?.enabled ?: true,
            format = editFormat,
            userAgent = editUserAgent,
        ))
        refresh()
        if (!quiet) {
            resultMsg = "✓ 已保存「${editName.trim()}」"
            messenger.show(t("已保存"), MessageTone.SUCCESS)
        }
    }

    fun activate(id: String) {
        AiConfig.setActive(context, id)
        activeId = id
        messenger.show("已切换到「${profiles.firstOrNull { it.id == id }?.name}」", MessageTone.INFO)
    }

    fun addProvider() {
        val id = AiConfig.newProfileId()
        AiConfig.saveProfile(context, AiConfig.Profile(
            id = id, name = t("新服务商"), baseUrl = "", model = "",
            apiKey = "", enabled = true, format = AiConfig.ApiFormat.OPENAI,
        ))
        refresh()
        openDetail(id)
    }

    fun testRun() {
        saveProfile(quiet = true)
        val project = ProjectManager.listProjects(context).firstOrNull()
        if (project == null) { resultMsg = t("✗ 请先创建一个项目再测试"); return }
        if (editBaseUrl.isBlank() || editApiKey.isBlank() || editModel.isBlank()) {
            resultMsg = t("✗ 请先填写地址、Key 和模型"); return
        }
        logs.clear(); resultMsg = null; testing = true
        resultTab = 2; showResultSheet = true
        var lastWasText = false
        val cfg = currentConfig()
        val agent = AgentLoop(
            context = context,
            projectPath = project.path,
            onEvent = { ev ->
                if (ev is AgentLoop.AgentEvent.TextDelta) {
                    val t = ev.text
                    if (t.isNotBlank() && !t.equals("null", true) && !t.equals("none", true)) {
                        if (lastWasText && logs.isNotEmpty()) logs[logs.lastIndex] = logs.last() + t
                        else { logs += t; lastWasText = true }
                    }
                } else {
                    lastWasText = false
                    val line = when (ev) {
                        is AgentLoop.AgentEvent.Reasoning -> "💭 ${ev.text.take(100)}"
                        is AgentLoop.AgentEvent.ToolStart -> "▸ ${ev.tool} ${ev.args.take(100)}"
                        is AgentLoop.AgentEvent.ToolDone -> "  ✓ ${ev.tool}: ${ev.summary.take(100)}"
                        is AgentLoop.AgentEvent.AskUser -> "? ${ev.question}"
                        is AgentLoop.AgentEvent.Status -> "· ${ev.message}"
                        is AgentLoop.AgentEvent.Done -> "\n✓ 完成，改动：${ev.changedFiles.joinToString()}"
                        is AgentLoop.AgentEvent.Error -> "\n✗ 错误：${ev.message}"
                        else -> ""
                    }
                    if (line.isNotEmpty()) logs += line
                }
            },
            onAskUser = { _, options -> options.firstOrNull() ?: "" },
            overrideConfig = cfg,
        )
        scope.launch {
            agent.run(t("请读取当前项目概览，告诉我项目里有哪些标签和角色。"))
            testing = false
        }
    }

    fun fetchModels() {
        saveProfile(quiet = true)
        if (editBaseUrl.isBlank() || editApiKey.isBlank()) { resultMsg = t("✗ 请先填写地址和 Key"); return }
        loadingModels = true; resultMsg = null; modelList = null
        resultTab = 0; showResultSheet = true
        val cfg = currentConfig()
        scope.launch {
            LlmClient.listModels(cfg).onSuccess { list ->
                modelList = list.map { it.id }
                resultMsg = "✓ 获取到 ${list.size} 个模型"
            }.onFailure { e ->
                modelList = emptyList()
                resultMsg = "✗ 获取失败：${e.message?.take(160)}"
            }
            loadingModels = false
        }
    }

    fun fetchBalance() {
        saveProfile(quiet = true)
        if (editBaseUrl.isBlank() || editApiKey.isBlank()) { resultMsg = t("✗ 请先填写地址和 Key"); return }
        loadingBalance = true; resultMsg = null; balanceText = null
        resultTab = 1; showResultSheet = true
        val cfg = currentConfig()
        scope.launch {
            val info = LlmClient.fetchBalance(cfg)
            balanceText = info.display
            if (!info.ok) resultMsg = "✗ ${info.display.take(80)}"
            loadingBalance = false
        }
    }

    // 返回键：详情页先回列表；列表页退出
    BackHandler {
        if (showResultSheet) showResultSheet = false
        else if (detail) detail = false
        else onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (detail) {
                LargeTopAppBar(
                    title = { Text(editName.ifBlank { t("编辑服务商") }, style = MaterialTheme.typography.headlineMediumEmphasized) },
                    navigationIcon = {
                        IconButton(onClick = { detail = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回列表"))
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (profiles.size > 1) showDeleteConfirm = true
                            else messenger.show(t("至少保留一个服务商"), MessageTone.WARNING) }) {
                            Icon(Icons.Filled.Delete, contentDescription = t("删除服务商"))
                        }
                    },
                )
            } else {
                LargeTopAppBar(
                    title = { Text(t("AI 服务商"), style = MaterialTheme.typography.headlineMediumEmphasized) },
                    actions = {
                        IconButton(onClick = { addProvider() }) {
                            Icon(Icons.Filled.Add, contentDescription = t("新增服务商"))
                        }
                    },
                )
            }
        },
    ) { padding ->
        // 原 spring(380, 0.8) 即 expressive default spatial；改走 token 以支持 Reduce Motion
        val navSpec = expressiveSpatialSpec<androidx.compose.ui.unit.IntOffset>(reduced)
        val fadeSpec: FiniteAnimationSpec<Float> = expressiveEffectsSpec(reduced)
        AnimatedContent(
            targetState = detail,
            transitionSpec = {
                // 进详情：右入左出；返回列表：左入右出
                if (targetState) {
                    (slideInHorizontally(navSpec) { it / 8 } + fadeIn(fadeSpec)) togetherWith
                        (slideOutHorizontally(navSpec) { -it / 12 } + fadeOut(fadeSpec))
                } else {
                    (slideInHorizontally(navSpec) { -it / 8 } + fadeIn(fadeSpec)) togetherWith
                        (slideOutHorizontally(navSpec) { it / 12 } + fadeOut(fadeSpec))
                }
            },
            label = "providerNav",
        ) { showingDetail ->
        if (!showingDetail) {
            // ── 列表页 ──
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        t("点选进入编辑，各家 Key 独立保存；勾选标记表示当前对话使用的服务商。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                itemsIndexed(profiles, key = { _, p -> p.id }) { idx, p ->
                    EnterBox(delayMs = idx * 45) {
                        ProviderRow(
                            name = p.name,
                            model = p.model,
                            format = p.format.label,
                            hasKey = p.apiKey.isNotBlank(),
                            active = p.id == activeId,
                            onClick = { openDetail(p.id) },
                            onActivate = { activate(p.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            // ── 详情页 ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 20.dp),
            ) {
                // 分组 1：连接
                GroupHeader(icon = Icons.Filled.Link, title = t("连接"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editName, onValueChange = { editName = it },
                    label = { Text(t("名称")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = editBaseUrl, onValueChange = { editBaseUrl = it },
                    label = { Text(t("API 地址")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://api.deepseek.com/v1") },
                    supportingText = {
                        Text(t("可填基础地址或完整端点（如 .../v1/chat/completions），原样使用不改写"),
                            style = MaterialTheme.typography.bodySmall)
                    },
                )
                Spacer(Modifier.height(6.dp))
                val cfgPreview = currentConfig()
                val preview = if (editBaseUrl.isNotBlank()) LlmClient.previewEndpoint(cfgPreview) else "—"
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Link, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("请求地址：$preview", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = editModel, onValueChange = { editModel = it },
                    label = { Text(t("模型名")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("deepseek-chat") },
                )

                Spacer(Modifier.height(8.dp))
                Text(t("接入协议"), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiConfig.ApiFormat.entries.forEach { f ->
                        FilterChip(
                            selected = editFormat == f,
                            onClick = {
                                editFormat = f
                                val preset = AiConfig.PRESETS.firstOrNull { it.format == f }
                                if (preset != null && editBaseUrl.isBlank()) {
                                    editBaseUrl = preset.baseUrl
                                    editModel = preset.model
                                    editUserAgent = preset.userAgent
                                }
                            },
                            label = { Text(f.label) },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 分组 2：密钥
                GroupHeader(icon = Icons.Filled.Key, title = t("密钥（本服务商独立，不与其他服务商共用）"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editApiKey, onValueChange = { editApiKey = it },
                    label = { Text("API Key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) t("隐藏") else t("显示"))
                        }
                    },
                    placeholder = { Text("sk-...") },
                )

                Spacer(Modifier.height(16.dp))

                // 分组 3：高级
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { showAdvanced = !showAdvanced }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Tune, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(t("高级：客户端标识 / User-Agent"), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(
                    visible = showAdvanced,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(t("使用编程客户端标识"), style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(t("遇 403「仅支持编程客户端」时开启，下方一键切换白名单标识"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = uaPresetEnabled, onCheckedChange = { uaPresetEnabled = it })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (uaPresetEnabled) {
                            Text(t("选择标识（点选后需保存）"), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AiConfig.UA_PRESETS.forEach { (label, ua) ->
                                    FilterChip(
                                        selected = editUserAgent == ua,
                                        onClick = { editUserAgent = ua },
                                        label = { Text(label) },
                                        leadingIcon = {
                                            if (editUserAgent == ua)
                                                Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = editUserAgent, onValueChange = { editUserAgent = it },
                            label = { Text(t("User-Agent（可手动修改）")) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(t("中转站按 UA 白名单放行；若仍 403，逐个切换上方标识并测试。留空则用系统默认。"),
                                    style = MaterialTheme.typography.bodySmall)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 激活开关
                val isActive = selectedId == activeId
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t("设为当前使用的服务商"), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(t("AI 助手对话将使用这家配置"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isActive, onCheckedChange = { if (it) activate(selectedId) })
                }

                Spacer(Modifier.height(16.dp))

                // 操作区
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { saveProfile() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(t("保存"))
                    }
                    Button(onClick = { testRun() }, enabled = !testing, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (testing) t("测试中…") else t("测试运行"))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AssistChip(onClick = { fetchModels() }, enabled = !loadingModels,
                        label = { Text(if (loadingModels) t("获取中…") else t("模型列表")) },
                        leadingIcon = { Icon(Icons.Filled.CloudDownload, null, Modifier.size(16.dp)) })
                    AssistChip(onClick = { fetchBalance() }, enabled = !loadingBalance,
                        label = { Text(if (loadingBalance) t("查询中…") else t("查询余额")) })
                }

                resultMsg?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(32.dp))
            }
        }
        }
    }

    // ── 结果弹层：模型列表 / 余额 / 测试日志 ──
    if (showResultSheet) {
        ModalBottomSheet(
            onDismissRequest = { showResultSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(t("检测结果"), style = MaterialTheme.typography.titleLargeEmphasized)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = resultTab == 0, onClick = { resultTab = 0 }, label = { Text(t("模型")) })
                    FilterChip(selected = resultTab == 1, onClick = { resultTab = 1 }, label = { Text(t("余额")) })
                    FilterChip(selected = resultTab == 2, onClick = { resultTab = 2 }, label = { Text(t("日志")) })
                }
                Spacer(Modifier.height(12.dp))
                val resultTabFadeSpec: FiniteAnimationSpec<Float> = expressiveEffectsSpec(reduced)
                AnimatedContent(targetState = resultTab, transitionSpec = {
                    fadeIn(resultTabFadeSpec) togetherWith fadeOut(resultTabFadeSpec)
                }, label = "resultTab") { tab ->
                when (tab) {
                    0 -> {
                        val list = modelList
                        if (loadingModels) Text(t("正在获取模型…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (list == null) Text(t("尚未获取，点详情页「模型列表」按钮"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Surface(shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState()).padding(10.dp)) {
                                list.forEach { id ->
                                    Text(id, style = MaterialTheme.typography.bodySmall,
                                        color = if (id == editModel) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                            .clickable { editModel = id; saveProfile(quiet = true) })
                                }
                            }
                        }
                    }
                    1 -> {
                        val text = balanceText
                        if (loadingBalance) Text(t("正在查询…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (text == null) Text(t("尚未查询，点详情页「查询余额」按钮"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Surface(shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(text, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(12.dp))
                        }
                    }
                    else -> {
                        if (logs.isEmpty()) Text(t("暂无日志"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Surface(shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState()).padding(12.dp)) {
                                logs.forEach { line ->
                                    Text(line, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(t("删除服务商？")) },
            text = { Text("将删除「${editName}」及其保存的 API Key，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    AiConfig.deleteProfile(context, selectedId)
                    showDeleteConfirm = false
                    detail = false
                    refresh()
                    val next = AiConfig.getActiveId(context).ifBlank { profiles.firstOrNull()?.id ?: "" }
                    if (next.isNotBlank()) loadIntoEditor(next)
                    messenger.show(t("已删除"), MessageTone.SUCCESS)
                }) { Text(t("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(t("取消")) } },
        )
    }
}

@Composable
private fun GroupHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 列表页竖向卡片：名称/模型/协议/Key 状态 + 激活勾选 + 切换使用。 */
@Composable
private fun ProviderRow(
    name: String,
    model: String,
    format: String,
    hasKey: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onActivate: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "providerRowContainer",
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        border = if (active) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(name.take(1), color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLargeEmphasized)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (active) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, t("当前使用"), Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(model.ifBlank { t("未设模型") }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(format, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasKey) t("已填 Key") else t("未填 Key"), style = MaterialTheme.typography.labelSmall,
                        color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            if (!active) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onActivate, modifier = Modifier.height(36.dp)) {
                    Text(t("使用"), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
