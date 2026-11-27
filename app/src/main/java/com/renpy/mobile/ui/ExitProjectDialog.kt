package com.renpy.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import com.renpy.mobile.i18n.t

/**
 * 退出项目确认卡（Material 3 Expressive）。
 * - 只有两个操作：「保存退出」（主操作，先播放保存反馈再退出）与「继续编辑」；
 *   不提供"直接退出"，避免误触丢失未保存内容。
 * - 图标容器 / 项目信息卡 / 保存说明 / 弹性按钮，全部走 motionScheme 与 48 色角色，
 *   兼容 Reduce Motion（motionScheme 自动退化）。
 * - 纯 UI 演示：保存与退出只做动画反馈，由回调交给上层处理。
 */
@Composable
fun ExitProjectDialog(
    onDismiss: () -> Unit,
    onSaveExit: () -> Unit,
    projectName: String = "My Visual Novel",
) {
    var saving by remember { mutableStateOf(false) }

    // 保存反馈：短暂展示"正在保存 → 已保存"，再交给上层退出
    LaunchedEffect(saving) {
        if (saving) {
            delay(720)
            onSaveExit()
        }
    }

    // 卡片弹性入场：0.9 → 1 缩放 + 淡入，走 spatial / effects spec
    val spatial: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, animationSpec = spatial) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    val p = enter.value
                    scaleX = 0.9f + 0.1f * p
                    scaleY = 0.9f + 0.1f * p
                    alpha = p
                },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── 图标容器：primary 调，表达"安全退出" ──
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    t("退出项目？"),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    t("退出前会保存当前所有更改，下次打开可继续编辑。"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(20.dp))
                // ── 项目信息卡：明确退出对象 ──
                ProjectInfoCard(projectName)

                Spacer(Modifier.height(14.dp))
                // ── 保存说明条 ──
                SaveNoteBar()

                Spacer(Modifier.height(26.dp))
                // ── 操作区：主按钮「保存退出」+ 次按钮「继续编辑」──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ExpressiveActionButton(
                        text = t("继续编辑"),
                        icon = Icons.Filled.FolderOpen,
                        filled = false,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    ExpressiveActionButton(
                        text = t("保存退出"),
                        icon = if (saving) Icons.Filled.Check else Icons.AutoMirrored.Filled.Logout,
                        filled = true,
                        enabled = !saving,
                        onClick = { saving = true },
                        modifier = Modifier.weight(1f),
                        saving = saving,
                        effects = effects,
                    )
                }
            }
        }
    }
}

/** 项目信息条：文件夹图标 + 项目名 + 分支胶囊。 */
@Composable
private fun ProjectInfoCard(projectName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    projectName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    t("Ren'Py 视觉小说项目"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "main",
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 保存说明条：info 图标 + 一行说明。 */
@Composable
private fun SaveNoteBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            t("所有未保存的更改都会自动写入磁盘"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 弹性动作按钮：按压回弹 + 渐弹性缩放，主/次两级视觉。 */
@Composable
private fun ExpressiveActionButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    saving: Boolean = false,
    effects: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec(),
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.95f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "exitBtnPress",
    )
    val container = if (filled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondaryContainer
    val content = if (filled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val consumed = tryAwaitRelease()
                        pressed = false
                        if (consumed) onClick()
                    },
                )
            },
        shape = RoundedCornerShape(20.dp),
        color = container,
        shadowElevation = if (filled) 3.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .height(50.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = saving,
                transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                label = "saveBtnLabel",
            ) { isSaving ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isSaving) t("保存中…") else text,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = content,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
