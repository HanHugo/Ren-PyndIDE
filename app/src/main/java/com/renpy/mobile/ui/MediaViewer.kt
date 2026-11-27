package com.renpy.mobile.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.renpy.mobile.i18n.t

/**
 * 图片查看器：解码本地图片（大图自动采样防 OOM），支持双指缩放 / 拖动平移 / 双击复位。
 * 图片/音频都不走文本 readFile，避免二进制被当字符串打开而乱码。
 */
@Composable
fun ImageViewer(path: String, fileName: String, modifier: Modifier = Modifier) {
    // 采样解码，限制最大边 2048px，避免大图 OOM
    val bitmap = remember(path) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val maxSide = 2048
            val sample = maxOf(
                1,
                (maxOf(bounds.outWidth, bounds.outHeight) / maxSide.toFloat()).roundToInt(),
            )
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            if (scale <= 1f) offset = Offset.Zero
                            else offset = Offset(
                                (offset.x + pan.x).coerceIn(-size.width * (scale - 1f), size.width * (scale - 1f)),
                                (offset.y + pan.y).coerceIn(-size.height * (scale - 1f), size.height * (scale - 1f)),
                            )
                        }
                    }
                    .pointerInput(path) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.BrokenImage,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(t("无法解码图片"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 文件名 + 缩放提示：胶囊悬浮条
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${(scale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 音频查看器：基于 MediaPlayer 的播放 / 暂停 / 停止 / 拖动进度。
 */
@Composable
fun AudioViewer(path: String, fileName: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isPlaying by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableIntStateOf(0) }
    var duration by remember(path) { mutableIntStateOf(0) }

    val player = remember(path) {
        MediaPlayer().apply {
            try {
                setDataSource(path)
                prepare()
                duration = this.duration.coerceAtLeast(0)
            } catch (e: Exception) {
                duration = 0
            }
        }
    }

    // 播放进度轮询
    androidx.compose.runtime.LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try {
                position = player.currentPosition.coerceAtMost(duration)
            } catch (e: Exception) { }
            kotlinx.coroutines.delay(200)
        }
    }

    DisposableEffect(path) {
        onDispose {
            try { if (player.isPlaying) player.stop() } catch (e: Exception) { }
            player.release()
        }
    }

    fun togglePlay() {
        try {
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
            } else {
                if (duration > 0 && position >= duration) {
                    player.seekTo(0)
                    position = 0
                }
                player.start()
                isPlaying = true
            }
        } catch (e: Exception) { }
    }

    fun stop() {
        try {
            player.stop()
            player.prepare()
        } catch (e: Exception) { }
        isPlaying = false
        position = 0
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 唱片封面：圆角方形容器（Expressive 不用圆形 FAB / 头像）
            Surface(
                modifier = Modifier.size(160.dp),
                shape = RoundedCornerShape(36.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Audiotrack,
                        contentDescription = null,
                        modifier = Modifier.size(76.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = fileName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = t("音频文件"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            // 进度条
            Slider(
                value = if (duration > 0) position.toFloat() else 0f,
                onValueChange = { value ->
                    position = value.roundToInt()
                    try { player.seekTo(position) } catch (e: Exception) { }
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    thumbColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(position), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatMs(duration), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))

            // 播放控制：圆角方形 FAB（Expressive 默认），primary 主 CTA
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledIconButton(
                    onClick = { stop() },
                    shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = t("停止"), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(20.dp))
                FilledIconButton(
                    onClick = { togglePlay() },
                    shape = RoundedCornerShape(20.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) t("暂停") else t("播放"),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Int): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}
