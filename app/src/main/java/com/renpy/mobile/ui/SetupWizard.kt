package com.renpy.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.renpy.mobile.i18n.t

// ─────────────────────────────────────────────────────────────────────────────
// Ren'PyndIDE 首次启动向导 —— Material 3 Expressive
// 4 步：欢迎 Hero → 真实文件访问权限 → 工具链环境 → 共享 UID 扩展包
// 全部完成才解锁「进入 IDE」；无跳过入口，引导为强制流程。
// 所有动画走 motionScheme；ANIMATOR_DURATION_SCALE=0 时降级为 snap。
// ─────────────────────────────────────────────────────────────────────────────

private enum class WizardStep(val label: String) {
    WELCOME(t("欢迎")),
    PERMISSION(t("权限")),
    TOOLCHAIN(t("环境")),
    EXTENSION(t("扩展")),
}

private data class ToolItem(val id: String, val name: String, val size: String, val desc: String, val icon: ImageVector)

/** 与 SdkInstaller.COMPONENTS 一一对应的官方真实包展示数据。 */
private val TOOL_ITEMS = listOf(
    ToolItem("sdk", t("Ren'Py SDK 核心"), "156 MiB", t("引擎与桌面运行时"), Icons.Filled.Memory),
    ToolItem("rapt", t("Android 打包工具 RAPT"), "69 MiB", t("APK 打包工具链"), Icons.Filled.Build),
    ToolItem("native", t("原生符号库"), "88 MiB", t("原生层调试支持"), Icons.Filled.Hardware),
)

private fun formatSpeed(bps: Long): String = when {
    bps <= 0 -> ""
    bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / (1024f * 1024f))
    else -> "%.0f KB/s".format(bps / 1024f)
}

/** 是否已获得项目文件访问能力（按系统版本分支）。 */
private fun hasFileAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

