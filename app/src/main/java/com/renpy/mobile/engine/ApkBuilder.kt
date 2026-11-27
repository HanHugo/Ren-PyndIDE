package com.renpy.mobile.engine

import android.content.Context
import com.renpy.mobile.installer.BuildToolchainInstaller
import com.renpy.mobile.project.ProjectInfo
import com.renpy.mobile.termux.TermuxBootstrap
import com.renpy.mobile.termux.TermuxSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.renpy.mobile.i18n.t

/**
 * 设备端 APK 打包器。
 *
 * 复用 Ren'Py 官方 RAPT（rapt.zip）+ Termux arm64 工具链，完全绕开 PC：
 *
 * 流程：
 *  1) 组装标准 Ren'Py distribution 目录（game/ + renpy/ + lib/ + main.py），
 *     其中 game/ 直接来自项目（编译后的 .rpyc + 资源），renpy/lib 来自 SDK。
 *  2) 生成 android.json 配置（包名/名称/版本/方向）。
 *  3) 生成签名密钥 android.keystore（keytool，alias=android，密码=android）。
 *  4) 用 Termux 的 Python 执行驱动脚本：设置 rapt.plat 路径，调用
 *     rapt.build.build() 生成 gradle 工程并构建 APK。
 *  5) 通过自定义 Interface 拦截 gradlew 调用，在构建前把 arm64 aapt2 路径和
 *     Maven 镜像写进 gradle.properties / local.properties（关键适配）。
 *  6) 产出 APK 复制到项目 apk-out/ 目录。
 *
 * 所有进度/日志通过 [onProgress] / [onLog] 回传。
 */
object ApkBuilder {

    data class BuildConfig(
        val packageName: String,
        val appName: String,
        val version: String,
        val orientation: String = "sensorLandscape",
        val iconName: String = appName,
        /** 自定义图标绝对路径（项目 .apk-assets/icon.png），空表示用默认图标。 */
        val iconPath: String = "",
        /** 自定义启动图绝对路径（项目 .apk-assets/splash.png），空表示用默认启动图。 */
        val splashPath: String = "",
    )

    data class BuildResult(
        val success: Boolean,
        val message: String,
        val apkPath: String? = null,
    )

    /** 打包阶段（供 UI 显示进度）。 */
    enum class Stage(val label: String) {
        PREPARE(t("准备打包环境")),
        DISTRIBUTION(t("组装游戏发布目录")),
        CONFIGURE(t("写入打包配置")),
        KEYSTORE(t("生成签名密钥")),
        GENERATE_PROJECT(t("生成 Android 工程")),
        BUILD_APK(t("编译 APK")),
        FINISH(t("收尾")),
    }

    private val COMPILE_LINE = Regex(""" Compiling|\.rpyc|BUILD SUCCESSFUL|BUILD FAILED|FAILURE:|error:|Exception|Traceback""", RegexOption.IGNORE_CASE)

    /** 打包工作目录。 */
    fun workDir(context: Context) = File(context.cacheDir, "apk-build").apply { mkdirs() }

