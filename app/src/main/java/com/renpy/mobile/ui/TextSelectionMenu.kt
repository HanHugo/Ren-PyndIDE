package com.renpy.mobile.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import kotlin.math.roundToInt
import com.renpy.mobile.i18n.t

/**
 * 空 TextToolbar：注入到 LocalTextToolbar 以屏蔽 Android 系统原生黑色选择菜单。
 * 菜单的显示与位置完全由我们自己用局部坐标控制，不再依赖系统回调传入的 rect。
 */
class EmptyTextToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {}
}

/**
 * Compose 1.10+/BOM 2026 引入的新版文本上下文菜单 provider 的空实现。
 * 新版 BasicTextField 除了旧的 TextToolbar，还会走 LocalTextContextMenuToolbarProvider /
 * LocalTextContextMenuDropdownProvider 弹出系统菜单；仅屏蔽旧 TextToolbar 已不够。
 * 这个 no-op provider 的 showTextContextMenu 什么都不做，彻底吞掉新版系统菜单。
 */
object NoOpTextContextMenuProvider : TextContextMenuProvider {
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) = Unit
}

// 菜单自身尺寸：4 个 48dp 按钮 + 3 个 2dp 间隔 + 左右各 6dp padding，高 48dp + 上下各 5dp padding。
// 用 dp 常量集中管理，定位时经 LocalDensity 换算成 px，避免硬编码 px 导致菜单压住选中文本。
private val MENU_BUTTON = 48.dp
private val MENU_GAP = 2.dp
private val MENU_PAD_H = 6.dp
private val MENU_PAD_V = 5.dp
private val MENU_CLEARANCE = 10.dp // 菜单与选中文本之间的呼吸间距

/**
 * 自绘选择菜单：pill 容器 + 4 个圆角方形图标按钮，spring 入场弹性。
 *
 * [anchorRect] 是选中文本相对编辑区根 Box 的局部坐标矩形（由 TextLayoutResult 计算），
 * [containerSize] 是根 Box 的像素尺寸，用于把菜单约束在可视区内。
 *
 * 定位规则（全部为局部像素坐标）：
 * - 垂直：优先浮在选区**上方**（menuH + clearance），上方空间不足则放到**下方**；
 *   上下都不足时贴最近的一侧并夹在容器内。菜单永远不会压住选中文本。
 * - 水平：菜单中心对齐选区中心，再 clamp 到 [0, 容器宽 - 菜单宽]，左右都不溢出。
 */
@Composable
fun CustomSelectionMenu(
    anchorRect: Rect?,
    containerSize: IntSize,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rect = anchorRect ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val appear by animateFloatAsState(targetValue = 1f, animationSpec = spec, label = "selMenu")

    with(density) {
        val menuW = (MENU_BUTTON * 4 + MENU_GAP * 3 + MENU_PAD_H * 2).toPx()
        val menuH = (MENU_BUTTON + MENU_PAD_V * 2).toPx()
        val clearance = MENU_CLEARANCE.toPx()

        // ── 水平：中心对齐选区，再夹在容器左右边界内 ──
        val selectionCenterX = (rect.left + rect.right) / 2f
        val desiredX = selectionCenterX - menuW / 2f
        val xPx = desiredX.coerceIn(0f, (containerSize.width - menuW).coerceAtLeast(0f))

        // ── 垂直：优先上方，空间不足放下方，再不足则贴边 ──
        val aboveY = rect.top - menuH - clearance
        val belowY = rect.bottom + clearance
        val yPx = when {
            aboveY >= 0f -> aboveY                                  // 上方放得下
            belowY + menuH <= containerSize.height -> belowY        // 上方不够，下方放得下
            else -> (containerSize.height - menuH).coerceAtLeast(0f) // 都不够，贴底
        }

        Surface(
            modifier = modifier
                .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                .graphicsLayer {
                    scaleX = appear
                    scaleY = appear
                    alpha = appear
                    // 从靠近选区的边缘展开：放上方时锚点在底部，放下方时锚点在顶部
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                        0.5f,
                        if (aboveY >= 0f) 1f else 0f,
                    )
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 10.dp,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MENU_PAD_H, vertical = MENU_PAD_V),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuAction(Icons.Filled.ContentCopy, t("复制"), onCopy)
                MenuAction(Icons.Filled.ContentCut, t("剪切"), onCut)
                MenuAction(Icons.Filled.ContentPaste, t("粘贴"), onPaste)
                MenuAction(Icons.Filled.SelectAll, t("全选"), onSelectAll)
            }
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spec,
        label = "menuAction",
    )
    Surface(
        modifier = Modifier
            .size(MENU_BUTTON)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    Spacer(Modifier.width(MENU_GAP))
}
