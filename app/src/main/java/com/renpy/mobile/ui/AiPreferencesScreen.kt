package com.renpy.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.renpy.mobile.ai.AiConfig
import com.renpy.mobile.ai.AiPrefs
import com.renpy.mobile.i18n.t

/**
 * AI 偏好页：集中管理所有「非连接」的对话显示与行为设置。
 * - 正文字号、思考卡片透明度（显示）
 * - 思考强度（推理深度，对所有服务商统一生效）
 * 与服务商管理页分离，避免两类设置混在一条长表单里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPreferencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var fontScale by remember { mutableStateOf(AiPrefs.getFontScale(context)) }
    var thinkAlpha by remember { mutableStateOf(AiPrefs.getThinkAlpha(context)) }
    var effort by remember { mutableStateOf(AiPrefs.getEffort(context)) }

    BackHandler { onBack() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(t("AI 偏好"), style = MaterialTheme.typography.headlineMediumEmphasized) },
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
            Text(
                t("这些设置对所有服务商统一生效，切换服务商无需重复调整。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // ── 显示偏好 ──
            EnterBox(delayMs = 80) {
            PrefSection(icon = Icons.Filled.Visibility, title = t("对话显示")) {
                Text(t("正文字号"), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(t("影响 AI 回复与思考内容的显示大小"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                AiPrefs.FONT_OPTIONS.forEach { (label, scale) ->
                    val selected = fontScale == scale
                    val container by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        label = "fontChoice",
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp), color = container,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        onClick = { fontScale = scale; AiPrefs.setFontScale(context, scale) },
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FormatSize, null, Modifier.size(18.dp),
                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f))
                            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text(t("思考卡片透明度"), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(t("调低可让思考内容更淡，减少对正文的干扰"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("淡"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = thinkAlpha,
                        onValueChange = {
                            thinkAlpha = it.coerceIn(0.25f, 1f)
                            AiPrefs.setThinkAlpha(context, thinkAlpha)
                        },
                        valueRange = 0.25f..1f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(t("实"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("当前：${(thinkAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            }

            Spacer(Modifier.height(16.dp))

            // ── 思考强度 ──
            EnterBox(delayMs = 200) {
            PrefSection(icon = Icons.Filled.AutoAwesome, title = t("思考强度")) {
                Text(t("推理深度"), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(t("对所有服务商统一生效。默认档不额外下发参数，兼容所有模型。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                AiConfig.ReasoningEffort.entries.forEach { e ->
                    val selected = effort == e
                    val container by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        label = "effortChoice",
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp), color = container,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        onClick = { effort = e; AiPrefs.setEffort(context, e) },
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.label, style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f))
                                if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                            Text(e.hint, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(t("仅对推理模型生效（o 系列 / DeepSeek-R1 / Claude / Gemini 2.5）；普通模型调高也无变化，但不影响使用。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PrefSection(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
