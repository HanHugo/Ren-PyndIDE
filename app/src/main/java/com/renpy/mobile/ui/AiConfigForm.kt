package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.renpy.mobile.ai.AgentLoop
import com.renpy.mobile.ai.AiConfig
import com.renpy.mobile.ai.LlmClient
import com.renpy.mobile.project.ProjectManager
import kotlinx.coroutines.launch
import com.renpy.mobile.i18n.t

/**
 * AI 配置表单：协议格式 / Base URL / 模型 / Key / User-Agent / 启用开关，
 * 以及 保存 / 测试运行 / 获取模型列表 / 查询余额。
 *
 * 同时被「设置页」和「侧边 AI 助手面板的配置抽屉」复用，避免两处逻辑分叉。
 */
@Composable
fun AiConfigForm(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf(AiConfig.DEFAULT_USER_AGENT) }
    var enabled by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(AiConfig.ApiFormat.OPENAI) }
    var selectedPreset by remember { mutableStateOf(-1) }
    var showAdvanced by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var loadingBalance by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    var modelList by remember { mutableStateOf<List<String>?>(null) }
    var balanceText by remember { mutableStateOf<String?>(null) }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val c = AiConfig.load(context)
        baseUrl = c.baseUrl
        model = c.model
        apiKey = c.apiKey
        userAgent = c.userAgent
        enabled = c.enabled
        format = c.format
        selectedPreset = AiConfig.PRESETS.indexOfFirst {
            it.baseUrl == c.baseUrl && it.format == c.format
        }
    }

    fun save(quiet: Boolean = false): AiConfig.Config {
        AiConfig.save(context, baseUrl, model, apiKey, enabled, format, userAgent)
        if (!quiet) resultMsg = t("✓ 配置已保存")
        return AiConfig.load(context)
    }

    fun testRun() {
        val cfg = save(quiet = true)
        val project = ProjectManager.listProjects(context).firstOrNull()
        if (project == null) {
            resultMsg = t("✗ 请先创建一个项目再测试")
            return
        }
        if (!AiConfig.isConfigured(context)) {
            resultMsg = t("✗ 请先填写 API 地址、Key 和模型名")
            return
        }
        logs.clear()
        resultMsg = null
        testing = true
        // 连续的 TextDelta 合并进同一行，避免流式片段被切成一堆短行、文字显得又碎又窄。
        var lastWasText = false
        val agent = AgentLoop(
            context = context,
            projectPath = project.path,
            onEvent = { ev ->
                if (ev is AgentLoop.AgentEvent.TextDelta) {
                    val t = ev.text
                    // 双保险：过滤模型输出里夹杂的 null / none / 空白片段
                    if (t.isNotBlank() && !t.equals("null", true) && !t.equals("none", true)) {
                        if (lastWasText && logs.isNotEmpty()) {
                            logs[logs.lastIndex] = logs.last() + t
                        } else {
                            logs += t
                            lastWasText = true
                        }
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
        )
        scope.launch {
            agent.run(t("请读取当前项目概览，告诉我项目里有哪些标签和角色。"))
            testing = false
        }
    }

    fun fetchModels() {
        if (!AiConfig.isConfigured(context)) {
            resultMsg = t("✗ 请先填写 API 地址、Key 和模型名")
            return
        }
        val cfg = save(quiet = true)
        loadingModels = true
        resultMsg = null
        scope.launch {
            val r = LlmClient.listModels(cfg)
            loadingModels = false
            r.onSuccess { list ->
                modelList = list.map { it.id }
                resultMsg = "✓ 获取到 ${list.size} 个模型"
            }.onFailure { e ->
                modelList = emptyList()
                resultMsg = "✗ 获取模型列表失败：${e.message?.take(160)}"
            }
        }
    }

    fun fetchBalance() {
        if (!AiConfig.isConfigured(context)) {
            resultMsg = t("✗ 请先填写 API 地址、Key 和模型名")
            return
        }
        val cfg = save(quiet = true)
        loadingBalance = true
        resultMsg = null
        scope.launch {
            val info = LlmClient.fetchBalance(cfg)
            loadingBalance = false
            balanceText = info.display
            if (!info.ok) resultMsg = "✗ ${info.display.take(80)}"
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // ── 协议格式 ──
        Text(t("接入协议"), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiConfig.ApiFormat.entries.forEach { f ->
                FilterChip(
                    selected = format == f,
                    onClick = {
                        format = f
                        selectedPreset = -1
                        // 切协议时给一个对应的默认端点，避免用户手动改一长串
                        val preset = AiConfig.PRESETS.firstOrNull { it.format == f }
                        if (preset != null) {
                            baseUrl = preset.baseUrl
                            model = preset.model
                            userAgent = preset.userAgent
                        }
                    },
                    label = { Text(f.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(AiConfig.ApiFormat.entries.first { it == format }.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        // ── 预设 ──
        Text(t("预设"), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollStateCompat()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiConfig.PRESETS.forEachIndexed { i, preset ->
                FilterChip(
                    selected = selectedPreset == i,
                    onClick = {
                        selectedPreset = i
                        format = preset.format
                        baseUrl = preset.baseUrl
                        model = preset.model
                        userAgent = preset.userAgent
                    },
                    label = { Text(preset.name) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it; selectedPreset = -1 },
            label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.deepseek.com/v1") },
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = model, onValueChange = { model = it; selectedPreset = -1 },
            label = { Text(t("模型名")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("deepseek-chat") },
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") },
        )
        Spacer(Modifier.height(12.dp))

        // ── 高级：User-Agent ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showAdvanced = !showAdvanced }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("高级：客户端标识 / User-Agent"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            Icon(
                if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = showAdvanced,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                OutlinedTextField(
                    value = userAgent, onValueChange = { userAgent = it },
                    label = { Text("User-Agent") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(t("中转站按 UA 白名单放行编程客户端，被 403 拒绝时可尝试改为 Codex/1.0 或 Claude-Code/1.0"),
                            style = MaterialTheme.typography.bodySmall)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── 启用开关 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { enabled = !enabled; save(quiet = true) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(t("启用 AI 助手"), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(t("启用后可在侧边 AI 助手面板对话"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; save(quiet = true) })
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { save() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(t("保存"))
            }
            Button(onClick = { testRun() }, enabled = !testing, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (testing) t("测试中…") else t("测试运行"))
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 模型列表 / 余额 ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AssistChip(
                onClick = { fetchModels() },
                enabled = !loadingModels,
                label = { Text(if (loadingModels) t("获取中…") else t("获取模型列表")) },
                leadingIcon = { Icon(Icons.Filled.CloudDownload, null, Modifier.size(16.dp)) },
            )
            AssistChip(
                onClick = { fetchBalance() },
                enabled = !loadingBalance,
                label = { Text(if (loadingBalance) t("查询中…") else t("查询余额")) },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)) },
            )
        }

        resultMsg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error)
        }

        modelList?.let { list ->
            Spacer(Modifier.height(8.dp))
            Text("可用模型（${list.size}）", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp).padding(10.dp)) {
                    items(list) { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (id == model) {
                                Icon(Icons.Filled.CheckCircle, null,
                                    Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(id, style = MaterialTheme.typography.bodySmall,
                                color = if (id == model) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { model = id; save(quiet = true) })
                        }
                    }
                }
            }
        }

        balanceText?.let { text ->
            Spacer(Modifier.height(8.dp))
            Text(t("余额"), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp))
            }
        }

        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(t("测试日志"), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(logs) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            t("AI 助手通过内置 Python（renpy-mcp）直接读写项目文件，不上传源码。API Key 加密保存在系统 Keystore。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 横向滚动状态（避免每次重组新建）。 */
@Composable
private fun rememberScrollStateCompat() = androidx.compose.foundation.rememberScrollState()
