package com.renpy.mobile.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.renpy.mobile.i18n.t

/**
 * Snackbar 消息语义分级。
 *
 * 设计依据：
 * - 颜色全部取自项目已有 token（IdeSemanticColors / MaterialTheme.colorScheme），
 *   不引入新 hex，零编造色值。
 * - success/warning 沿用项目 LogPanel 原色；error/info 用 ColorScheme 角色，
 *   自动适配明暗主题与 5 套配色。
 */
enum class MessageTone {
    SUCCESS, ERROR, WARNING, INFO;

    /** 语义图标 —— 全部来自 material-icons-extended（项目已依赖，稳定 API）。 */
    val icon: ImageVector
        get() = when (this) {
            SUCCESS -> Icons.Filled.CheckCircle
            ERROR -> Icons.Filled.Error
            WARNING -> Icons.Filled.Warning
            INFO -> Icons.Filled.Info
        }

    /** 语义强调色 —— 需在 Composable 上下文读取主题色。 */
    @Composable
    fun color(): Color = when (this) {
        SUCCESS -> IdeSemanticColors.success
        ERROR -> MaterialTheme.colorScheme.error
        WARNING -> IdeSemanticColors.warning
        INFO -> MaterialTheme.colorScheme.primary
    }
}

/**
 * 携带语义分级的 SnackbarVisuals。
 *
 * 依据：androidx.compose.material3.SnackbarHostState.showSnackbar(visuals)
 * 官方示例即自定义 SnackbarVisuals 子类承载额外字段，属稳定 API。
 */
data class IdeSnackbarVisuals(
    override val message: String,
    val tone: MessageTone,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

/**
 * 全局消息通道：封装 [SnackbarHostState] 与协程作用域。
 *
 * - [show] 为非 suspend 入口，内部 launch，让 Composable 回调、协程、
 *   非 Composable 函数（通过 onMessage 透传）三类上下文统一调用。
 * - duration 映射自原 Toast：LENGTH_SHORT -> Short，LENGTH_LONG -> Long。
 */
class SnackbarMessenger(
    private val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(
        message: String,
        tone: MessageTone = MessageTone.INFO,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        scope.launch {
            hostState.showSnackbar(
                IdeSnackbarVisuals(
                    message = message,
                    tone = tone,
                    actionLabel = actionLabel,
                    withDismissAction = actionLabel != null,
                    duration = duration,
                )
            )
            if (onAction != null) onAction()
        }
    }
}

/** 在 Composable 内创建并记住一个 messenger（绑定宿主 state 与生命周期）。 */
@Composable
fun rememberSnackbarMessenger(hostState: SnackbarHostState): SnackbarMessenger {
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) { SnackbarMessenger(hostState, scope) }
}

/** 全局消息通道的 CompositionLocal。 */
val LocalMessenger = compositionLocalOf<SnackbarMessenger> {
    error(t("LocalMessenger 未提供：请确保调用点位于提供 LocalMessenger 的 CompositionLocalProvider 内"))
}

/**
 * 语义化 Snackbar 宿主：左侧语义图标 + 消息文本。
 *
 * 说明：Material3 的 [Snackbar] composable 无 icon 参数（已查官方参考页），
 * 故图标通过 content lambda 自绘；shape 用项目 IdeShapes.Medium（12dp token）。
 *
 * @param hostState 对应宿主状态（全局或 BuildSheet 独立宿主）
 */
@Composable
fun IdeSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState, modifier) { data: SnackbarData ->
        val visuals = data.visuals as? IdeSnackbarVisuals
        val tone = visuals?.tone ?: MessageTone.INFO
        Snackbar(
            shape = IdeShapes.Medium,
            action = null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = tone.icon,
                    contentDescription = null,
                    tint = tone.color(),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(data.visuals.message)
            }
        }
    }
}
