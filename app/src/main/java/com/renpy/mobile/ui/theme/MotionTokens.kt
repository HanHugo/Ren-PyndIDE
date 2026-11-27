package com.renpy.mobile.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 全局动效 token —— 统一 M3 Expressive motionScheme 与 Reduce Motion 降级。
 *
 * 设计依据：
 * - abhixv/google-m3-expressive-design-skill `references/motion.md`：
 *   几何/位移属性走 spatial spring，颜色/透明度走 effects spring（damping 1.0，不回弹）。
 * - canine-labs/DESIGN.md §7：Motion is a functional requirement；必须提供 reduced-motion 替代。
 * - albermonte/android-skills `compose-mapping.md`：系统动画缩放为 0 时使用即时（instant）转场。
 *
 * 逻辑来源：SetupWizard.kt 现有的 reduceMotion() / mSpatial() 已验证实现，
 * 此处提升为全局共享 token，逐字保留其判定与降级行为，不改变任何动画表现。
 */

/** 当前设备是否关闭了动画（系统「移除动画」/ 动画时长缩放为 0）。 */
fun reduceMotion(context: Context): Boolean =
    try {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (e: Exception) {
        false
    }

/** 空间弹簧 spec：可中断、按尺寸分级；Reduce Motion 时 snap。 */
@Composable
fun <T> expressiveSpatialSpec(reduced: Boolean, speed: Speed = Speed.DEFAULT): FiniteAnimationSpec<T> {
    val scheme = MaterialTheme.motionScheme
    if (reduced) return snap<T>()
    return when (speed) {
        Speed.FAST -> scheme.fastSpatialSpec()
        Speed.SLOW -> scheme.slowSpatialSpec()
        Speed.DEFAULT -> scheme.defaultSpatialSpec()
    }
}

/** 效果弹簧 spec（透明度/颜色）；Reduce Motion 时 snap。 */
@Composable
fun <T> expressiveEffectsSpec(reduced: Boolean, speed: Speed = Speed.DEFAULT): FiniteAnimationSpec<T> {
    val scheme = MaterialTheme.motionScheme
    if (reduced) return snap<T>()
    return when (speed) {
        Speed.FAST -> scheme.fastEffectsSpec()
        Speed.SLOW -> scheme.slowEffectsSpec()
        Speed.DEFAULT -> scheme.defaultEffectsSpec()
    }
}

enum class Speed { FAST, DEFAULT, SLOW }
