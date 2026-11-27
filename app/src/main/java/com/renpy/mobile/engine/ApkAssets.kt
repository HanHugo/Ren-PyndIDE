package com.renpy.mobile.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * APK 打包资源与配置。
 *
 * 负责：
 *  - 持久化每个项目的打包配置（应用名/包名/版本/方向 + 自定义图标/启动图路径）到项目根
 *    `android-build.json`，下次打开打包界面自动回显。
 *  - 把用户从相册选的图标处理成 Ren'Py RAPT 需要的资源，写进 distribution 根目录：
 *      * android-icon_foreground.png (432×432, 透明) —— 前景
 *      * android-icon_background.png (432×432, 不透明) —— 背景（前景色的模糊放大）
 *      * android-presplash.jpg —— 启动图（等比居中 + 单色边框填充）
 *    RAPT build() 会据此生成各密度 mipmap 图标与启动图 assets。
 */
object ApkAssets {

    data class Config(
        val appName: String = "",
        val packageName: String = "",
        val version: String = "1.0",
        val orientation: String = "sensorLandscape",
        val iconPath: String = "",
        val splashPath: String = "",
    )

    /** 配置文件固定放项目根，避免与 RAPT 的 android.json 混淆。 */
    private fun configFile(projectPath: String) = File(projectPath, "android-build.json")

    /** 资源副本目录（用户选的原图复制进项目，随项目持久化，避免相册原图被删后失效）。 */
    private fun assetsDir(projectPath: String) = File(projectPath, ".apk-assets").apply { mkdirs() }