    /**
     * 执行一次打包。调用方需保证：SDK 已装、工具链已装、项目已编译（有 rpyc）。
     * 本函数阻塞式跑在调用方的 IO 协程里。
     */
    suspend fun build(
        context: Context,
        project: ProjectInfo,
        config: BuildConfig,
        onLog: (String) -> Unit,
        onProgress: (stage: Stage, percent: Int) -> Unit,
    ): BuildResult = withContext(Dispatchers.IO) {
        try {
            if (!BuildToolchainInstaller.isInstalled()) {
                return@withContext BuildResult(false, t("打包工具链未安装，请先在设置中安装"))
            }
            if (!TermuxBootstrap.isInstalled(context)) {
                return@withContext BuildResult(false, t("Termux 环境未安装"))
            }
            val sdkBase = EngineBridge.sdkBase(context)
                ?: return@withContext BuildResult(false, t("未找到 Ren'Py SDK"))
            val raptBase = findRaptDir(context)
                ?: return@withContext BuildResult(false, t("未找到 RAPT（rapt.zip 未安装或解压异常）"))
            if (!File(raptBase, "buildlib/rapt/build.py").exists()) {
                return@withContext BuildResult(false, t("RAPT 目录不完整：缺少 buildlib/rapt/build.py"))
            }

            // ── 打包缓存：配置 + 图标/启动图 + 游戏内容 + SDK 全一致则直接复用上次 APK ──
            // 命中时跳过整个 Gradle 构建（分钟级），这是提速的关键路径。
            val fp = ApkCache.fingerprint(project, config, sdkBase.absolutePath)
            val cached = ApkCache.hit(project, fp)
            if (cached != null) {
                onProgress(Stage.PREPARE, 50)
                onLog(t("打包配置与游戏内容均未变化，使用缓存 APK，跳过 Gradle 构建"))
                onProgress(Stage.FINISH, 100)
                onLog("打包完成（缓存）：${cached.name}（${cached.length() / 1024 / 1024} MB）")
                return@withContext BuildResult(true, "打包完成（缓存）：${cached.name}", cached.absolutePath)
            }
            onLog(t("未命中打包缓存（配置或游戏内容有变化），开始完整打包"))
            // 进入完整打包即视为缓存已过期；失败时不写新缓存，下次仍会重打。
            ApkCache.invalidate(project)

            val work = workDir(context)
            val dist = File(work, "dist")
            val logFile = File(work, "build.log")
            logFile.writeText("")
            val apkOut = File(project.path, "apk-out").apply { mkdirs() }

            onProgress(Stage.PREPARE, 5)
            onLog("打包目录：${work.absolutePath}")

            // ── 1) 组装 distribution ──
            onProgress(Stage.DISTRIBUTION, 15)
            assembleDistribution(sdkBase, project, dist, onLog)

            // ── 1b) 写入自定义图标/启动图资源（RAPT 从 dist 根目录读取）──
            if (config.iconPath.isNotEmpty() || config.splashPath.isNotEmpty()) {
                onLog(t("正在写入自定义图标与启动图…"))
                ApkAssets.applyToDistribution(
                    ApkAssets.Config(iconPath = config.iconPath, splashPath = config.splashPath),
                    dist,
                )
            }

            // ── 2) android.json（RAPT 从 distribution 目录读取，必须写到 dist）──
            onProgress(Stage.CONFIGURE, 25)
            writeAndroidJson(dist, config)
            // 同时写一份到项目根，供下次打包回显配置
            writeAndroidJson(File(project.path), config)

            // ── 2b) 把 APK 内置的 Java 信任库解到工作目录 ──
            // 驱动脚本会把它复制到 JDK 的 lib/security/cacerts（纯文件复制，秒级），
            // 避免运行时调用 keytool 逐张导入证书（在部分 ROM 上启动 JVM 会死锁/极慢）。
            val cacertsFile = File(work, "java-cacerts.jks")
            try {
                context.assets.open("java-cacerts.jks").use { input ->
                    cacertsFile.outputStream().use { out -> input.copyTo(out) }
                }
                onLog("已就绪 Java 信任库（${cacertsFile.length() / 1024} KB）")
            } catch (e: Exception) {
                onLog("警告：内置信任库读取失败（${e.message}）")
            }

            // ── 2c) 把 APK 内置的真实 Android platform 元数据解到工作目录 ──
            // AGP 判定 platform 是否安装靠 build.prop / source.properties / package.xml
            // 等元数据。设备端 SDK 是手动拼的桩，缺这些文件 → "Build properties not found"
            // / "Failed to find target with hash string 'android-36'"。这里把从真实 SDK
            // 提取的元数据（架构无关的文本 + 纯 Java jar）解出来，驱动脚本覆盖到 android-36。
            val platformMeta = File(work, "android-platform-meta.tar")
            try {
                context.assets.open("android-platform-meta.tar").use { input ->
                    platformMeta.outputStream().use { out -> input.copyTo(out) }
                }
                onLog("已就绪 Android platform 元数据（${platformMeta.length() / 1024} KB）")
            } catch (e: Exception) {
                onLog("警告：platform 元数据读取失败（${e.message}）")
            }

            // ── 2d) 把 APK 内置的 build-tools 骨架解到工作目录 ──
            // AGP 的 BuildToolInfo.isValid() 会检查 build-tools/<rev>/ 下一整套工具文件
            // 是否存在（aapt/aidl/zipalign/lld/*-ld/core-lambda-stubs.jar 等），缺任一即
            // 判定 "Build Tools revision corrupted"。设备端只有 arm64 aapt2，这里补一套
            // 真实路径骨架（纯 Java jar 用真品，x86_64 大二进制用占位——只要求 exists）。
            val buildToolsSkeleton = File(work, "android-buildtools-35.tar")
            try {
                context.assets.open("android-buildtools-35.tar").use { input ->
                    buildToolsSkeleton.outputStream().use { out -> input.copyTo(out) }
                }
                onLog("已就绪 build-tools 骨架（${buildToolsSkeleton.length() / 1024} KB）")
            } catch (e: Exception) {
                onLog("警告：build-tools 骨架读取失败（${e.message}）")
            }

            // ── 3) 生成驱动脚本 ──
            onProgress(Stage.KEYSTORE, 35)
            val script = buildDriverScript(
                context = context,
                raptBase = raptBase,
                sdkBase = sdkBase,
                project = project,
                dist = dist,
                config = config,
                apkOut = apkOut,
                logFile = logFile,
                cacertsFile = cacertsFile,
                platformMetaFile = platformMeta,
                buildToolsFile = buildToolsSkeleton,
            )
            val scriptFile = File(work, "run_build.py")
            scriptFile.writeText(script)
            onLog(t("已生成打包驱动脚本"))

            // ── 4) 在 Termux 执行 ──
            onProgress(Stage.GENERATE_PROJECT, 45)
            val prefix = TermuxBootstrap.prefixDir(context).absolutePath
            val py = "$prefix/bin/python"
            val command = "cd '$prefix/bin' && $py '${scriptFile.absolutePath}' >> '${logFile.absolutePath}' 2>&1"

            onLog(t("开始运行打包（日志：build.log）…"))
            val exitCode = runTermuxCommand(context, command, logFile, onLog, onProgress)

            onProgress(Stage.FINISH, 95)
            val tail = runCatching { if (logFile.exists()) logFile.readText().takeLast(3000) else "" }.getOrDefault("")

            // 找产出 APK
            val apks = apkOut.walkTopDown().filter { it.isFile && it.name.endsWith(".apk", true) }.toList()
            val ok = exitCode == 0 && apks.isNotEmpty()
            if (ok) {
                val apk = apks.maxByOrNull { it.lastModified() }!!
                onProgress(Stage.FINISH, 100)
                // 打包成功：写入缓存，下次配置/内容不变即可秒级复用
                val cacheApk = ApkCache.save(project, fp, apk)
                if (cacheApk != null) onLog(t("已写入打包缓存，下次无变化可直接复用"))
                onLog("打包成功：${apk.name}（${apk.length() / 1024 / 1024} MB）")
                BuildResult(true, "打包成功：${apk.name}", apk.absolutePath)
            } else {
                val msg = if (exitCode != 0) "打包失败（退出码 $exitCode），请查看 build.log" else t("打包结束但未找到 APK，请查看 build.log")
                onLog(msg)
                onLog("--- 日志尾部 ---\n$tail")
                BuildResult(false, msg)
            }
        } catch (e: Exception) {
            onLog("打包异常：${e.message}")
            BuildResult(false, "打包异常：${e.message ?: "未知错误"}")
        }
    }

