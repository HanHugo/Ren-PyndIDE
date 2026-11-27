package com.renpy.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.roundToInt
import com.renpy.mobile.i18n.t

/**
 * 打包完成庆祝卡（Material 3 Expressive）。
 *
 * 打包成功后立即弹出，第一眼传达「成功 + 这是你的应用」：
 *  - 应用图标（自定义图标优先，缺省用 Android 机器人），右下角成功对勾徽章
 *  - 应用名 emphasized 大标题 + 「已生成安装包」说明
 *  - 信息卡：APK 文件名 + 体积；包名 / 版本胶囊
 *  - 操作：安装（filled primary）、分享（filledTonal）、完成（text）
 *
 * 入场弹性缩放淡入、图标徽章延迟弹入、按钮按压回弹全部走 motionScheme，
 * 自动兼容 Reduce Motion。点遮罩 / 返回键 / 「完成」都只关闭弹窗，不产生副作用。
 */
@Composable
fun BuildCompleteDialog(
    appName: String,
    packageName: String,
    version: String,
    iconPath: String,
    apkPath: String,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spatial: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val enter = remember { Animatable(0f) }
    val badge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, animationSpec = spatial)
        badge.animateTo(1f, animationSpec = spatial)
    }

    val fileName = remember(apkPath) { apkPath.substringAfterLast('/') }
    val sizeText = remember(apkPath) {
        runCatching {
            val mb = File(apkPath).length() / 1024f / 1024f
            if (mb >= 1f) "${mb.roundToInt()} MB" else "${(mb * 1024f).roundToInt()} KB"
        }.getOrDefault("")
    }

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
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── 应用图标 + 成功徽章 ──
                Box(
                    modifier = Modifier.size(104.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = remember(iconPath) {
                            if (iconPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(iconPath) }.getOrNull() else null
                        }
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = t("应用图标"),
                                modifier = Modifier.fillMaxWidth().size(96.dp),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Android,
                                contentDescription = t("应用图标"),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(52.dp),
                            )
                        }
                    }
                    // 成功对勾徽章：延迟弹入
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .graphicsLayer {
                                val b = badge.value
                                scaleX = b; scaleY = b; alpha = b
                            }
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = t("打包成功"),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    if (appName.isNotBlank()) appName else t("应用已打包"),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t("安装包已生成，可以安装或分享了"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(20.dp))
                // ── 信息卡：文件名 + 体积 ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Android,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                fileName,
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                t("APK 安装包"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (sizeText.isNotBlank()) {
                            Text(
                                sizeText,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // ── 包名 / 版本胶囊 ──
                if (packageName.isNotBlank() || version.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        if (packageName.isNotBlank()) {
                            PillLabel(packageName)
                        }
                        if (version.isNotBlank()) {
                            PillLabel("v$version")
                        }
                    }
                }

                Spacer(Modifier.height(26.dp))
                // ── 操作区：安装（主）+ 分享（次）──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompleteActionButton(
                        text = t("安装"),
                        icon = Icons.Filled.InstallMobile,
                        filled = true,
                        onClick = onInstall,
                        modifier = Modifier.weight(1f),
                    )
                    CompleteActionButton(
                        text = t("分享"),
                        icon = Icons.Filled.Share,
                        filled = false,
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(t("完成"), style = MaterialTheme.typography.labelLargeEmphasized)
                }
            }
        }
    }
}

/** 信息胶囊：包名 / 版本。 */
@Composable
private fun PillLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** 完成卡弹性按钮：按压回弹，主/次两级视觉，走 motionScheme。 */
@Composable
private fun CompleteActionButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "completeBtnPress",
    )
    val container = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val content = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
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
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLargeEmphasized, color = content, maxLines = 1)
        }
    }
}