    fun load(projectPath: String): Config {
        val f = configFile(projectPath)
        if (!f.exists()) return Config()
        return try {
            val text = f.readText()
            // 极简 JSON 解析：逐字段正则，避免引入依赖。
            fun str(key: String): String {
                val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text) ?: return ""
                return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            }
            Config(
                appName = str("appName"),
                packageName = str("packageName"),
                version = str("version").ifEmpty { "1.0" },
                orientation = str("orientation").ifEmpty { "sensorLandscape" },
                iconPath = str("iconPath"),
                splashPath = str("splashPath"),
            )
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(projectPath: String, config: Config) {
        fun q(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = buildString {
            append("{\n")
            append("  \"appName\": \"${q(config.appName)}\",\n")
            append("  \"packageName\": \"${q(config.packageName)}\",\n")
            append("  \"version\": \"${q(config.version)}\",\n")
            append("  \"orientation\": \"${q(config.orientation)}\",\n")
            append("  \"iconPath\": \"${q(config.iconPath)}\",\n")
            append("  \"splashPath\": \"${q(config.splashPath)}\"\n")
            append("}\n")
        }
        configFile(projectPath).writeText(json)
    }

    /** 从相册 Uri 导入图片到项目 .apk-assets/，返回落地的绝对路径。 */
    suspend fun importImage(context: Context, projectPath: String, uri: Uri, prefix: String): String =
        withContext(Dispatchers.IO) {
            val dir = assetsDir(projectPath)
            val ext = "png"
            val dest = File(dir, "${prefix}.$ext")
            context.contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(dest).use { out -> input?.copyTo(out) }
            }
            dest.absolutePath
        }

    /**
     * 把自定义图标与启动图写进 distribution 根目录，供 RAPT 生成资源。
     * 仅当对应路径指向存在的文件时写入；否则不写（RAPT 使用默认图标/启动图）。
     */
    suspend fun applyToDistribution(config: Config, dist: File) = withContext(Dispatchers.IO) {
        if (!config.iconPath.isNullOrEmpty()) {
            val icon = File(config.iconPath)
            if (icon.exists()) generateIcon(icon, dist)
        }
        if (!config.splashPath.isNullOrEmpty()) {
            val splash = File(config.splashPath)
            if (splash.exists()) generateSplash(splash, dist)
        }
    }

    /**
     * 生成 RAPT iconmaker 可直接复制的各密度 PNG（共 15 个）。
     * iconmaker.write_icon 会优先取 dist 里的 android-{name}-{dpi}.png 直接 shutil.copy，
     * 因此只要文件齐全，就完全不调用 pygame 图像处理（设备端无真 pygame 也能生成图标）。
     *  - icon_background：不透明底（主色 + 柔和铺底），基准 108dp
     *  - icon_foreground：原图等比居中、透明底，基准 108dp
     *  - icon：legacy 静态图标（背景+前景合成后圆形裁剪），基准 48dp
     */
    private fun generateIcon(src: File, dist: File) {
        val original = BitmapFactory.decodeFile(src.absolutePath) ?: return
        val safe = ensureMutable(original)
        val dominant = averageColor(safe)

        // dpi -> scale（与 RAPT iconmaker 一致）
        val dpis = listOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)
        for ((dpi, scale) in dpis) {
            val size108 = (108 * scale).toInt()
            val size48 = (48 * scale).toInt()

            // 前景：等比居中透明
            val fg = Bitmap.createBitmap(size108, size108, Bitmap.Config.ARGB_8888)
            val fgCanvas = Canvas(fg)
            val s = min(size108.toFloat() / safe.width, size108.toFloat() / safe.height)
            val w = safe.width * s; val h = safe.height * s
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            fgCanvas.drawBitmap(safe, null, RectF((size108 - w) / 2f, (size108 - h) / 2f, (size108 + w) / 2f, (size108 + h) / 2f), paint)
            savePng(fg, File(dist, "android-icon_foreground-$dpi.png"))

            // 背景：主色不透明 + 放大铺底
            val bg = Bitmap.createBitmap(size108, size108, Bitmap.Config.ARGB_8888)
            val bgCanvas = Canvas(bg)
            bgCanvas.drawColor(dominant)
            val cover = maxOf(size108.toFloat() / safe.width, size108.toFloat() / safe.height) * 1.6f
            val bw = safe.width * cover; val bh = safe.height * cover
            val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 90 }
            bgCanvas.drawBitmap(safe, null, RectF((size108 - bw) / 2f, (size108 - bh) / 2f, (size108 + bw) / 2f, (size108 + bh) / 2f), bmp)
            savePng(bg, File(dist, "android-icon_background-$dpi.png"))

            // legacy 静态图标：背景缩到 size48 + 前景叠上，圆形裁剪
            val icon = Bitmap.createBitmap(size48, size48, Bitmap.Config.ARGB_8888)
            val ic = Canvas(icon)
            ic.drawColor(dominant)
            val fs = min(size48.toFloat() / safe.width, size48.toFloat() / safe.height)
            val fw = safe.width * fs; val fh = safe.height * fs
            ic.drawBitmap(safe, null, RectF((size48 - fw) / 2f, (size48 - fh) / 2f, (size48 + fw) / 2f, (size48 + fh) / 2f), paint)
            // 圆形裁剪：用 Xfermode 清掉圆外像素
            val maskBitmap = Bitmap.createBitmap(size48, size48, Bitmap.Config.ARGB_8888)
            val mc = Canvas(maskBitmap)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            mc.drawCircle(size48 / 2f, size48 / 2f, size48 / 2f, maskPaint)
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            }
            ic.drawBitmap(maskBitmap, 0f, 0f, circlePaint)
            savePng(icon, File(dist, "android-icon-$dpi.png"))
        }
    }

    /** 启动图：等比居中，单色边框填充四周，输出 JPG。 */
    private fun generateSplash(src: File, dist: File) {
        val original = BitmapFactory.decodeFile(src.absolutePath) ?: return
        val safe = ensureMutable(original)
        // 以原图尺寸为准，限制最长边 1920 以控制体积
        val maxSide = 1920
        val scale = min(1f, maxSide.toFloat() / maxOf(safe.width, safe.height))
        val outW = (safe.width * scale).toInt().coerceAtLeast(1)
        val outH = (safe.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)
        canvas.drawColor(averageColor(safe))
        val s = min(outW.toFloat() / safe.width, outH.toFloat() / safe.height)
        val w = safe.width * s
        val h = safe.height * s
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(safe, null, RectF((outW - w) / 2f, (outH - h) / 2f, (outW + w) / 2f, (outH + h) / 2f), paint)
        FileOutputStream(File(dist, "android-presplash.jpg")).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    private fun ensureMutable(b: Bitmap): Bitmap =
        if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)

    private fun averageColor(b: Bitmap): Int {
        // 采样中心区域，避免边缘透明影响
        val cx = b.width / 2
        val cy = b.height / 2
        val r = maxOf(1, min(b.width, b.height) / 6)
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        for (y in (cy - r) until (cy + r) step 4) {
            if (y !in 0 until b.height) continue
            for (x in (cx - r) until (cx + r) step 4) {
                if (x !in 0 until b.width) continue
                val c = b.getPixel(x, y)
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
            }
        }
        if (n == 0) return Color.rgb(63, 81, 181)
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun savePng(b: Bitmap, f: File) {
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