    /** 组装 Ren'Py distribution：game/（项目） + renpy/ + lib/（SDK） + main.py。 */
    private fun assembleDistribution(sdkBase: File, project: ProjectInfo, dist: File, onLog: (String) -> Unit) {
        if (dist.exists()) dist.deleteRecursively()
        dist.mkdirs()

        // game/：直接复制项目的 game 目录（含编译后的 rpyc + 资源）
        val srcGame = File(project.path, "game")
        if (srcGame.exists()) {
            srcGame.copyRecursively(File(dist, "game"), overwrite = true)
            onLog(t("已复制游戏脚本与资源：game/"))
        } else {
            onLog(t("警告：项目 game/ 目录不存在"))
        }

        // renpy/ 与 lib/：从 SDK 复制
        val sdkRenpy = File(sdkBase, "renpy")
        if (sdkRenpy.exists()) {
            sdkRenpy.copyRecursively(File(dist, "renpy"), overwrite = true)
            onLog(t("已复制 Ren'Py 运行时：renpy/"))
        }
        val sdkLib = File(sdkBase, "lib")
        if (sdkLib.exists()) {
            sdkLib.copyRecursively(File(dist, "lib"), overwrite = true)
            onLog(t("已复制 Python 运行时：lib/"))
        }

        // main.py：用 SDK 的 renpy.py 作为标准入口
        val renpyPy = File(sdkBase, "renpy.py")
        if (renpyPy.exists()) {
            renpyPy.copyTo(File(dist, "main.py"), overwrite = true)
        }
    }

