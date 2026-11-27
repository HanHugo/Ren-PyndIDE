package com.renpy.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * M3 Expressive 通用入场容器：内容先透明并下移，再用 spatial spring 弹性到位。
 * 使用 MaterialTheme.motionScheme 的 spatial spec（可中断、阻尼 ~0.6），
 * 而不是硬编码 tween。delayMs 用于列表错峰（stagger）。
 */
@Composable
fun EnterBox(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    distance: Float = 32f,
    content: @Composable () -> Unit,
) {
    val spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, animationSpec = spec)
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * distance
        },
    ) {
        content()
    }
}

/**
 * 按压弹性缩放。用 fastSpatialSpec 让小组件按压回弹，符合 Expressive 速度分级：
 * 小组件用 fast，不用 slow。
 */
@Composable
fun pressScale(pressed: Boolean): Float {
    val spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec()
    return animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spec,
        label = "pressScale",
    ).value
}

/**
 * 通用按压回弹容器：按下缩小、松手弹回，并在抬起（未被取消）时触发点击。
 * 用于给卡片/按钮这类可点击元素统一的触觉反馈，避免各界面重复实现 detectTapGestures。
 * [scaleDown] 为按下时的缩放比例，卡片用 0.96、小图标用 0.9 更自然。
 */
@Composable
fun PressScaleBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scaleDown: Float = 0.96f,
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spec,
        label = "pressScaleBox",
    )
    Box(
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
    ) { content() }
}