/** 当前设备是否关闭了动画（系统「移除动画」）。 */
private fun reduceMotion(context: Context): Boolean =
    try {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (e: Exception) {
        false
    }

/** 空间弹簧 spec：可中断、按尺寸分级；Reduce Motion 时 snap。 */
@Composable
private fun <T> mSpatial(reduced: Boolean, slow: Boolean = false): FiniteAnimationSpec<T> {
    val scheme = MaterialTheme.motionScheme
    if (reduced) return snap<T>()
    return if (slow) scheme.slowSpatialSpec() else scheme.defaultSpatialSpec()
}

/** 效果 spec：用于颜色/透明度；Reduce Motion 时 snap。 */
@Composable
private fun <T> mEffects(reduced: Boolean): FiniteAnimationSpec<T> {
    val scheme = MaterialTheme.motionScheme
    if (reduced) return snap<T>()
    return scheme.defaultEffectsSpec()
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun SetupWizard(onEnter: () -> Unit) {
    val context = LocalContext.current
    val reduced = remember { reduceMotion(context) }

    val sizeClass = (context as? Activity)?.let { calculateWindowSizeClass(it) }
    val wide = sizeClass?.widthSizeClass in setOf(
        WindowWidthSizeClass.Medium, WindowWidthSizeClass.Expanded
    )

    var step by remember { mutableStateOf(0) }
    var fileGranted by remember { mutableStateOf(hasFileAccess(context)) }
    var extensionDone by remember { mutableStateOf(false) }

    // SDK 安装真实状态（由前台服务/协程驱动）
    val installerStates by com.renpy.mobile.installer.SdkInstaller.states.collectAsState()
    // 门槛完全由 StateFlow 派生，状态一到 INSTALLED 立即刷新，避免读 prefs/文件的时序问题
    val toolchainDone = com.renpy.mobile.installer.SdkInstaller.allInstalledFromState(installerStates)

    // 低版本走运行时权限；高版本走 MANAGE_EXTERNAL_STORAGE 设置页
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> fileGranted = granted || hasFileAccess(context) }

    fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            runCatching { context.startActivity(intent) }
        } else {
            requestPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // 从系统设置返回后，重新检测权限真实状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) fileGranted = hasFileAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = WizardStep.entries[step]
    val canAdvance = when (current) {
        WizardStep.WELCOME -> true
        WizardStep.PERMISSION -> fileGranted
        WizardStep.TOOLCHAIN -> toolchainDone
        WizardStep.EXTENSION -> extensionDone
    }
    val allDone = fileGranted && toolchainDone && extensionDone

    fun advance() {
        if (step < WizardStep.entries.lastIndex) step++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 顶部品牌光晕（Expressive 氛围层）
        HeroGlow(reduced = reduced)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsSafe(),
        ) {
            // ── 顶部：品牌 + 步骤进度 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(modifier = Modifier.size(34.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${step + 1} / ${WizardStep.entries.size}",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            StepIndicator(
                currentIndex = step,
                labels = WizardStep.entries.map { it.label },
                reduced = reduced,
                onSelect = { index -> if (index <= step) step = index },
            )

    // ── 步骤内容（全屏切换：slow spatial + fade）──
    // spec 必须在 composable 上下文计算（transitionSpec lambda 非 composable）
    val slideSpec: FiniteAnimationSpec<IntOffset> = mSpatial(reduced, slow = true)
    val fadeSpec: FiniteAnimationSpec<Float> = mEffects(reduced)
    AnimatedContent(
        targetState = step,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        transitionSpec = {
            (slideInHorizontally(slideSpec) { it / 8 } + fadeIn(fadeSpec)) togetherWith
                (slideOutHorizontally(slideSpec) { -it / 8 } + fadeOut(fadeSpec))
        },
                label = "wizardStep",
            ) { index ->
                val contentModifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .then(if (wide) Modifier.widthIn(max = 620.dp) else Modifier)

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    when (WizardStep.entries[index]) {
                        WizardStep.WELCOME -> WelcomeStep(contentModifier, reduced)
                        WizardStep.PERMISSION -> PermissionStep(
                            modifier = contentModifier,
                            granted = fileGranted,
                            reduced = reduced,
                            onRequest = { requestFileAccess() },
                        )
                        WizardStep.TOOLCHAIN -> ToolchainStep(
                            modifier = contentModifier,
                            states = installerStates,
                            reduced = reduced,
                        )
                        WizardStep.EXTENSION -> ExtensionStep(
                            modifier = contentModifier,
                            done = extensionDone,
                            reduced = reduced,
                            onDone = { extensionDone = true },
                        )
                    }
                }
            }

            // ── 底部导航 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    Button(
                        onClick = { step-- },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.height(54.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(t("上一步"))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (current == WizardStep.EXTENSION && allDone) onEnter() else advance()
                    },
                    enabled = canAdvance,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .defaultMinSize(minWidth = 130.dp),
                ) {
                    Text(
                        when {
                            current == WizardStep.EXTENSION -> t("进入 IDE")
                            current == WizardStep.WELCOME -> t("开始设置")
                            else -> t("下一步")
                        },
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

/** 顶部 pill 分段步骤指示器：当前段更宽、强调，点击可回退到已完成步骤。 */
@Composable
private fun StepIndicator(
    currentIndex: Int,
    labels: List<String>,
    reduced: Boolean,
    onSelect: (Int) -> Unit,
) {
    val barSpec: FiniteAnimationSpec<Float> = mSpatial(reduced)
    val colorSpec: FiniteAnimationSpec<Color> = mEffects(reduced)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == currentIndex
            val done = index < currentIndex
            val weight by animateFloatAsState(
                targetValue = if (active) 1.6f else 1f,
                animationSpec = barSpec,
                label = "stepWeight",
            )
            val barColor by animateColorAsState(
                targetValue = when {
                    active || done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = colorSpec,
                label = "stepColor",
            )
            Column(
                modifier = Modifier
                    .weight(weight)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = done) { onSelect(index) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = if (active) MaterialTheme.typography.labelLargeEmphasized
                    else MaterialTheme.typography.labelMedium,
                    color = if (active || done) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(barColor),
                )
            }
        }
    }
}

/** 背景光晕：primary / tertiary 径向渐变，spring 入场淡入。 */
@Composable
private fun HeroGlow(reduced: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val spec: FiniteAnimationSpec<Float> = mSpatial(reduced, slow = true)
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val appear by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = spec,
        label = "glowAppear",
    )
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear },
    ) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.28f, size.height * 0.10f),
                radius = size.minDimension * 0.85f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(tertiary.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.32f),
                radius = size.minDimension * 0.75f,
            ),
        )
    }
}

/** 步骤 0：品牌标识 + 产品名 + 简短介绍，说明这个 IDE 能做什么。 */
@Composable
private fun WelcomeStep(modifier: Modifier, reduced: Boolean) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(44.dp))
        BrandMark(modifier = Modifier.size(104.dp))
        Spacer(Modifier.height(28.dp))
        EnterBox(delayMs = 80) {
            Text(
                text = "Ren'PyndIDE",
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(14.dp))
        EnterBox(delayMs = 140) {
            Text(
                text = t("在手机上编写、编译并打包 Ren'Py 视觉小说"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 200) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    WelcomeFeature(Icons.Filled.Folder, t("创建与管理项目"), t("集中管理你的 Ren'Py 项目与脚本文件。"))
                    Spacer(Modifier.height(18.dp))
                    WelcomeFeature(Icons.Filled.Build, t("设备端编译"), t("内置 Ren'Py 引擎，直接在手机上编译预览。"))
                    Spacer(Modifier.height(18.dp))
                    WelcomeFeature(Icons.Filled.Extension, t("打包成 APK"), t("下载工具链，把游戏打包为 Android 应用。"))
                }
            }
        }
    }
}

