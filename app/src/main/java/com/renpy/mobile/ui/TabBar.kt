package com.renpy.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renpy.mobile.model.CodeTab
import com.renpy.mobile.model.FileKind

/**
 * 打开文件标签栏（横向可滚动）：选中项与代码区同色（surface），未选中为灰色；
 * 高度收紧，底部指示条标记当前标签。
 */
@Composable
fun TabBar(
    tabs: List<CodeTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit = {},
) {
    val scroll = rememberScrollState()

    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            // 选中：与代码区一致；未选中：灰色
            val container = if (selected) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainerHigh
            val contentColor = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(container)
                    .clickable { onSelect(index) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (tab.kind) {
                            FileKind.IMAGE -> Icons.Filled.Image
                            FileKind.AUDIO -> Icons.Filled.Audiotrack
                            else -> Icons.Filled.Description
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else contentColor,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = tab.fileName,
                        fontSize = 12.sp,
                        color = contentColor,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { onClose(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭 ${tab.fileName}",
                            modifier = Modifier.size(13.dp),
                            tint = contentColor,
                        )
                    }
                }
                // 底部选中指示条
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                            )
                            .size(width = 40.dp, height = 3.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))
    }
}
