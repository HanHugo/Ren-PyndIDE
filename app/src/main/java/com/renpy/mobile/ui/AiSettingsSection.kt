package com.renpy.mobile.ui

import androidx.compose.runtime.Composable

/**
 * 设置页的 AI 助手分区。
 * 内容已统一收敛到 [AiConfigForm]，侧边 AI 面板的配置抽屉复用同一个表单。
 */
@Composable
fun AiSettingsSection() {
    AiConfigForm()
}
