package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.renpy.mobile.ui.theme.JetBrainsMono
import com.renpy.mobile.model.ManualData
import com.renpy.mobile.model.ManualEntry
import com.renpy.mobile.i18n.t

/**
 * 离线语法手册（MD3 Expressive）：
 * - 顶部搜索框实时过滤（标题/摘要/说明/语法/示例）；
 * - 按官方语句/文本标签/字符串/基础语法分类，条目可展开查看语法、说明与示例；
 * - 示例代码套用与编辑器一致的语法高亮，浅/深色自适应；
 * - 内容全部随包内置，离线可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(
    darkTheme: Boolean,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { ManualData.search(query) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        t("Ren'Py 语法手册"),
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
                .padding(padding),
        ) {
            // ── 搜索栏 ──
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text(t("搜索关键字、标签、语法…")) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )

            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        t("没有匹配的手册条目"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp,
                ),
            ) {
                results.forEach { category ->
                    item(key = "header_${category.id}") {
                        EnterBox(delayMs = 0) {
                            CategoryHeader(category.title, category.description)
                        }
                    }
                    itemsIndexed(
                        items = category.entries,
                        key = { idx, entry -> "${category.id}_${idx}_${entry.title}" },
                    ) { idx, entry ->
                        EnterBox(delayMs = (idx * 30).coerceAtMost(180)) {
                            // EnterBox 内部是 Box（子项重叠），必须套 Column 才能让
                            // Spacer 真正把卡片往下推开，否则间距被压在卡片底下看不见。
                            Column(modifier = Modifier.fillMaxWidth()) {
                                EntryCard(entry = entry, darkTheme = darkTheme)
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                    item(key = "divider_${category.id}") {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String, description: String) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

@Composable
private fun EntryCard(entry: ManualEntry, darkTheme: Boolean) {
    var expanded by rememberSaveable(entry.title) { mutableStateOf(false) }
    val expandSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val effectsSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val rot by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = effectsSpec,
        label = "manualChevron",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) t("收起") else t("展开"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = rot },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = expandSpec) + fadeIn(animationSpec = effectsSpec),
                exit = shrinkVertically(animationSpec = expandSpec) + fadeOut(animationSpec = effectsSpec),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    Spacer(Modifier.height(12.dp))
                    LabeledBlock(t("语法")) {
                        CodeLine(entry.syntax, darkTheme, mono = true)
                    }
                    Spacer(Modifier.height(12.dp))
                    LabeledBlock(t("说明")) {
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (entry.example.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        LabeledBlock(t("示例")) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (darkTheme)
                                    com.renpy.mobile.ui.theme.SyntaxDark.currentLine
                                else com.renpy.mobile.ui.theme.SyntaxLight.currentLine,
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    entry.example.lines().forEach { line ->
                                        Text(
                                            text = syntaxCode(line, darkTheme),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = JetBrainsMono,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun LabeledBlock(label: String, content: @Composable () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    content()
}

@Composable
private fun CodeLine(text: String, darkTheme: Boolean, mono: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = if (mono) syntaxCode(text, darkTheme)
                   else AnnotatedString(text),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (mono) JetBrainsMono else FontFamily.Default,
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 对单行套用编辑器同款语法高亮。 */
@Composable
private fun syntaxCode(line: String, darkTheme: Boolean): AnnotatedString =
    SyntaxHighlight.highlight(line, darkTheme, currentLine = 0)