    private fun writeAndroidJson(dir: File, config: BuildConfig) {
        val json = buildString {
            append("{\n")
            append("  \"package\": \"${config.packageName.replace("\\", "\\\\").replace("\"", "\\\"")}\",\n")
            append("  \"name\": \"${config.appName.replace("\\", "\\\\").replace("\"", "\\\"")}\",\n")
            append("  \"icon_name\": \"${config.iconName.replace("\\", "\\\\").replace("\"", "\\\"")}\",\n")
            append("  \"version\": \"${config.version.replace("\"", "\\\"")}\",\n")
            append("  \"numeric_version\": 1,\n")
            append("  \"orientation\": \"${config.orientation}\",\n")
            append("  \"permissions\": [\"VIBRATE\", \"INTERNET\"],\n")
            append("  \"store\": \"none\",\n")
            // 有自定义图标时启用 iconmaker：驱动脚本注入了 pygame stub，
            // iconmaker 用我们预生成的前景/背景 png 生成各密度 mipmap。
            append("  \"update_icons\": ${config.iconPath.isNotEmpty()},\n")
            append("  \"update_always\": true,\n")
            append("  \"update_keystores\": true\n")
            append("}\n")
        }
        File(dir, "android.json").writeText(json)
    }

    /**
     * 生成 Termux Python 驱动脚本。
     *
     * 关键适配：
     *  - 把 RAPT 的 buildlib 加入 sys.path，import rapt.*
     *  - 覆盖 plat.java/javac/keytool/sdk/gradlew 为 Termux 实际路径
     *  - 自定义 Interface.call：拦截 gradlew，注入 arm64 aapt2 override 与国内 Maven 镜像
     *  - 用 keytool 生成 android.keystore / bundle.keystore
     *  - 调 rapt.build.build() 产出 APK
     */
    private fun buildDriverScript(
        context: Context,
        raptBase: File,
        sdkBase: File,
        project: ProjectInfo,
        dist: File,
        config: BuildConfig,
        apkOut: File,
        logFile: File,
        cacertsFile: File,
        platformMetaFile: File,
        buildToolsFile: File,
    ): String {
        val prefix = TermuxBootstrap.prefixDir(context).absolutePath
        val home = TermuxBootstrap.homeDir(context).absolutePath
        // Gradle Wrapper 的 user home 必须指向 App 私有可写目录。Java 的 user.home
        // 在 Termux 上被编译期硬编码为 /data/data/com.termux/files/home，不跟随 $HOME，
        // 不显式指定 GRADLE_USER_HOME 就会写到无权限路径 → 创建 .lck 失败。
        val gradleHome = File(home, ".gradle").absolutePath
        val javaHome = BuildToolchainInstaller.javaHome()
        val aapt2 = BuildToolchainInstaller.aapt2Path()
        val androidSdk = BuildToolchainInstaller.androidSdkDir().absolutePath
        // RAPT 官方用 <rapt>/project/gradlew（由 copy_project 从 prototype 复制生成）
        val gradlew = File(raptBase, "project/gradlew").absolutePath
        val base = project.path // android.keystore 等放项目根
        val version = config.version

        // 用 Python 单引号字符串注入路径（Android 路径无单引号，转义反斜杠即可）。
        fun pyq(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
        return """
import os, sys, subprocess, time, shutil, glob

def log(msg):
    print("[apk] " + str(msg), flush=True)

RAPT        = __RAPT__
DIST        = __DIST__
BASE        = __BASE__
APKOUT      = __APKOUT__
JAVA_HOME   = __JAVA_HOME__
AAPT2       = __AAPT2__
ANDROID_SDK = __ANDROID_SDK__
GRADLEW     = __GRADLEW__
VERSION     = __VERSION__
TERMUX_SH   = __TERMUX_SH__
PREFIX      = __PREFIX__
HOME_DIR    = __HOME_DIR__
GRADLE_HOME = __GRADLE_HOME__
CACERTS_ASSET = __CACERTS_ASSET__
PLATFORM_META = __PLATFORM_META__
BUILDTOOLS   = __BUILDTOOLS__
LOGFILE     = __LOGFILE__

os.environ["JAVA_HOME"] = JAVA_HOME
os.environ["ANDROID_HOME"] = ANDROID_SDK
os.environ["ANDROID_SDK_ROOT"] = ANDROID_SDK
os.environ["PATH"] = JAVA_HOME + "/bin:" + os.environ.get("PATH", "")
os.environ["RAPT_NO_TERMS"] = "1"
# AGP 8.x 用 ANDROID_USER_HOME 定位 .android（analytics.settings 等用户配置）。
# 不设置则回退到 JVM user.home/.android = /data/data/com.termux/files/home/.android（无权限写）。
os.environ["ANDROID_USER_HOME"] = os.path.join(HOME_DIR, ".android")
os.makedirs(os.environ["ANDROID_USER_HOME"], exist_ok=True)
# curl 在私有 prefix 下硬编码了 com.termux 的 CA 路径，显式指向真实 bundle。
_ca = os.path.join(PREFIX, "etc", "tls", "cert.pem")
if os.path.isfile(_ca):
    os.environ["SSL_CERT_FILE"] = _ca
    os.environ["CURL_CA_BUNDLE"] = _ca

# 关键：Gradle Wrapper 会通过 user.home/.gradle 找缓存和下载 distribution——
# Java 的 user.home 在 Termux 上硬编码为 /data/data/com.termux/files/home（本 App 无权限写），
# 导致 "Could not create parent directory for lock file .../.gradle/wrapper/dists/...zip.lck"。
# 这里三重保险：
#  1) 显式设置 GRADLE_USER_HOME 到 App 私有可写目录；
#  2) GRADLE_OPTS 覆盖 JVM 的 user.home / java.io.tmpdir（gradlew 会把它传给 gradle JVM）；
#  3) 预创建目录（含 wrapper/dists、daemon、caches、native），避免首次写入失败。
os.environ["HOME"] = HOME_DIR
os.environ["GRADLE_USER_HOME"] = GRADLE_HOME
os.environ["GRADLE_OPTS"] = os.environ.get("GRADLE_OPTS", "") + (
    " -Duser.home=" + HOME_DIR +
    " -Djava.io.tmpdir=" + HOME_DIR + "/.tmp" +
    " -Djavax.net.ssl.trustStore=" + JAVA_HOME + "/lib/security/cacerts" +
    " -Djavax.net.ssl.trustStorePassword=changeit" +
    " -Dandroid.builder.sdkDownload=false"
)
for _d in ("wrapper/dists", "daemon", "caches", "native"):
    os.makedirs(os.path.join(GRADLE_HOME, _d), exist_ok=True)
os.makedirs(os.path.join(HOME_DIR, ".tmp"), exist_ok=True)

# ===== 修复 Java cacerts 信任库（纯文件复制，零 keytool 调用）=====
# 工具链通过 apt-get -d + 手动 dpkg-deb 解压安装，跳过了 ca-certificates 的
# postinst，导致 ${'$'}JAVA_HOME/lib/security/cacerts 为空 → HTTPS 报 trustAnchors empty。
# APK 内置了一个完整的 cacerts（154 个根证书），这里直接复制过去即可，秒级完成。
# 绝不能在打包时调用 keytool 逐张导入：那会启动上百个 JVM，在部分 ROM 上死锁/极慢。
def install_cacerts():
    dst = os.path.join(JAVA_HOME, "lib", "security", "cacerts")
    # 目标已非空则跳过（幂等）
    try:
        if os.path.isfile(dst) and os.path.getsize(dst) > 10000:
            log("cacerts already present (" + str(os.path.getsize(dst)) + " bytes)")
            return
    except Exception:
        pass
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    # 若目标是符号链接（Termux 常把 cacerts 链到 ca-certificates 目录），先删链接，
    # 否则 shutil.copy 会跟随链接写到可能无权限的目标路径。
    try:
        if os.path.islink(dst):
            os.remove(dst)
    except Exception:
        pass
    srcs = []
    if CACERTS_ASSET and os.path.isfile(CACERTS_ASSET):
        srcs.append(CACERTS_ASSET)
    srcs += [os.path.join(PREFIX, "etc", "ssl", "certs", "java", "cacerts"),
             os.path.join(PREFIX, "etc", "ssl", "certs", "cacerts")]
    for src in srcs:
        try:
            if os.path.isfile(src) and os.path.getsize(src) > 10000:
                shutil.copy(src, dst)
                log("cacerts installed from " + src + " (" + str(os.path.getsize(dst)) + " bytes)")
                return
        except Exception as e:
            log("WARN cacerts copy failed: " + str(e))
    log("WARN no usable cacerts source")

try:
    install_cacerts()
except Exception as e:
    log("WARN install_cacerts error: " + str(e))

# ===== 预置 Gradle distribution（绕开 Java 下载器）=====
# services.gradle.org 在国内常断流（Software caused connection abort），Java 下载器
# 不支持断点续传。这里用 curl（支持 -C - 续传 + 重试）从腾讯云镜像下载 9.1.0，
# 解压到 wrapper 缓存目录并写 .ok 标记；gradlew 检测到缓存就绪会直接跳过下载。
# 缓存 hash = base36(MD5(distributionUrl))，services.gradle.org 官方 URL 对应 9agqghryom9wkf8r80qlhnts3。
GRADLE_DIST_VER = "9.1.0"
GRADLE_DIST_HASH = "9agqghryom9wkf8r80qlhnts3"
GRADLE_MIRRORS = [
    "https://mirrors.cloud.tencent.com/gradle/gradle-9.1.0-bin.zip",
    "https://services.gradle.org/distributions/gradle-9.1.0-bin.zip",
]

def _gradle_ready():
    base = os.path.join(GRADLE_HOME, "wrapper", "dists", "gradle-9.1.0-bin", GRADLE_DIST_HASH)
    gradle_bin = os.path.join(base, "gradle-9.1.0", "bin", "gradle")
    ok = os.path.join(base, "gradle-9.1.0-bin.zip.ok")
    return os.path.isfile(gradle_bin) and os.path.isfile(ok)

def ensure_gradle_dist():
    if _gradle_ready():
        log("gradle dist cached, skip download")
        return True
    base = os.path.join(GRADLE_HOME, "wrapper", "dists", "gradle-9.1.0-bin", GRADLE_DIST_HASH)
    os.makedirs(base, exist_ok=True)
    zpath = os.path.join(base, "gradle-9.1.0-bin.zip")
    curl = os.path.join(PREFIX, "bin", "curl")
    if not os.path.isfile(curl):
        curl = "curl"
    # curl 断点续传 + 多次重试；每个镜像试 3 轮，校验 zip 完整性。
    # curl 的进度直接写 logFile（构建日志实时可见），避免用户误以为卡住。
    log("gradle dist downloading ~130MB (first run only, may take a few minutes)...")
    for mirror in GRADLE_MIRRORS:
        for attempt in range(3):
            try:
                with open(LOGFILE, "a") as lf:
                    subprocess.run(
                        [curl, "-fL", "-C", "-", "--retry", "3", "--retry-delay", "2",
                         "--connect-timeout", "20", "--max-time", "900", "-sS",
                         "-o", zpath, mirror],
                        stdout=lf, stderr=subprocess.STDOUT, timeout=960,
                    )
            except Exception as e:
                log("gradle download error: " + str(e)[:120])
                continue
            # 校验：zip 能列出且含 gradle bin
            good = False
            try:
                import zipfile
                with zipfile.ZipFile(zpath) as zf:
                    good = ("gradle-9.1.0/bin/gradle" in zf.namelist())
            except Exception:
                good = False
            if good:
                log("gradle dist downloaded from " + mirror)
                # 解压到 base（zip 顶层就是 gradle-9.1.0/）
                try:
                    import zipfile
                    with zipfile.ZipFile(zpath) as zf:
                        zf.extractall(base)
                    gbin = os.path.join(base, "gradle-9.1.0", "bin", "gradle")
                    os.chmod(gbin, 0o755)
                    open(os.path.join(base, "gradle-9.1.0-bin.zip.ok"), "w").close()
                    log("gradle dist extracted & ready")
                    return True
                except Exception as e:
                    log("gradle extract error: " + str(e)[:120])
                    return False
            else:
                log("gradle zip invalid from " + mirror + ", retry " + str(attempt + 1))
    log("WARN gradle dist preload failed; gradlew will try itself")
    return False

try:
    ensure_gradle_dist()
except Exception as e:
    log("WARN ensure_gradle_dist error: " + str(e))

sys.path.insert(0, os.path.join(RAPT, "buildlib"))
sys.path.insert(0, RAPT)

# RAPT 的 iconmaker 顶层 import renpy.pygame。设备端没有真 pygame，
# 注入 stub：iconmaker 只用 display 的几个空操作 + 预生成的 android-icon-*.png，
# write_icon 检测到这些文件会直接 shutil.copy，不走 pygame.image.load/transform。
try:
    import renpy
    import renpy.pygame
except ImportError:
    import types
    _renpy = sys.modules.get("renpy") or types.ModuleType("renpy")
    _pyg = sys.modules.get("renpy.pygame") or types.ModuleType("renpy.pygame")
    for _n in ("Surface", "Color", "Rect"):
        setattr(_pyg, _n, type(_n, (), {})())
    _pyg.BLEND_RGBA_MULT = 0
    _display = types.ModuleType("renpy.pygame.display")
    _display.get_surface = lambda: None
    _display.init = lambda: None
    _display.hint = lambda *a, **k: None
    _display.set_mode = lambda *a, **k: None
    _display.quit = lambda: None
    _event = types.ModuleType("renpy.pygame.event")
    _event.pump = lambda: None
    _pyg.display = _display
    _pyg.event = _event
    _renpy.pygame = _pyg
    sys.modules["renpy"] = _renpy
    sys.modules["renpy.pygame"] = _pyg
    sys.modules["renpy.pygame.display"] = _display
    sys.modules["renpy.pygame.event"] = _event

import rapt.plat as plat
import rapt.build as rbuild
import rapt.interface as riface

plat.java    = os.path.join(JAVA_HOME, "bin", "java")
plat.javac   = os.path.join(JAVA_HOME, "bin", "javac")
plat.keytool = os.path.join(JAVA_HOME, "bin", "keytool")
plat.sdk     = ANDROID_SDK
plat.adb     = os.path.join(ANDROID_SDK, "platform-tools", "adb")
plat.gradlew = GRADLEW

class BuilderInterface(riface.Interface):
    def info(self, prompt):
        log("INFO " + str(prompt).replace("\n", " ")[:200])
    def success(self, prompt):
        log("OK " + str(prompt).replace("\n", " ")[:200])
    def final_success(self, prompt):
        log("SUCCESS " + str(prompt).replace("\n", " ")[:200])
    def fail(self, prompt):
        log("FAIL " + str(prompt).replace("\n", " ")[:300])
        raise RuntimeError(str(prompt))
    def background(self, f):
        f()
    def terms(self, url, prompt):
        log("terms: " + url)
    def yesno(self, prompt):
        return True
    def say(self, prompt):
        log(str(prompt)[:200])
    def call(self, args, cancel=False, use_path=False, yes=False):
        args = [str(a) for a in args]
        if args and "gradlew" in args[0]:
            self._patch_gradle()
            # SDK 解压到设备时 prototype/gradlew 丢失了可执行位，copytree 复制后仍是 644，
            # 直接执行会 PermissionError。双保险：chmod +x，并改用 `sh gradlew ...` 显式调用，
            # 绕开 shebang 里可能存在的硬编码 /data/data/com.termux 路径。
            gpath = args[0]
            try:
                os.chmod(gpath, 0o755)
            except OSError:
                pass
            args = [TERMUX_SH, gpath] + args[1:]
        log("RUN " + " ".join(args)[:200])
        subprocess.check_call(args, cwd=plat.RAPT_PATH)
    def _patch_gradle(self):
        proj = plat.path("project")
        gp = os.path.join(proj, "gradle.properties")
        lp = os.path.join(proj, "local.properties")
        with open(gp, "a") as f:
            f.write("\nandroid.aapt2FromMavenOverride=" + AAPT2 + "\n")
            f.write("org.gradle.daemon=false\n")
            f.write("org.gradle.jvmargs=-Xmx1536m\n")
            # 禁止 AGP 用 sdkmanager 联网下载 SDK 组件（设备端无 Google 网络/权限）。
            f.write("android.builder.sdkDownload=false\n")
        with open(lp, "w") as f:
            f.write("sdk.dir=" + ANDROID_SDK.replace("\\", "/") + "\n")
            f.write("key.alias=android\n")
            f.write("key.store.password=android\n")
            f.write("key.alias.password=android\n")
            f.write("key.store=" + os.path.join(BASE, "android.keystore").replace("\\", "/") + "\n")
        self._prepare_sdk()

    def _prepare_sdk(self):
        # AGP 通过 ${'$'}SDK/licenses/* 判断是否接受 license、通过各包的 source.properties
        # 判断包是否已安装。我们的 SDK 是手动拼的桩，缺这些元数据 → AGP 误判组件缺失，
        # 触发 sdkmanager 自动安装 → 因 license 未接受而 FAIL。这里补全元数据，
        # 让 AGP 认为 platform 36 / build-tools 35+36 已安装且 license 已接受。
        sdk = ANDROID_SDK
        lic_dir = os.path.join(sdk, "licenses")
        os.makedirs(lic_dir, exist_ok=True)
        licenses = {
            "android-sdk-license": [
                "8933bad161af4178b1185d1a37fbf41ea5269c55",
                "d56f5187479451eabf01fb78af6dfcb131a6481e",
                "24333f8a63b6825ea9c5514f83c2829b004d1fee",
            ],
            "android-sdk-preview-license": ["84831b9409646a918e30573bab4c9c91346d8abd"],
            "android-gdk-license": ["33b6a2b1del07b803d59cf3d79e37abc6132eb2b"],
            "intel-android-extra-license": ["d975f751698a77b662f1254ddbeed3901e976f5a"],
            "android-googletv-license": ["601085b94cd77f0b54ff86406957099ebe79c4d6"],
        }
        for name, hashes in licenses.items():
            p = os.path.join(lic_dir, name)
            existing = ""
            try:
                if os.path.isfile(p):
                    existing = open(p, errors="ignore").read()
            except Exception:
                pass
            missing = [h for h in hashes if h not in existing]
            if missing:
                try:
                    with open(p, "a") as f:
                        for h in missing:
                            f.write("\n" + h + "\n")
                except Exception as e:
                    log("WARN write license " + name + ": " + str(e)[:100])

        # platform android-36 元数据：
        # 先从 APK 内置的真实 SDK 元数据 tar 解压覆盖（build.prop / source.properties /
        # package.xml / framework.aidl / core-for-system-modules.jar 等），这是 AGP 判定
        # "target android-36" 存在与 desugar 的真正依据。纯文本 + 纯 Java jar，架构无关。
        p36 = os.path.join(sdk, "platforms", "android-36")
        os.makedirs(p36, exist_ok=True)
        if PLATFORM_META and os.path.isfile(PLATFORM_META):
            try:
                import tarfile
                with tarfile.open(PLATFORM_META, "r:*") as tf:
                    for m in tf.getmembers():
                        # 只接受平铺文件名或 optional/ 下一层，杜绝路径穿越。
                        if m.name.startswith("/") or ".." in m.name.split("/"):
                            continue
                        if not (m.isfile() or m.isdir()):
                            continue
                        # 覆盖解压：真实元数据优先于设备上的桩文件。
                        tf.extract(m, path=p36)
                log("platform metadata extracted from asset")
            except Exception as e:
                log("WARN platform meta extract: " + str(e)[:120])
        # 兜底：若真实元数据没解出 source.properties，写一个最小可用版本。
        sp = os.path.join(p36, "source.properties")
        try:
            if not os.path.isfile(sp) or os.path.getsize(sp) < 40:
                with open(sp, "w") as f:
                    f.write("Pkg.Desc=Android SDK Platform 16\nPkg.UserSrc=false\n")
                    f.write("Platform.Version=16\nPkg.Revision=2\n")
                    f.write("AndroidVersion.ApiLevel=36\nAndroidVersion.ExtensionLevel=17\n")
                    f.write("AndroidVersion.IsBaseSdk=true\nLayoutlib.Api=15\n")
                    f.write("Layoutlib.Revision=1\nPlatform.MinToolsRev=22\n")
        except Exception as e:
            log("WARN platform source.properties: " + str(e)[:100])

        # build-tools：AGP 8.13 用 35.0.0。它的 isValid() 要求该目录下一整套工具文件
        # 都存在（aapt/aidl/zipalign/lld/*-ld/core-lambda-stubs.jar/lib/d8.jar ...），
        # 缺任一即 "Build Tools revision corrupted"。设备端只有 arm64 aapt2，这里解压
        # APK 内置的真实 build-tools 骨架覆盖（路径百分百正确，纯 Java jar 为真品，
        # x86_64 大二进制为占位——isValid 只查 exists；aapt2 已 override 到 arm64）。
        bt35 = os.path.join(sdk, "build-tools", "35.0.0")
        os.makedirs(bt35, exist_ok=True)
        if BUILDTOOLS and os.path.isfile(BUILDTOOLS):
            try:
                import tarfile
                with tarfile.open(BUILDTOOLS, "r:*") as tf:
                    for m in tf.getmembers():
                        n = m.name[2:] if m.name.startswith("./") else m.name
                        if not n or n.startswith("/") or ".." in n.split("/"):
                            continue
                        if not (m.isfile() or m.isdir()):
                            continue
                        tf.extract(m, path=bt35)
                log("build-tools skeleton extracted (35.0.0)")
            except Exception as e:
                log("WARN build-tools extract: " + str(e)[:120])
        # 确保 aapt2 是真品（arm64，来自 prefix/bin）——override 指向它，且占位可能覆盖过。
        for tool in ("aapt2",):
            src = os.path.join(PREFIX, "bin", tool)
            if os.path.isfile(src) or os.path.islink(src):
                try:
                    shutil.copy2(src, os.path.join(bt35, tool))
                    os.chmod(os.path.join(bt35, tool), 0o755)
                except Exception:
                    pass
        # source.properties 用骨架自带的真实版本（revision 35.0.0）——覆盖兜底。
        sp = os.path.join(bt35, "source.properties")
        try:
            if not os.path.isfile(sp) or os.path.getsize(sp) < 20:
                with open(sp, "w") as f:
                    f.write("Pkg.UserSrc=false\nPkg.Revision=35.0.0\nPkg.Path=build-tools;35.0.0\n")
        except Exception as e:
            log("WARN bt35 source.properties: " + str(e)[:100])
        # 36.0.0 保持设备现状，仅补元数据（防止 AGP 偶尔选它）。
        bt36 = os.path.join(sdk, "build-tools", "36.0.0")
        os.makedirs(bt36, exist_ok=True)
        try:
            with open(os.path.join(bt36, "source.properties"), "w") as f:
                f.write("Pkg.UserSrc=false\nPkg.Revision=36.0.0\nPkg.Path=build-tools;36.0.0\n")
        except Exception:
            pass
        log("sdk metadata ready (licenses + platform 36 + build-tools 35/36)")

iface = BuilderInterface()
log("STAGE=CONFIGURE")
if not os.path.exists(os.path.join(BASE, "android.keystore")):
    log("gen keystore")
    subprocess.check_call([
        plat.keytool, "-genkeypair", "-keystore", os.path.join(BASE, "android.keystore"),
        "-alias", "android", "-keyalg", "RSA", "-keysize", "2048",
        "-keypass", "android", "-storepass", "android",
        "-dname", "CN=RenPyndIDE", "-validity", "20000",
    ])
if not os.path.exists(os.path.join(BASE, "bundle.keystore")):
    shutil.copy(os.path.join(BASE, "android.keystore"), os.path.join(BASE, "bundle.keystore"))
# 不要在这里调 rkeys.update_project_keys(BASE)：它要写 rapt/project/local.properties，
# 而 project/ 目录是 rbuild.build() 内部 copy_project() 才创建的。build() 自身在
# copy_project() 之后、update_keystores=true 时会自动调用它，我们提前调用只会 FileNotFoundError。

log("STAGE=BUILD")
rbuild.build(iface, DIST, BASE, install=False, bundle=False, launch=False, version=VERSION)

apk_dir = plat.path("project/app/build/outputs/apk/release")
for a in glob.glob(os.path.join(apk_dir, "*.apk")):
    shutil.copy(a, os.path.join(APKOUT, os.path.basename(a)))
    log("APK " + os.path.join(APKOUT, os.path.basename(a)))
log("DONE")
""".trimIndent()
            .replace("__RAPT__", pyq(raptBase.absolutePath))
            .replace("__DIST__", pyq(dist.absolutePath))
            .replace("__BASE__", pyq(base))
            .replace("__APKOUT__", pyq(apkOut.absolutePath))
            .replace("__JAVA_HOME__", pyq(javaHome))
            .replace("__AAPT2__", pyq(aapt2))
            .replace("__ANDROID_SDK__", pyq(androidSdk))
            .replace("__GRADLEW__", pyq(gradlew))
            .replace("__VERSION__", pyq(version))
            .replace("__TERMUX_SH__", pyq("$prefix/bin/sh"))
            .replace("__PREFIX__", pyq(prefix))
            .replace("__HOME_DIR__", pyq(home))
            .replace("__GRADLE_HOME__", pyq(gradleHome))
            .replace("__CACERTS_ASSET__", pyq(cacertsFile.absolutePath))
            .replace("__PLATFORM_META__", pyq(platformMetaFile.absolutePath))
            .replace("__BUILDTOOLS__", pyq(buildToolsFile.absolutePath))
            .replace("__LOGFILE__", pyq(logFile.absolutePath))
    }

    /** 定位 RAPT 目录：filesDir/sdk/8.6.1/rapt/。 */
    private fun findRaptDir(context: Context): File? {
        val root = File(context.filesDir, "sdk")
        return root.walkTopDown().firstOrNull { dir ->
            dir.name == "rapt" && File(dir, "buildlib").isDirectory && File(dir, "prototype").isDirectory
        }
    }

    /** 在后台执行 Termux 命令，实时把日志回传；返回退出码。 */
    private suspend fun runTermuxCommand(
        context: Context,
        command: String,
        logFile: File,
        onLog: (String) -> Unit,
        onProgress: (Stage, Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val mgr = TermuxSessionManager(context)
        // execBackground 阻塞；在后台协程执行，同时本协程轮询日志推进度。
        var exitCode = -1
        val buildJob = launch(Dispatchers.IO) {
            exitCode = try { mgr.execBackground(command, logFile, append = true) } catch (e: Exception) {
                onLog("命令执行异常：${e.message}"); -1
            }
        }
        var lastLen = if (logFile.exists()) logFile.length().toInt() else 0
        var pct = 45
        while (buildJob.isActive) {
            val text = if (logFile.exists()) runCatching { logFile.readText() }.getOrDefault("") else ""
            if (text.length > lastLen) {
                val fresh = text.substring(lastLen)
                lastLen = text.length
                fresh.lineSequence().forEach { line: String ->
                    val l = line.trim()
                    if (l.isBlank()) return@forEach
                    if (COMPILE_LINE.containsMatchIn(l) || l.startsWith("[apk]")) onLog(l.take(280))
                    when {
                        l.contains("STAGE=CONFIGURE", true) -> { pct = 55; onProgress(Stage.GENERATE_PROJECT, 55) }
                        l.contains("STAGE=BUILD", true) -> { pct = 70; onProgress(Stage.BUILD_APK, 70) }
                        l.contains("BUILD SUCCESSFUL", true) -> { pct = 90; onProgress(Stage.FINISH, 90) }
                    }
                }
            }
            kotlinx.coroutines.delay(400)
        }
        buildJob.join()
        // 收尾日志
        val finalText = if (logFile.exists()) runCatching { logFile.readText() }.getOrDefault("") else ""
        if (finalText.length > lastLen) {
            finalText.substring(lastLen).lineSequence().forEach { line: String ->
                val l = line.trim()
                if (l.isNotBlank() && (COMPILE_LINE.containsMatchIn(l) || l.startsWith("[apk]"))) onLog(l.take(280))
            }
        }
        exitCode
    }

}
