package com.renpy.mobile.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme

/**
 * 品牌标志：星空渐变的六芒星（两个等边三角形环叠加，中间镂空六边形 + 六个小三角），
 * 带入场缩放旋转动画。color 参数保留以兼容旧调用点。
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    animate: Boolean = true,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    var started by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { started = true }
    val spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.slowSpatialSpec()
    val p by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = spec,
        label = "brand",
    )

    Canvas(
        modifier = modifier.graphicsLayer {
            alpha = p
            scaleX = 0.6f + 0.4f * p
            scaleY = 0.6f + 0.4f * p
            rotationZ = -25f * (1f - p)
        },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val R = minOf(size.width, size.height) * 0.47f   // 三角形外接半径（尖角到中心）
        val lineW = R * 0.27f                             // 描边宽度
        val Ri = R - 2 * lineW                            // 内缩三角顶点半径

        val brush = Brush.linearGradient(
            colors = listOf(primary, tertiary),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        )

        // 每个三角形环 = 外三角 + 内三角（evenOdd 规则下内三角成为镂空孔）。
        // 两个环分别绘制（同色不透明）即为并集，形成标准六芒星镂空结构。
        fun addRingTo(path: Path, startDeg: Double) {
            val outer = (0..2).map { i ->
                val a = Math.toRadians(startDeg + i * 120.0)
                Offset(cx + R * Math.cos(a).toFloat(), cy + R * Math.sin(a).toFloat())
            }
            val inner = (0..2).map { i ->
                val a = Math.toRadians(startDeg + i * 120.0)
                Offset(cx + Ri * Math.cos(a).toFloat(), cy + Ri * Math.sin(a).toFloat())
            }
            path.moveTo(outer[0].x, outer[0].y)
            path.lineTo(outer[1].x, outer[1].y)
            path.lineTo(outer[2].x, outer[2].y)
            path.close()
            path.moveTo(inner[0].x, inner[0].y)
            path.lineTo(inner[1].x, inner[1].y)
            path.lineTo(inner[2].x, inner[2].y)
            path.close()
        }

        for (startDeg in listOf(-90.0, 90.0)) {
            val ring = Path().apply { fillType = PathFillType.EvenOdd }
            addRingTo(ring, startDeg)
            drawPath(ring, brush)
        }
    }
}