/** 欢迎页的一条能力介绍：图标 + 标题 + 一行说明。 */
@Composable
private fun WelcomeFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 步骤 1：真实文件访问权限。 */
@Composable
private fun PermissionStep(
    modifier: Modifier,
    granted: Boolean,
    reduced: Boolean,
    onRequest: () -> Unit,
) {
    val tintSpec: FiniteAnimationSpec<Color> = mEffects(reduced)
    val tint by animateColorAsState(
        targetValue = if (granted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tintSpec,
        label = "permTint",
    )

    Column(modifier = modifier) {
        Spacer(Modifier.height(36.dp))
        StepHeading(
            eyebrow = t("第 1 步 · 共 3 步"),
            title = t("项目文件访问"),
            desc = t("IDE 需要读取和写入你的 Ren'Py 项目目录，才能在设备上创建、编译与调试游戏。"),
        )
        Spacer(Modifier.height(30.dp))

        EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 80) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(modifier = Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (granted) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = if (granted) MaterialTheme.colorScheme.onPrimaryContainer else tint,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            t("全部文件访问权限"),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (granted) t("已授予，可访问项目文件") else t("尚未授予 · 点击右侧按钮开启"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (granted) {
                        DoneCheck(reduced)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 140) {
            Text(
                text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    t("在 Android 11 及以上，系统会打开「所有文件访问」设置页，请为 Ren'PyndIDE 开启该权限后返回。")
                    else t("系统会弹出运行时权限请求，请允许「写入外部存储」以便管理项目文件。"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!granted) {
            Spacer(Modifier.height(24.dp))
            EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 200) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(t("授予访问权限"), style = MaterialTheme.typography.titleMediumEmphasized)
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** 步骤 2：工具链环境（真实下载：Ren'Py 官方站 SDK 组件）。 */
@Composable
private fun ToolchainStep(
    modifier: Modifier,
    states: List<com.renpy.mobile.installer.SdkInstaller.ComponentState>,
    reduced: Boolean,
) {
    val context = LocalContext.current
    val installer = com.renpy.mobile.installer.SdkInstaller
    val allInstalled = installer.allInstalledFromState(states)
    val anyRunning = states.any {
        it.phase == com.renpy.mobile.installer.SdkInstaller.Phase.DOWNLOADING ||
            it.phase == com.renpy.mobile.installer.SdkInstaller.Phase.VERIFYING ||
            it.phase == com.renpy.mobile.installer.SdkInstaller.Phase.EXTRACTING
    }
    val anyFailed = states.any { it.phase == com.renpy.mobile.installer.SdkInstaller.Phase.FAILED }

    // 总体进度 = 各组件 downloaded/total 的加权平均
    val overall = if (states.isEmpty()) 0f else states.map { s ->
        if (s.total > 0) (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f) else 0f
    }.average().toFloat()

    Column(modifier = modifier) {
        Spacer(Modifier.height(36.dp))
        StepHeading(
            eyebrow = t("第 2 步 · 共 3 步"),
            title = t("开发环境"),
            desc = t("从 Ren'Py 官方站拉取编译所需的 SDK 组件，独立存放于应用私有目录，不占用系统全局环境，也不会撑大安装包体。"),
        )
        Spacer(Modifier.height(30.dp))

        // 总体进度卡
        EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 60) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Hardware,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            t("工具链组件"),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(overall * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { overall },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50)),
                    )
                    val active = states.firstOrNull {
                        it.phase == com.renpy.mobile.installer.SdkInstaller.Phase.DOWNLOADING
                    }
                    if (active != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${formatBytes(active.downloaded)} / ${formatBytes(active.total)}  ·  ${formatSpeed(active.speedBps)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // 三个 SDK 组件之间保留舒适间隔，避免挤在一起
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TOOL_ITEMS.forEachIndexed { index, item ->
                val state = states.firstOrNull { it.id == item.id }
                EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 120 + index * 60) {
                    ToolRow(
                        item = item,
                        state = state,
                        reduced = reduced,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 320) {
            when {
                allInstalled -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "SDK ${installer.SDK_VERSION} 已就绪，可进入下一步",
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                anyRunning -> {
                    Button(
                        onClick = { installer.cancelAll() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(t("暂停下载"), style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            // 失败后重试：先清掉失败/暂停项，再启动前台服务下载
                            installer.retryFailed()
                            com.renpy.mobile.installer.SdkDownloadService.start(context)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(
                            if (anyFailed) Icons.Filled.Refresh else Icons.Filled.Hardware,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (anyFailed) t("重试下载") else t("开始下载"),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 步骤 3：共享 UID 扩展包（安装动画 + 完成形变）。 */
@Composable
private fun ExtensionStep(
    modifier: Modifier,
    done: Boolean,
    reduced: Boolean,
    onDone: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var running by remember { mutableStateOf(false) }
    val spec: FiniteAnimationSpec<Float> = mSpatial(reduced)
    val cornerSpec: FiniteAnimationSpec<androidx.compose.ui.unit.Dp> = mSpatial(reduced)
    val corner by animateDpAsState(
        targetValue = if (done) 50.dp else 20.dp,
        animationSpec = cornerSpec,
        label = "shapeMorph",
    )

    LaunchedEffect(running) {
        if (running && progress.value < 1f) {
            progress.animateTo(1f, spec)
            onDone()
        }
    }

    Column(modifier = modifier) {
        Spacer(Modifier.height(36.dp))
        StepHeading(
            eyebrow = t("第 3 步 · 共 3 步"),
            title = t("预览扩展包"),
            desc = t("安装共享 UID 扩展，使游戏预览以独立进程（:preview）运行，并与主进程共享数据，编译与预览互不阻塞。"),
        )
        Spacer(Modifier.height(30.dp))

        EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 80) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(corner))
                            .background(MaterialTheme.colorScheme.tertiary),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.size(32.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            t("Bootstrap 扩展"),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (done) t("已安装 · 预览进程就绪") else t("独立进程 · 共享数据通道"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        if (running && !done) {
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
            )
        }

        Spacer(Modifier.height(26.dp))
        EnterBox(modifier = Modifier.fillMaxWidth(), delayMs = 140) {
            if (!done) {
                Button(
                    onClick = { running = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        if (running) t("正在安装…") else t("安装扩展包"),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t("环境就绪，点击下方「进入 IDE」"),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** 统一标题区：eyebrow（强调色）+ emphasized 大标题 + 描述。 */
@Composable
private fun StepHeading(eyebrow: String, title: String, desc: String) {
    EnterBox {
        Text(
            eyebrow,
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(10.dp))
    EnterBox(delayMs = 50) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(12.dp))
    EnterBox(delayMs = 100) {
        Text(
            desc,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 工具项行：图标容器 + 名称/状态 + 单项进度/完成态。 */
@Composable
private fun ToolRow(
    item: ToolItem,
    state: com.renpy.mobile.installer.SdkInstaller.ComponentState?,
    reduced: Boolean,
) {
    val phase = state?.phase ?: com.renpy.mobile.installer.SdkInstaller.Phase.IDLE
    val done = phase == com.renpy.mobile.installer.SdkInstaller.Phase.INSTALLED
    val active = phase == com.renpy.mobile.installer.SdkInstaller.Phase.DOWNLOADING ||
        phase == com.renpy.mobile.installer.SdkInstaller.Phase.VERIFYING ||
        phase == com.renpy.mobile.installer.SdkInstaller.Phase.EXTRACTING
    val cornerSpec: FiniteAnimationSpec<androidx.compose.ui.unit.Dp> = mSpatial(reduced)
    val corner by animateDpAsState(
        targetValue = if (done) 50.dp else 16.dp,
        animationSpec = cornerSpec,
        label = "toolCorner",
    )
    val statusText = when (phase) {
        com.renpy.mobile.installer.SdkInstaller.Phase.DOWNLOADING ->
            "下载中 ${if (state!!.total > 0) ((state.downloaded * 100) / state.total).toInt() else 0}%"
        com.renpy.mobile.installer.SdkInstaller.Phase.VERIFYING -> t("校验中…")
        com.renpy.mobile.installer.SdkInstaller.Phase.EXTRACTING -> t("解压中…")
        com.renpy.mobile.installer.SdkInstaller.Phase.INSTALLED -> "已安装 · ${item.size}"
        com.renpy.mobile.installer.SdkInstaller.Phase.FAILED -> "失败：${state?.error ?: "未知错误"}"
        com.renpy.mobile.installer.SdkInstaller.Phase.PAUSED -> "已暂停 · ${item.size}"
        com.renpy.mobile.installer.SdkInstaller.Phase.IDLE -> item.size
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(
                            if (done) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (done) Icons.Filled.Check else item.icon,
                        contentDescription = null,
                        tint = if (done) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (phase == com.renpy.mobile.installer.SdkInstaller.Phase.FAILED)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (done) DoneCheck(reduced)
                if (active) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            if (active && state != null && state.total > 0) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                )
            }
        }
    }
}

/** 完成态打勾：圆形实心 + check 弹性出现。 */
@Composable
private fun DoneCheck(reduced: Boolean) {
    val scaleSpec: FiniteAnimationSpec<Float> = mSpatial(reduced)
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = scaleSpec,
        label = "checkScale",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = t("已完成"),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 让内容避开系统栏（edge-to-edge 安全区）。 */
@Composable
private fun Modifier.systemBarsSafe(): Modifier =
    this.windowInsetsPadding(WindowInsets.systemBars)
