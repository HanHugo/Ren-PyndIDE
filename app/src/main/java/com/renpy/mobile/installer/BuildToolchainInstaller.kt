package com.renpy.mobile.installer

import android.content.Context
import android.content.SharedPreferences
import com.renpy.mobile.termux.TermuxBootstrap
import com.renpy.mobile.termux.TermuxSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.renpy.mobile.i18n.t

/**
 * 打包工具链安装器。
 *
 * 设备端打 APK 需要一整套工具链（ Termux arm64 原生包 + Android platform 桩）：
 *  - openjdk-21        : javac / java / keytool（RAPT 的 gradle + AGP 8.13 要求 JDK 21）
 *  - aapt2             : arm64 原生资源编译器（替换 Google x86_64 版）
 *  - apksigner / d8    : 备用手工签名/dex 工具
 *  - python + jinja2   : 驱动 RAPT build.py（前半段是纯 Python）
 *  - android.jar       : Android API 桩（从 Maven 下载，API 36）
 *  - framework-res.apk : 系统资源定义（aapt2 link 需要）
 *
 * 全部通过 Termux 用户态安装（[TermuxBootstrap] 必须先装好）。安装/校验走后台
 * 一次性 bash 会话，输出重定向到日志，UI 通过 [state] 观察阶段与进度，通过
 * 读取日志文件展示实时输出。
 */
object BuildToolchainInstaller {

    private const val PREFS = "build_toolchain_prefs"
    private const val KEY_DONE = "toolchain_done"
    private const val KEY_VERSION = "toolchain_version"
    private const val TOOLCHAIN_VERSION = "1" // 工具链布局版本，变更时强制重装

    /** Termux 专用的 Android SDK 桩 tarball（含 android.jar + framework-res.apk，约 43MB）。 */
    private const val SDK_STUBS_URL =
        "https://github.com/flipphoneguy/app-template/releases/latest/download/android-sdk-termux.tar.gz"

    enum class Phase {
        IDLE, NEED_BOOTSTRAP, INSTALLING, DONE, FAILED
    }

    /** 单个工具组件的进度状态。 */
    enum class StepStatus { PENDING, RUNNING, DONE, FAILED }

    data class StepProgress(val key: String, val label: String, val status: StepStatus)

    data class State(
        val phase: Phase = Phase.IDLE,
        val stage: String = "",
        val logTail: String = "",
        val error: String? = null,
        val steps: List<StepProgress> = defaultSteps(),
    )

    /** 分段进度定义：顺序与安装脚本一致。 */
    private val STEP_LABELS = listOf(
        "jdk" to "JDK 21",
        "aapt2" to "aapt2",
        "apksigner" to "apksigner",
        "python" to "Python",
        "gradle" to "Gradle",
        "sdk" to "Android API",
    )

    private fun defaultSteps() = STEP_LABELS.map { StepProgress(it.first, it.second, StepStatus.PENDING) }

    /** 步骤状态文件目录（每段一个文件，内容 RUN/DONE/FAIL）。 */
    private fun stepDir() = File(toolDir(), ".steps")

