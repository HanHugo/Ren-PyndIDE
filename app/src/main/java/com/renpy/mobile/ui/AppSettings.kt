package com.renpy.mobile.ui

import com.renpy.mobile.i18n.t

/**
 * 个性化设置相关的枚举定义。
 * 集中放在这里，便于主题层、设置页、MainActivity 共用。
 */

enum class ThemeMode(val label: String, val desc: String) {
    SYSTEM(t("跟随系统"), t("根据设备深色模式自动切换")),
    LIGHT(t("浅色"), t("始终使用浅色主题")),
    DARK(t("深色"), t("始终使用深色主题")),
}

enum class FontSize(val label: String, val scale: Float) {
    SMALL(t("小"), 0.85f),
    NORMAL(t("标准"), 1.0f),
    LARGE(t("大"), 1.15f),
    XLARGE(t("超大"), 1.3f),
}

enum class AppPalette(val label: String) {
    INDIGO(t("靛蓝紫")),
    OCEAN(t("海洋蓝")),
    FOREST(t("森林绿")),
    SUNSET(t("暖阳橙")),
    DYNAMIC(t("动态取色")),
}

/** 编辑器项目树布局偏好。 */
enum class EditorLayout(val label: String, val desc: String) {
    AUTO(t("智能适配"), t("横屏常驻双栏，竖屏用抽屉")),
    DRAWER(t("始终抽屉"), t("无论横竖屏，项目树都从侧边滑出")),
}