    /** 从状态文件读取每段进度（不受日志截断影响）。 */
    private fun readSteps(): List<StepProgress> {
        val dir = stepDir()
        return STEP_LABELS.map { (key, label) ->
            val raw = runCatching { File(dir, key).readText().trim() }.getOrDefault("")
            val status = when (raw) {
                "RUN" -> StepStatus.RUNNING
                "DONE" -> StepStatus.DONE
                "FAIL" -> StepStatus.FAILED
                else -> StepStatus.PENDING
            }
            StepProgress(key, label, status)
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var appCtx: Context? = null
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bind(context: Context) {
        if (appCtx == null) {
            appCtx = context.applicationContext
            prefs = appCtx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _state.value = if (isInstalled()) State(Phase.DONE, t("打包工具链已就绪"))
            else State(Phase.IDLE)
        }
    }

    private fun requireCtx(): Context = appCtx ?: throw IllegalStateException(t("BuildToolchainInstaller 未 bind"))

    private fun toolDir(): File = File(requireCtx().filesDir, "buildtools")

    /** SDK 桩目录（喂给 RAPT 的 plat.sdk / local.properties 的 sdk.dir）。 */
    fun androidSdkDir(): File = File(toolDir(), "android-sdk")

    /**
     * JDK HOME（Termux openjdk-21）。
     * 动态发现真实路径：Termux 在不同版本里 JVM 目录名可能是 java-21-openjdk /
     * java-21-openjdk-beta 等，硬编码容易失配。$prefix/bin/java 是始终存在的符号链接，
     * 通过它反解真实 HOME；失败再退回标准路径。
     */
    fun javaHome(): String {
        val prefix = TermuxBootstrap.prefixDir(requireCtx()).absolutePath
        val javaBin = File(prefix, "bin/java")
        if (javaBin.exists()) {
            val real = runCatching { javaBin.toPath().toRealPath().toFile() }.getOrNull()
            // bin/java -> <home>/bin/java，向上两级即 JAVA_HOME
            real?.parentFile?.parentFile?.let { home ->
                if (File(home, "bin/javac").exists()) return home.absolutePath
            }
        }
        return "$prefix/lib/jvm/java-21-openjdk"
    }

    /** arm64 aapt2 绝对路径。 */
    fun aapt2Path(): String = "${TermuxBootstrap.prefixDir(requireCtx()).absolutePath}/bin/aapt2"

    /**
     * 文件层面是否齐备——这是"是否已安装"的唯一真相，与 prefs 标志无关。
     * 判据与安装脚本末段 verify 完全一致：.installed 标记 + bin/java + bin/aapt2 + android.jar。
     * 注意不走 javaHome() 反解：它有硬编码 fallback，JVM 目录名带变体时会误判为未安装。
     */
    private fun filesReady(): Boolean {
        val prefix = TermuxBootstrap.prefixDir(requireCtx()).absolutePath
        return File(toolDir(), ".installed").exists() &&
            File(prefix, "bin/java").canExecute() &&
            File(prefix, "bin/aapt2").canExecute() &&
            File(androidSdkDir(), "platforms/android-36/android.jar").isFile
    }

    fun isInstalled(): Boolean {
        if (!filesReady()) return false
        // 文件齐备即视为已安装；prefs 标志缺失/版本旧时顺手自愈补齐。
        // 修复历史死锁：早期 install() 用 isInstalled() 当成功判定，而 isInstalled()
        // 又依赖 prefs 标志，标志只在"成功"分支写 → 永远写不进去 →
        // 明明装好了却每次判定未安装、反复重装。
        val done = prefs?.getBoolean(KEY_DONE, false) == true
        val verOk = prefs?.getString(KEY_VERSION, "") == TOOLCHAIN_VERSION
        if (!done || !verOk) {
            prefs?.edit()?.putBoolean(KEY_DONE, true)?.putString(KEY_VERSION, TOOLCHAIN_VERSION)?.apply()
        }
        return true
    }

    /** 日志文件（安装输出）。 */
    fun logFile(): File = File(toolDir(), "install.log")

    /**
     * 安装工具链。必须在 Termux bootstrap 已装好的前提下调用。
     * 通过后台 bash 会话执行，输出重定向到日志文件；结束时写 .installed 标记。
     */
    fun install(onFinished: (Boolean) -> Unit = {}) {
        if (isInstalled()) {
            _state.value = State(Phase.DONE, t("打包工具链已就绪"))
            onFinished(true)
            return
        }
        val ctx = requireCtx()
        if (!TermuxBootstrap.isInstalled(ctx)) {
            _state.value = State(Phase.NEED_BOOTSTRAP, t("请先安装 Termux 运行环境"), error = t("Termux bootstrap 未安装"))
            onFinished(false)
            return
        }
        // 旧 bootstrap 没有 apt.conf（新增机制前解压的），这里幂等补齐，避免 pkg install 因
        // apt/dpkg 硬编码 /data/data/com.termux 路径而失败。
        TermuxBootstrap.ensureAptConf(ctx)

        val dir = toolDir()
        dir.mkdirs()
        val sdk = androidSdkDir()
        val log = logFile()
        log.writeText("")
        File(dir, ".installed").delete()
        // 重置分段进度文件：清空旧状态，避免上一轮残留误导 UI
        stepDir().let { d -> if (d.exists()) d.deleteRecursively(); d.mkdirs() }

        // 把 APK 内置的 Java 信任库解到 toolDir，安装脚本直接复制到 JDK（纯文件复制，
        // 绝不调用 keytool 逐张导入——部分 ROM 上启动 JVM 会死锁/极慢）。
        val cacertsAsset = File(dir, "java-cacerts.jks")
        runCatching {
            ctx.assets.open("java-cacerts.jks").use { input ->
                cacertsAsset.outputStream().use { out -> input.copyTo(out) }
            }
        }

        // 构造安装脚本：所有命令输出追加到日志，任何一步失败即退出（set -e）。
        val script = buildInstallScript(prefix = TermuxBootstrap.prefixDir(ctx).absolutePath,
            home = TermuxBootstrap.homeDir(ctx).absolutePath, toolDir = dir.absolutePath,
            sdkDir = sdk.absolutePath, log = log.absolutePath,
            cacertsPath = cacertsAsset.absolutePath)

        _state.value = State(Phase.INSTALLING, t("正在安装打包工具链…"), steps = defaultSteps())

        val mgr = TermuxSessionManager(ctx)
        // execBackground 阻塞直到结束；放在 IO 协程里执行，同时起一个协程轮询日志更新 UI。
        scope.launch {
            val code = try {
                mgr.execBackground(script, log)
            } catch (e: Exception) {
                _state.value = State(Phase.FAILED, t("工具链安装失败"), error = e.message)
                onFinished(false)
                return@launch
            }
            // 成功判定只看脚本退出码 + 文件是否真的就位，不再依赖 prefs 标志，
            // 避免"标志未写→判定失败→标志更不会写"的死锁。
            val ok = code == 0 && filesReady()
            val finalSteps = readSteps()
            if (ok) {
                prefs?.edit()?.putBoolean(KEY_DONE, true)?.putString(KEY_VERSION, TOOLCHAIN_VERSION)?.apply()
                // 所有段标记为 DONE（含 verify 成功）
                _state.value = State(Phase.DONE, t("打包工具链安装完成"),
                    steps = finalSteps.map { it.copy(status = StepStatus.DONE) })
            } else {
                val tail = runCatching { if (log.exists()) log.readText().takeLast(1500) else "" }.getOrDefault("")
                _state.value = State(Phase.FAILED, "工具链安装失败（退出码 $code）", logTail = tail,
                    steps = finalSteps, error = t("请查看安装日志；通常是网络或软件源问题"))
            }
            onFinished(ok)
        }
        // 轮询日志尾部，实时更新阶段文案
        scope.launch {
            repeat(600) { // 最多 10 分钟
                val tail = withContext(Dispatchers.IO) {
                    runCatching { if (log.exists()) log.readText().takeLast(1200) else "" }.getOrDefault("")
                }
                if (_state.value.phase == Phase.INSTALLING) {
                    val steps = readSteps()
                    val running = steps.firstOrNull { it.status == StepStatus.RUNNING }
                    val stageText = running?.label?.let { "正在安装：$it" } ?: parseStage(tail)
                    _state.value = _state.value.copy(stage = stageText, logTail = tail, steps = steps)
                }
                if (_state.value.phase != Phase.INSTALLING) return@launch
                kotlinx.coroutines.delay(700)
            }
        }
    }

    /** 读取安装日志尾部（供 UI 主动刷新）。 */
    fun refreshLog() {
        val tail = runCatching { if (logFile().exists()) logFile().readText().takeLast(1200) else "" }.getOrDefault("")
        _state.value = _state.value.copy(logTail = tail)
    }

    private fun parseStage(tail: String): String {
        val l = tail.lowercase()
        return when {
            l.contains("openjdk") || l.contains("jdk") -> t("正在安装 JDK 21…")
            l.contains("aapt2") -> t("正在安装 aapt2 / apksigner…")
            l.contains("android.jar") || l.contains("android-36") -> t("正在下载 Android API 桩…")
            l.contains("jinja2") || l.contains("pip") -> t("正在安装 Python 依赖…")
            l.contains("installed") || l.contains("完成") -> t("正在收尾…")
            else -> t("正在安装打包工具链…")
        }
    }

    private fun buildInstallScript(prefix: String, home: String, toolDir: String, sdkDir: String, log: String, cacertsPath: String): String {
        val stubsUrl = SDK_STUBS_URL
        // 分段进度：每段写一个状态文件 stepDir()/<key> = RUN / DONE / FAIL，
        // UI 轮询这些文件得到每段的真实状态（不受日志 takeLast 截断影响）。
        return """
STEPDIR="$toolDir/.steps"
mark() { printf '%s' "${'$'}2" > "${'$'}STEPDIR/${'$'}1"; }
# Termux 官方 curl 硬编码了 /data/data/com.termux/... 的 CA 路径，
# 在本 App 私有 prefix 下会报 curl (77) trust anchors 错误。
export SSL_CERT_FILE="$prefix/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$prefix/etc/tls/cert.pem"
export REQUESTS_CA_BUNDLE="$prefix/etc/tls/cert.pem"
# apt 重定位（二进制把 com.termux 路径写死），让 apt 在私有 prefix 下读索引/缓存/状态库。
export TERMUX_PREFIX="$prefix"
export APT_CONFIG="$prefix/etc/apt/apt.conf"
export DPKG_ADMINDIR="$prefix/var/lib/dpkg"

ARCHIVES="$prefix/var/cache/apt/archives"

# ============ 关键：绝不使用 pkg / apt-get install 的解包环节 ============
# Termux 的每个 .deb 包内路径都是 ./data/data/com.termux/files/usr/...，
# dpkg install 时 instdir 默认 "/"，会把文件写到 /data/data/com.termux/files/usr/...
# ——那是另一个 App 的私有目录，本 App 无权限写入，解包必然失败（java/aapt2 永远 MISS）。
# 正确做法：
#   1) apt-get install -d（--download-only）只下载 .deb 及全部依赖，不触发 dpkg 解包；
#   2) dpkg-deb --fsys-tarfile 取出 tar 流，tar --strip-components=6 剥掉
#      "./data/data/com.termux/files/usr" 这 6 层，解压到私有 prefix，保留可执行位。
#   这样完全绕开 dpkg 的 instdir/admindir 硬编码路径，无需 root、无需写 com.termux。
unpack_archives() {
  local deb n=0
  for deb in "${'$'}ARCHIVES"/*.deb; do
    [ -f "${'$'}deb" ] || continue
    if dpkg-deb --fsys-tarfile "${'$'}deb" 2>/dev/null | tar -x --strip-components=6 -C "$prefix" 2>/dev/null; then
      n=${'$'}((n+1))
    fi
  done
  echo "    unpacked ${'$'}n deb(s) -> $prefix"
  # Termux 用 update-alternatives(postinst) 建 bin/ 下的 java/javac/keytool 等符号链接，
  # 手动解压不会执行 postinst，这里补建：把 lib/jvm/<home>/bin/ 下每个工具软链到 bin/。
  local jhome jbin tool
  for jhome in "$prefix"/lib/jvm/*; do
    [ -d "${'$'}jhome/bin" ] || continue
    for jbin in "${'$'}jhome"/bin/*; do
      [ -f "${'$'}jbin" ] || continue
      tool=${'$'}(basename "${'$'}jbin")
      [ -e "$prefix/bin/${'$'}tool" ] || ln -sf "${'$'}jbin" "$prefix/bin/${'$'}tool" 2>/dev/null || true
    done
  done
  # apksigner / aapt2 等包若在 bin 下放脚本则已是实体文件，无需额外链接。
}

echo "=== START toolchain install ==="
echo "[0/6] apt update"
# 清理上一轮遗留的 apt 索引（可能含未签名/半下载的 InRelease），用带 [trusted=yes] 的新源重拉。
rm -rf "$prefix/var/lib/apt/lists" 2>/dev/null || true
mkdir -p "$prefix/var/lib/apt/lists/partial" "${'$'}ARCHIVES/partial"
apt-get update 2>&1 || echo t("  WARN apt-get update 非零（继续尝试下载）")

echo "[1/6] JDK 21"
mark jdk RUN
if apt-get install -y -d openjdk-21 2>&1; then
  unpack_archives
  # 手动 dpkg-deb 解压跳过了 ca-certificates 的 postinst，cacerts 常为空 →
  # Java HTTPS 报 trustAnchors empty。APK 内置了完整 cacerts（154 根证书），
  # 直接复制到每个 JDK 的 lib/security/cacerts（纯文件复制，零 keytool 调用）。
  # 绝不能逐张 keytool 导入：部分 ROM 上启动 JVM 会死锁/极慢。
  for jhome in "$prefix"/lib/jvm/*; do
    [ -d "${'$'}jhome/bin" ] || continue
    jks="${'$'}jhome/lib/security/cacerts"
    mkdir -p "${'$'}(dirname "${'$'}jks")"
    [ -L "${'$'}jks" ] && rm -f "${'$'}jks" 2>/dev/null || true
    if [ -s "$cacertsPath" ]; then
      cp "$cacertsPath" "${'$'}jks" 2>/dev/null && echo "    cacerts installed: ${'$'}jhome" && continue
    fi
    for csrc in "$prefix/etc/ssl/certs/java/cacerts" "$prefix/etc/ssl/certs/cacerts"; do
      [ -s "${'$'}csrc" ] && cp "${'$'}csrc" "${'$'}jks" 2>/dev/null && break
    done
  done
  [ -x "$prefix/bin/java" ] && mark jdk DONE || { mark jdk FAIL; echo "  FAIL jdk"; }
else
  mark jdk FAIL; echo "  FAIL jdk"
fi

echo "[2/6] aapt2"
mark aapt2 RUN
if apt-get install -y -d aapt2 2>&1; then
  unpack_archives
  [ -x "$prefix/bin/aapt2" ] && mark aapt2 DONE || { mark aapt2 FAIL; echo "  FAIL aapt2"; }
else
  mark aapt2 FAIL; echo "  FAIL aapt2"
fi

echo "[3/6] apksigner"
mark apksigner RUN
if apt-get install -y -d apksigner 2>&1; then
  unpack_archives
  mark apksigner DONE
else
  mark apksigner FAIL; echo "  FAIL apksigner"
fi

echo "[4/6] Python + deps"
mark python RUN
if apt-get install -y -d python python-pip 2>&1; then
  unpack_archives
  python -m pip install --user --trusted-host pypi.org --trusted-host files.pythonhosted.org jinja2 requests 2>&1 || true
  mark python DONE
else
  mark python FAIL; echo "  FAIL python"
fi

echo "[5/6] Gradle"
mark gradle RUN
if apt-get install -y -d gradle zip unzip 2>&1; then
  unpack_archives
  mark gradle DONE
else
  mark gradle FAIL; echo "  FAIL gradle"
fi

echo "[6/6] Android API stubs"
mark sdk RUN
mkdir -p "$sdkDir/platforms/android-36" "$sdkDir/build-tools/36.0.0" "$sdkDir/platform-tools"
if [ ! -s "$sdkDir/platforms/android-36/android.jar" ]; then
  curl -fL --retry 3 -o "$toolDir/sdk-stubs.tar.gz" "$stubsUrl" || \
  curl -fkL --retry 3 -o "$toolDir/sdk-stubs.tar.gz" "$stubsUrl"
  mkdir -p "$toolDir/stubs"
  tar xzf "$toolDir/sdk-stubs.tar.gz" -C "$toolDir/stubs"
  find "$toolDir/stubs" -name android.jar -exec cp {} "$sdkDir/platforms/android-36/android.jar" \; 2>/dev/null || true
  find "$toolDir/stubs" -name framework-res.apk -exec cp {} "$sdkDir/platforms/android-36/framework-res.apk" \; 2>/dev/null || true
fi
if [ ! -s "$sdkDir/platforms/android-36/framework-res.apk" ]; then
  cp /system/framework/framework-res.apk "$sdkDir/platforms/android-36/framework-res.apk" 2>/dev/null || true
fi
cp "$prefix/bin/aapt2" "$sdkDir/build-tools/36.0.0/aapt2" 2>/dev/null || true
mark sdk DONE

echo "[verify] ..."
VFAIL=0
if [ -x "$prefix/bin/java" ]; then echo "  OK  java"; else echo "  MISS java ($prefix/bin/java)"; VFAIL=1; fi
if [ -x "$prefix/bin/aapt2" ]; then echo "  OK  aapt2"; else echo "  MISS aapt2 ($prefix/bin/aapt2)"; VFAIL=1; fi
if [ -s "$sdkDir/platforms/android-36/android.jar" ]; then echo "  OK  android.jar"; else echo "  MISS android.jar"; VFAIL=1; fi
if [ -s "$sdkDir/platforms/android-36/framework-res.apk" ]; then echo "  OK  framework-res.apk"; else echo t("  WARN framework-res.apk (可能非必需)"); fi
if [ ${'$'}VFAIL -ne 0 ]; then
  echo t("=== VERIFY FAILED: 缺失项见上（MISS），请复制日志 ===")
  exit 4
fi
touch "$toolDir/.installed"
echo "=== INSTALL DONE ==="
""".trimIndent()
    }

    /** 卸载工具链。 */
    fun uninstall() {
        runCatching { toolDir().deleteRecursively() }
        prefs?.edit()?.putBoolean(KEY_DONE, false)?.apply()
        _state.value = State(Phase.IDLE)
    }
}
