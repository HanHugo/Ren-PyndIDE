/*
 * engine_bridge.c — Ren'Py 内置引擎桥接层（含 native 崩溃自捕获）。
 *
 * 脱离官方 SDLActivity，直接 dlopen Ren'Py 的 arm64 librenpython.so
 * （内部静态链接了 CPython 3.12 + _renpy + pygame_sdl2 + SDL2），
 * 通过 CPython C API 初始化解释器，用于自检与执行 renpy.py compile。
 *
 * 关键设计：
 *  - dlopen 而非 System.loadLibrary：避开 so 自带的 SDL JNI_OnLoad（无 Surface 会崩）。
 *  - 【关键】dlopen 后手动调 SDL_SetMainReady()：该 so 用 SDL_main 链接，
 *    不走官方入口就必须先调它，否则 SDL 初始化会直接 abort()（SIGABRT）。
 *  - 手动 AppendInittab 逐个注册 Ren'Py 全部内置引擎模块（_renpy 等）。
 *  - Py_SetPath 直接给出 sys.path，绕开 PYTHONHOME 探测。
 *  - dup2 把 fd 1/2 重定向到日志文件；signal handler 把崩溃栈写到独立 crash.log。
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <unistd.h>
#include <fcntl.h>
#include <locale.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <unwind.h>
#include <pthread.h>
#include <time.h>
#include "renpy_modules.h"

/* ---------------- native 崩溃自捕获 ---------------- */
static char g_crash_path[1024] = {0};

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    int fd = *(int *) arg;
    uintptr_t ip = (uintptr_t) _Unwind_GetIP(ctx);
    if (!ip) return _URC_NO_REASON;
    char buf[256];
    Dl_info info;
    int n = 0;
    if (dladdr((void *) ip, &info) && info.dli_fname) {
        const char *base = strrchr(info.dli_fname, '/');
        base = base ? base + 1 : info.dli_fname;
        if (info.dli_sname) {
            uintptr_t off = ip - (uintptr_t) info.dli_saddr;
            n = snprintf(buf, sizeof(buf), "    #%p  %s  %s + 0x%lx\n",
                         (void *) ip, base, info.dli_sname, (unsigned long) off);
        } else {
            uintptr_t off = ip - (uintptr_t) info.dli_fbase;
            n = snprintf(buf, sizeof(buf), "    #%p  %s + 0x%lx\n",
                         (void *) ip, base, (unsigned long) off);
        }
    } else {
        n = snprintf(buf, sizeof(buf), "    #%p\n", (void *) ip);
    }
    if (n > 0) write(fd, buf, (size_t) n);
    return _URC_NO_REASON;
}

static void crash_handler(int sig) {
    if (!g_crash_path[0]) _exit(128 + sig);
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd >= 0) {
        char buf[160];
        const char *name = sig == SIGABRT ? "SIGABRT/abort()" :
                           (sig == SIGSEGV ? "SIGSEGV" :
                            (sig == SIGBUS ? "SIGBUS" : "SIGILL"));
        int n = snprintf(buf, sizeof(buf),
                         "\n===== NATIVE CRASH signal=%d (%s) =====\n", sig, name);
        if (n > 0) write(fd, buf, (size_t) n);
        _Unwind_Backtrace(unwind_cb, &fd);
        n = snprintf(buf, sizeof(buf), "===== END CRASH =====\n");
        if (n > 0) write(fd, buf, (size_t) n);
        close(fd);
    }
    _exit(128 + sig);
}

static void install_crash_handler(const char *path) {
    if (path) {
        strncpy(g_crash_path, path, sizeof(g_crash_path) - 1);
        g_crash_path[sizeof(g_crash_path) - 1] = 0;
    }
    signal(SIGSEGV, crash_handler);
    signal(SIGABRT, crash_handler);
    signal(SIGBUS, crash_handler);
    signal(SIGILL, crash_handler);
}

/* ---------------- native 阶段停滞看门狗 ----------------
 * 背景：librenpython.so 在 fork 出的 :compiler 进程里 dlopen/初始化时，
 * 偶发卡在 native linker/CPython 初始化内部（不崩溃、不返回），表现为编译
 * 永远停在 0%。为此起一个独立看门狗线程：主线程每进入一个阶段就推进
 * g_stage_seq；若某阶段在超时时间内没有任何推进，看门狗直接把「卡在哪个
 * 阶段」写进 crash.log 并 _exit，让主进程能立即感知并显示明确诊断。 */
static volatile int   g_stage_seq = 0;          /* 阶段序号，推进即刷新看门狗 */
static volatile int   g_watchdog_stop = 0;      /* 编译正常结束时置 1 */
static const char    *g_stage_name = "startup";
static volatile int   g_stage_timeout = 40;     /* 单阶段停滞上限（秒），编译阶段可调大 */

static void *watchdog_thread(void *arg) {
    (void) arg;
    int last_seq = -1;
    int stalled_sec = 0;
    for (;;) {
        if (g_watchdog_stop) break;
        int cur = g_stage_seq;
        if (cur != last_seq) {
            last_seq = cur;          /* 阶段推进了，重置计时 */
            stalled_sec = 0;
        } else {
            stalled_sec++;
        }
        if (stalled_sec >= g_stage_timeout) {
            if (g_crash_path[0]) {
                int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
                if (fd >= 0) {
                    char b[512];
                    int n = snprintf(b, sizeof(b),
                        "\n===== STAGE STALL (watchdog) =====\n"
                        "卡住阶段: %s\n"
                        "该 native 阶段超过 %d 秒无进展，判定为挂起。\n"
                        "常见原因：fork 后 linker/malloc 锁竞争，或 CPython 静态初始化死锁。\n"
                        "===== END STALL =====\n",
                        g_stage_name ? g_stage_name : "(unknown)", g_stage_timeout);
                    if (n > 0) write(fd, b, (size_t) n);
                    close(fd);
                }
            }
            _exit(97);   /* 直接终止本编译子进程，主进程经 crash.log 感知 */
        }
        usleep(1000000);  /* 每秒检查一次 */
    }
    return NULL;
}

/* CPython C API 函数指针类型 */
typedef void  (*fn_SetPath)(const wchar_t *);
typedef void  (*fn_InitializeEx)(int);
typedef int   (*fn_IsInitialized)(void);
typedef int   (*fn_RunSimpleString)(const char *);
typedef int   (*fn_RunFile)(FILE *, const char *, int, void *);
typedef void  (*fn_SetArgvEx)(int, wchar_t **, int);
typedef int   (*fn_FinalizeEx)(void);
typedef void  (*fn_Finalize)(void);
typedef void  (*fn_SetMainReady)(void);

/* 模块注册只用 PyImport_AppendInittab：逐个 name+func 追加，有内部容量检查，安全。
 * 【已移除】PyImport_Inittab / PyImport_ExtendInittab：该全局数据符号在 CPython 3.12
 * 中经 dlsym 解析后语义不可靠，遍历其数组曾导致 native 死循环（编译卡在 dlsym OK）。 */
typedef int (*fn_AppendInittab)(const char *, void *);

/* SDL3 app metadata：设置 name 后，SDL_GetAppMetadataProperty(name) 直接返回缓存值，
 * 不会 fallback 到 SDL_GetExeName -> SDL_GetAndroidPackageName（该函数在无 JNI 环境会
 * 空指针 SIGSEGV）。这是纯设字符串、不依赖 JNI 的安全兜底。
 * 【注意】绝不能手动调 so 的 JNI_OnLoad：它内部 FindClass/RegisterNatives 依赖
 * org.libsdl.app.SDLActivity 等我们 APK 里没有的类，会触发 ART fatal abort（SIGABRT）。 */
typedef int (*fn_SetAppMetadata)(const char *, const char *, const char *);

/* 把 UTF-8/ASCII 路径转成宽字符（Android wchar_t 为 4 字节） */
static wchar_t *to_wide(const char *s) {
    if (!s) return NULL;
    size_t n = mbstowcs(NULL, s, 0);
    if (n == (size_t)-1) return NULL;
    wchar_t *w = (wchar_t *) malloc((n + 1) * sizeof(wchar_t));
    if (!w) return NULL;
    mbstowcs(w, s, n + 1);
    return w;
}

/* 引擎运行时上下文：一次 dlopen + 一组函数指针，供自检与编译复用。 */
typedef struct {
    void *h;
    fn_SetPath SetPath;
    fn_InitializeEx Init;
    fn_IsInitialized IsInit;
    fn_RunSimpleString Run;
    fn_RunFile RunFile;
    fn_SetArgvEx SetArgvEx;
    fn_FinalizeEx FinEx;
    fn_Finalize Fin;
    fn_SetMainReady SetMainReady;
} EngineCtx;

/* 打点：写到 stderr（已被重定向到日志）并强制刷新，同时推进看门狗阶段序号。
 * 每次 STAGE 都意味着 native 仍在前进，看门狗据此判定是否卡死。 */
#define STAGE(msg) do { \
    g_stage_name = (msg); \
    __sync_fetch_and_add(&g_stage_seq, 1); \
    fprintf(stderr, "[stage] %s\n", (msg)); fflush(stderr); \
} while (0)

/*
 * 打开引擎：dlopen → SDL_SetMainReady → dlsym → 注册模块 → 设置 sys.path → 初始化解释器。
 * 所有诊断写 stderr（已被调用方重定向到日志）。成功返回 0，失败返回非 0 并填 err。
 */
static int engine_open(EngineCtx *c, const char *soPath, const char *sysPath,
                       JNIEnv *c_env, char *err, size_t errsz) {
    (void) c_env;  /* 保留参数以兼容调用点；JNI_OnLoad 方案已废弃（会触发 ART abort） */
    memset(c, 0, sizeof(*c));
    setlocale(LC_ALL, "C");

    STAGE("before dlopen");
    /* 【改】RTLD_NOW 替代 RTLD_LAZY：把全部重定位在 dlopen 内一次性完成，
     * 避免首次调用时才懒解析符号、在 fork 后子进程里与 linker 锁竞争而挂起。 */
    c->h = dlopen(soPath, RTLD_NOW | RTLD_LOCAL);
    if (!c->h) {
        snprintf(err, errsz, "DLOPEN_FAIL: %s", dlerror());
        fprintf(stderr, "%s\n", err); fflush(stderr);
        return 1;
    }
    STAGE("dlopen OK");

    /* 关键修复：预设 app metadata，切断 SDL_GetAppMetadataProperty(name) 在 name 缺失时
     * 的 lazy fallback：SDL_GetExeName -> SDL_GetAndroidPackageName -> 空 JNIEnv SIGSEGV。
     * 反汇编确认：设置了 name property 后 GetAppMetadataProperty 直接返回缓存值，不再调
     * SDL_GetExeName。纯设字符串、不依赖 JNI，安全。
     * 【禁忌】绝不手动调 JNI_OnLoad：其 FindClass/RegisterNatives 依赖 APK 里不存在的
     * org.libsdl.app.SDLActivity，会触发 ART fatal abort(SIGABRT)。 */
    {
        fn_SetAppMetadata pSetMeta =
            (fn_SetAppMetadata) dlsym(c->h, "SDL_SetAppMetadata");
        if (pSetMeta) {
            pSetMeta("RenPyndIDE Compiler", "1.0", "com.renpy.mobile.compiler");
            STAGE("SDL_SetAppMetadata done");
        } else {
            STAGE("SDL_SetAppMetadata NOT FOUND");
        }
    }

    /* dlsym 每个函数都单独打点，卡在任意一个都能从日志立即定位。 */
    STAGE("dlsym Py_SetPath");
    c->SetPath   = (fn_SetPath) dlsym(c->h, "Py_SetPath");
    STAGE("dlsym Py_InitializeEx");
    c->Init      = (fn_InitializeEx) dlsym(c->h, "Py_InitializeEx");
    STAGE("dlsym Py_IsInitialized");
    c->IsInit    = (fn_IsInitialized) dlsym(c->h, "Py_IsInitialized");
    STAGE("dlsym PyRun_SimpleString");
    c->Run       = (fn_RunSimpleString) dlsym(c->h, "PyRun_SimpleString");
    STAGE("dlsym PyRun_SimpleFileExFlags");
    c->RunFile   = (fn_RunFile) dlsym(c->h, "PyRun_SimpleFileExFlags");
    STAGE("dlsym PySys_SetArgvEx");
    c->SetArgvEx = (fn_SetArgvEx) dlsym(c->h, "PySys_SetArgvEx");
    STAGE("dlsym Py_FinalizeEx");
    c->FinEx     = (fn_FinalizeEx) dlsym(c->h, "Py_FinalizeEx");
    c->Fin       = (fn_Finalize) dlsym(c->h, "Py_Finalize");

    /* 【关键】该 so 用 SDL_main 链接：不走官方入口就必须先调 SDL_SetMainReady()，
     * 否则 Cython 模块 init 里首次 SDL_Init() 会直接 abort()（SIGABRT）。
     * 之前"卡在 dlopen OK"实际就是卡在这句首次懒绑定解析，移到全部 dlsym 之后，
     * 现在配合 RTLD_NOW 已在 dlopen 内解析完，这里只剩纯设标志。 */
    STAGE("before SDL_SetMainReady");
    c->SetMainReady = (fn_SetMainReady) dlsym(c->h, "SDL_SetMainReady");
    if (c->SetMainReady) {
        c->SetMainReady();
        STAGE("SDL_SetMainReady called");
    } else {
        STAGE("SDL_SetMainReady NOT FOUND (proceed anyway)");
    }

    if (!c->SetPath || !c->Init || !c->Run || (!c->FinEx && !c->Fin)) {
        snprintf(err, errsz,
                 "DLSYM_FAIL: SetPath=%d Init=%d Run=%d Fin=%d",
                 c->SetPath != 0, c->Init != 0, c->Run != 0,
                 (c->FinEx || c->Fin) != 0);
        fprintf(stderr, "%s\n", err); fflush(stderr);
        return 2;
    }
    STAGE("dlsym OK");

    /* 注册 Ren'Py 内置引擎模块（必须在 Py_Initialize 之前）。
     * 【重要】只用 AppendInittab 逐个追加：自检已验证这条路径可用。
     * 已彻底移除 ExtendInittab 遍历 PyImport_Inittab 的未定义行为——那是本次
     * 编译卡死的直接原因（dlsym 拿到的二级指针若无效，for(e->name) 会死循环）。 */
    STAGE("before AppendInittab");
    fn_AppendInittab pAppend = (fn_AppendInittab) dlsym(c->h, "PyImport_AppendInittab");
    if (pAppend) {
        int ok = 0, fail = 0;
        for (int i = 0; RENPY_FALLBACK_MODULES[i].name != NULL; i++) {
            void *fn = dlsym(c->h, RENPY_FALLBACK_MODULES[i].sym);
            if (fn && pAppend(RENPY_FALLBACK_MODULES[i].name, fn) == 0) ok++;
            else fail++;
        }
        fprintf(stderr, "[bridge] AppendInittab ok=%d fail=%d\n", ok, fail);
    } else {
        fprintf(stderr, "[bridge] AppendInittab SYMBOL NOT FOUND\n");
    }
    fflush(stderr);
    STAGE("modules registered");

    /* sys.path（必须在 Py_Initialize 之前） */
    wchar_t *wPath = to_wide(sysPath);
    if (!wPath) {
        snprintf(err, errsz, "WIDE_CONVERT_FAIL: %s", sysPath);
        fprintf(stderr, "%s\n", err); fflush(stderr);
        return 3;
    }
    c->SetPath(wPath);
    free(wPath);
    STAGE("Py_SetPath called");

    STAGE("before Py_InitializeEx");
    c->Init(0);
    if (c->IsInit && !c->IsInit()) {
        snprintf(err, errsz, "INIT_FAIL: Py_IsInitialized()==0");
        fprintf(stderr, "%s\n", err); fflush(stderr);
        return 4;
    }
    STAGE("Py_InitializeEx OK");
    return 0;
}

static void engine_close(EngineCtx *c) {
    if (c->FinEx) c->FinEx(); else if (c->Fin) c->Fin();
    fprintf(stderr, "[bridge] Py_Finalize done\n"); fflush(stderr);
    if (c->h) { dlclose(c->h); c->h = NULL; }
}

/* 把字符串里的单引号转义，供嵌入 Python 源码字面量使用 */
static char *py_quote(const char *s) {
    size_t cap = strlen(s) * 2 + 1;
    char *out = (char *) malloc(cap);
    size_t j = 0;
    for (size_t i = 0; s[i]; i++) {
        if (s[i] == '\\' || s[i] == '\'') out[j++] = '\\';
        out[j++] = s[i];
    }
    out[j] = '\0';
    return out;
}

/*
 * nativeSelfTest(soPath, sysPath, outPath)
 */
JNIEXPORT jstring JNICALL
Java_com_renpy_mobile_engine_EngineBridge_nativeSelfTest(
        JNIEnv *env, jclass clazz,
        jstring jSoPath, jstring jSysPath, jstring jOutPath) {
    (void) clazz;
    char status[2048];
    status[0] = '\0';

    const char *soPath = (*env)->GetStringUTFChars(env, jSoPath, NULL);
    const char *sysPath = (*env)->GetStringUTFChars(env, jSysPath, NULL);
    const char *outPath = (*env)->GetStringUTFChars(env, jOutPath, NULL);

    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    int savedOut = -1, savedErr = -1;
    if (outFd >= 0) {
        savedOut = dup(STDOUT_FILENO);
        savedErr = dup(STDERR_FILENO);
        dup2(outFd, STDOUT_FILENO);
        dup2(outFd, STDERR_FILENO);
        close(outFd);
    }

    fprintf(stderr, "[bridge] soPath=%s\n", soPath);
    fprintf(stderr, "[bridge] sysPath=%s\n", sysPath);
    fflush(stderr);

    EngineCtx c;
    char err[1024];
    int rc = engine_open(&c, soPath, sysPath, env, err, sizeof(err));
    if (rc != 0) {
        snprintf(status, sizeof(status), "%s", err);
        goto done;
    }

    /* 自检：解释器/标准库/引擎模块 + renpy 包路径诊断 */
    const char *code =
        "import sys\n"
        "print('RENPY_ENGINE_SELFTEST_OK')\n"
        "print('PYTHON_VERSION=' + sys.version.split()[0])\n"
        "print('SYS_PATH=' + repr(sys.path))\n"
        "try:\n"
        "    import encodings\n"
        "    print('IMPORT_ENCODINGS=OK')\n"
        "except BaseException as e:\n"
        "    print('IMPORT_ENCODINGS=FAIL:' + repr(e))\n"
        "import os\n"
        "for _p in sys.path:\n"
        "    try:\n"
        "        _ip = os.path.join(_p, 'renpy', '__init__.py')\n"
        "        print('PATHCHECK=' + repr(_p) + ' dir=' + str(os.path.isdir(_p)) + ' renpy_init=' + str(os.path.exists(_ip)))\n"
        "    except BaseException as e:\n"
        "        print('PATHCHECK_ERR=' + repr(e))\n"
        "try:\n"
        "    import renpy\n"
        "    print('IMPORT_RENPY=OK')\n"
        "except BaseException as e:\n"
        "    print('IMPORT_RENPY=FAIL:' + repr(e))\n"
        "try:\n"
        "    import _renpy\n"
        "    print('IMPORT__RENPY=OK')\n"
        "except BaseException as e:\n"
        "    print('IMPORT__RENPY=FAIL:' + repr(e))\n"
        "try:\n"
        "    import renpy.pygame.error\n"
        "    print('IMPORT_PYGAME_ERROR=OK')\n"
        "except BaseException as e:\n"
        "    print('IMPORT_PYGAME_ERROR=FAIL:' + repr(e))\n"
        "sys.stdout.flush()\n";
    STAGE("before self-test PyRun");
    int prc = c.Run(code);
    fprintf(stderr, "[bridge] PyRun_SimpleString rc=%d\n", prc); fflush(stderr);
    engine_close(&c);
    snprintf(status, sizeof(status), "SELFTEST_DONE rc=%d", prc);

done:
    fflush(stdout); fflush(stderr);
    if (savedOut >= 0) { dup2(savedOut, STDOUT_FILENO); close(savedOut); }
    if (savedErr >= 0) { dup2(savedErr, STDERR_FILENO); close(savedErr); }
    (*env)->ReleaseStringUTFChars(env, jSoPath, soPath);
    (*env)->ReleaseStringUTFChars(env, jSysPath, sysPath);
    (*env)->ReleaseStringUTFChars(env, jOutPath, outPath);
    return (*env)->NewStringUTF(env, status);
}

/*
 * nativeCompile(soPath, sysPath, renpyPyPath, projectPath, outPath, crashPath)
 * 在内置引擎里执行 renpy.py <project> compile，输出写入 outPath；
 * native 崩溃栈写入 crashPath。返回 "COMPILE_DONE rc=<n>" 或错误标识。
 */
JNIEXPORT jstring JNICALL
Java_com_renpy_mobile_engine_EngineBridge_nativeCompile(
        JNIEnv *env, jclass clazz,
        jstring jSoPath, jstring jSysPath, jstring jRenpyPy,
        jstring jProject, jstring jOutPath, jstring jCrashPath) {
    (void) clazz;
    char status[2048];
    status[0] = '\0';

    const char *soPath   = (*env)->GetStringUTFChars(env, jSoPath, NULL);
    const char *sysPath  = (*env)->GetStringUTFChars(env, jSysPath, NULL);
    const char *renpyPy  = (*env)->GetStringUTFChars(env, jRenpyPy, NULL);
    const char *project  = (*env)->GetStringUTFChars(env, jProject, NULL);
    const char *outPath  = (*env)->GetStringUTFChars(env, jOutPath, NULL);
    const char *crashPath = (*env)->GetStringUTFChars(env, jCrashPath, NULL);

    /* 清空旧崩溃日志并安装 signal handler（在 dup2 之前，保证 handler 用绝对路径） */
    if (crashPath) {
        int cf = open(crashPath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (cf >= 0) close(cf);
    }
    install_crash_handler(crashPath);

    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    int savedOut = -1, savedErr = -1;
    if (outFd >= 0) {
        savedOut = dup(STDOUT_FILENO);
        savedErr = dup(STDERR_FILENO);
        dup2(outFd, STDOUT_FILENO);
        dup2(outFd, STDERR_FILENO);
        close(outFd);
    }

    /* 纯编译：禁用 display/audio/JIT，避免无 EGL surface 时初始化 SDL 视频 */
    setenv("SDL_VIDEODRIVER", "dummy", 1);
    setenv("SDL_AUDIODRIVER", "dummy", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    setenv("RENPY_DISABLE_JIT", "1", 1);

    fprintf(stderr, "[bridge] compile renpyPy=%s\n", renpyPy);
    fprintf(stderr, "[bridge] compile project=%s\n", project);
    fprintf(stderr, "[bridge] soPath=%s\n", soPath);
    fprintf(stderr, "[bridge] sysPath=%s\n", sysPath);
    fflush(stderr);

    /* 启动阶段停滞看门狗：若任一 native 阶段超过 STAGE_TIMEOUT_SEC 无进展，
     * 它会把卡住阶段写进 crash.log 并终止本进程，主进程即可立即显示诊断。
     * 注意编译真正的 PyRun 阶段可能合法地久于超时，故进入该阶段前会单独放宽。 */
    g_watchdog_stop = 0;
    g_stage_seq = 0;
    g_stage_timeout = 40;   /* 进程复用时重置：初始化阶段回到 40 秒阈值 */
    g_stage_name = "watchdog-start";
    pthread_t wd_tid;
    if (pthread_create(&wd_tid, NULL, watchdog_thread, NULL) != 0) {
        fprintf(stderr, "[bridge] WARN: watchdog thread create failed\n"); fflush(stderr);
    }

    EngineCtx c;
    char err[1024];
    int rc = engine_open(&c, soPath, sysPath, env, err, sizeof(err));
    fprintf(stderr, "[bridge] engine_open rc=%d err=%s\n", rc, rc ? err : "(none)");
    fflush(stderr);
    if (rc != 0) {
        snprintf(status, sizeof(status), "%s", err);
        goto done;
    }

    /* sys.argv = ['renpy.py', <project>, 'compile'] */
    if (c.SetArgvEx) {
        wchar_t *argv[3];
        argv[0] = to_wide("renpy.py");
        argv[1] = to_wide(project);
        argv[2] = to_wide("compile");
        c.SetArgvEx(3, argv, 0);
        free(argv[0]); free(argv[1]); free(argv[2]);
        fprintf(stderr, "[bridge] PySys_SetArgvEx done\n"); fflush(stderr);
    } else {
        char *qp = py_quote(project);
        size_t csz = strlen(qp) + 128;
        char *code = (char *) malloc(csz);
        snprintf(code, csz, "import sys; sys.argv=['renpy.py', '%s', 'compile']\n", qp);
        c.Run(code);
        free(code); free(qp);
        fprintf(stderr, "[bridge] sys.argv set via Python\n"); fflush(stderr);
    }

    /* 执行 renpy.py 文件 */
    if (!c.RunFile) {
        snprintf(status, sizeof(status), "NO_RUNFILE_SYMBOL");
        engine_close(&c);
        goto done;
    }
    FILE *f = fopen(renpyPy, "rb");
    if (!f) {
        snprintf(status, sizeof(status), "RENPYPY_OPEN_FAIL: %s", strerror(errno));
        engine_close(&c);
        goto done;
    }
    /* 进入 Python 脚本编译：这一步可能合法跑十几分钟（大项目上千个 rpy），
     * 期间 native 层不再有新 STAGE 打点。看门狗若继续按单阶段计时，会在 5 分钟后
     * 误判「卡住」并杀掉编译进程（这正是之前进度到 103 就被掐断的原因之一）。
     * 编译阶段的进度/卡死改由 Kotlin 侧轮询日志行与实际超时统一判断，这里停掉看门狗。 */
    g_watchdog_stop = 1;

    /* 执行 renpy.py 前的 Python 环境准备：
     * 1) stdout/stderr 行缓冲，让 "Compiling xxx.rpy" 实时刷到日志驱动进度条。
     * 2) faulthandler 只 enable（崩溃抓栈），不周期 dump（曾刷屏且被误算错误）。
     * 3) 【关键】设 RENPY_PATH_TO_SAVES 指向 app 可写目录。否则 Ren'Py 的
     *    savetoken.init_tokens → path_to_saves 在 Android 上（我们未设 ANDROID_*
     *    环境变量）会落到 Linux 分支 "~/.renpy/"，expanduser 得到不可写的
     *    /data/.renpy，创建 security_keys.txt 时 FileNotFoundError，编译在 93% 失败。
     *    用 outPath(compile.log) 所在 cache 目录派生可写存档目录。 */
    {
        char *qOut = py_quote(outPath);
        size_t dsz = strlen(qOut) + 1024;
        char *diag = (char *) malloc(dsz);
        snprintf(diag, dsz,
            "import sys, os\n"
            "try:\n"
            "    sys.stdout.reconfigure(line_buffering=True, write_through=True)\n"
            "    sys.stderr.reconfigure(line_buffering=True, write_through=True)\n"
            "except Exception: pass\n"
            /* faulthandler 只 enable（崩溃时抓栈写 stderr→compile.log），不再周期 dump。
             * 周期栈曾被误当成"卡死"证据，且是纯诊断噪音；真卡死由 Kotlin 侧超时兜底。 */
            "try:\n"
            "    import faulthandler; faulthandler.enable()\n"
            "except Exception: pass\n"
            /* 只设 RENPY_PATH_TO_SAVES，不设 HOME：path_to_saves 非 android 分支
             * 第一个判断就命中它，绕开不可写的 /data/.renpy，无需 HOME。 */
            "try:\n"
            "    _cache = os.path.dirname(os.path.dirname('%s'))\n"
            "    _saves = os.path.join(_cache, 'renpy-saves')\n"
            "    os.makedirs(_saves, exist_ok=True)\n"
            "    os.environ['RENPY_PATH_TO_SAVES'] = _saves\n"
            "    print('[bridge] RENPY_PATH_TO_SAVES=' + _saves)\n"
            "except Exception as _e:\n"
            "    print('[bridge] env setup FAIL: %%r' %% (_e,))\n"
            "sys.stdout.flush()\n",
            qOut);
        c.Run(diag);
        free(diag); free(qOut);
    }
    STAGE("before PyRun_SimpleFileExFlags (renpy.py compile)");
    int frc = c.RunFile(f, renpyPy, 1, NULL);
    STAGE("after PyRun_SimpleFileExFlags");
    c.Run("import sys\ntry:\n    sys.stdout.flush()\n    sys.stderr.flush()\nexcept Exception: pass\n");
    /* Ren'Py compile 完成通常调用 sys.exit(0)，PyRun 会返回非 0；以日志与 rpyc 数量为准 */
    engine_close(&c);
    snprintf(status, sizeof(status), "COMPILE_DONE rc=%d", frc);

done:
    g_watchdog_stop = 1;   /* 通知看门狗线程退出 */
    fflush(stdout); fflush(stderr);
    if (savedOut >= 0) { dup2(savedOut, STDOUT_FILENO); close(savedOut); }
    if (savedErr >= 0) { dup2(savedErr, STDERR_FILENO); close(savedErr); }
    (*env)->ReleaseStringUTFChars(env, jSoPath, soPath);
    (*env)->ReleaseStringUTFChars(env, jSysPath, sysPath);
    (*env)->ReleaseStringUTFChars(env, jRenpyPy, renpyPy);
    (*env)->ReleaseStringUTFChars(env, jProject, project);
    (*env)->ReleaseStringUTFChars(env, jOutPath, outPath);
    if (crashPath) (*env)->ReleaseStringUTFChars(env, jCrashPath, crashPath);
    return (*env)->NewStringUTF(env, status);
}

/*
 * nativeRunScript(soPath, sysPath, scriptPath, argsPath, outPath, crashPath)
 * 在内置 CPython 里执行任意 Python 脚本（AI 工具入口），用途：
 *  renpy-mcp 移植包由纯标准库构成，只需要一个能跑脚本的 CPython 解释器。
 * 复用 engine_open() 的 dlopen + SDL_SetMainReady + 模块注册流程，
 * 把 sys.argv 设为 [scriptPath, argsPath]，脚本从 argsPath 读 JSON 参数、
 * 把结果 JSON print 到 stdout（已被重定向到 outPath）。
 * 返回 "SCRIPT_DONE rc=<n>" 或错误标识；native 崩溃栈写 crashPath。
 */
JNIEXPORT jstring JNICALL
Java_com_renpy_mobile_engine_EngineBridge_nativeRunScript(
        JNIEnv *env, jclass clazz,
        jstring jSoPath, jstring jSysPath, jstring jScriptPath,
        jstring jArgsPath, jstring jOutPath, jstring jCrashPath) {
    (void) clazz;
    char status[2048];
    status[0] = '\0';

    const char *soPath    = (*env)->GetStringUTFChars(env, jSoPath, NULL);
    const char *sysPath   = (*env)->GetStringUTFChars(env, jSysPath, NULL);
    const char *scriptPath= (*env)->GetStringUTFChars(env, jScriptPath, NULL);
    const char *argsPath  = (*env)->GetStringUTFChars(env, jArgsPath, NULL);
    const char *outPath   = (*env)->GetStringUTFChars(env, jOutPath, NULL);
    const char *crashPath = (*env)->GetStringUTFChars(env, jCrashPath, NULL);

    /* 清空旧崩溃日志并安装 signal handler（在 dup2 之前，handler 用绝对路径）。 */
    if (crashPath) {
        int cf = open(crashPath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (cf >= 0) close(cf);
    }
    install_crash_handler(crashPath);

    /* stdout/stderr → outPath：脚本唯一输出就是结果 JSON。 */
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    int savedOut = -1, savedErr = -1;
    if (outFd >= 0) {
        savedOut = dup(STDOUT_FILENO);
        savedErr = dup(STDERR_FILENO);
        dup2(outFd, STDOUT_FILENO);
        dup2(outFd, STDERR_FILENO);
        close(outFd);
    }

    /* 工具脚本不渲染 UI，禁用 SDL 图形/音频，避免无 EGL surface 时 abort。 */
    setenv("SDL_VIDEODRIVER", "dummy", 1);
    setenv("SDL_AUDIODRIVER", "dummy", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    setenv("RENPY_DISABLE_JIT", "1", 1);

    /* 工具脚本不 import renpy、不写存档，看门狗意义不大；直接停掉，避免误杀。 */
    g_watchdog_stop = 1;

    fprintf(stderr, "[bridge] runScript=%s\n", scriptPath);
    fprintf(stderr, "[bridge] argsPath=%s\n", argsPath);
    fflush(stderr);

    EngineCtx c;
    char err[1024];
    int rc = engine_open(&c, soPath, sysPath, env, err, sizeof(err));
    if (rc != 0) {
        snprintf(status, sizeof(status), "%s", err);
        goto done;
    }

    /* sys.argv = [scriptPath, argsPath]。入口脚本按此约定读取参数。 */
    if (c.SetArgvEx) {
        wchar_t *argv[2];
        argv[0] = to_wide(scriptPath);
        argv[1] = to_wide(argsPath);
        c.SetArgvEx(2, argv, 0);
        free(argv[0]); free(argv[1]);
    } else {
        char *qs = py_quote(scriptPath);
        char *qa = py_quote(argsPath);
        size_t csz = strlen(qs) + strlen(qa) + 128;
        char *code = (char *) malloc(csz);
        snprintf(code, csz, "import sys; sys.argv=['%s', '%s']\n", qs, qa);
        c.Run(code);
        free(code); free(qs); free(qa);
    }

    /* 把脚本所在目录加入 sys.path，使其能 import renpy_mcp 包与 mcp shim。
     * 同时开行缓冲，让脚本 print 的 JSON 立即写入 outPath。 */
    {
        char *qs = py_quote(scriptPath);
        size_t dsz = strlen(qs) + 768;
        char *prep = (char *) malloc(dsz);
        snprintf(prep, dsz,
            "import sys, os\n"
            "sys.path.insert(0, os.path.dirname(os.path.abspath('%s')))\n"
            "try:\n"
            "    sys.stdout.reconfigure(line_buffering=True, write_through=True)\n"
            "    sys.stderr.reconfigure(line_buffering=True, write_through=True)\n"
            "except Exception: pass\n",
            qs);
        c.Run(prep);
        free(prep); free(qs);
    }

    if (!c.RunFile) {
        snprintf(status, sizeof(status), "NO_RUNFILE_SYMBOL");
        engine_close(&c);
        goto done;
    }
    FILE *f = fopen(scriptPath, "rb");
    if (!f) {
        snprintf(status, sizeof(status), "SCRIPT_OPEN_FAIL: %s", strerror(errno));
        engine_close(&c);
        goto done;
    }
    STAGE("before PyRun_SimpleFileExFlags (ai tool script)");
    int frc = c.RunFile(f, scriptPath, 1, NULL);
    STAGE("after PyRun_SimpleFileExFlags");
    c.Run("import sys\ntry:\n    sys.stdout.flush()\n    sys.stderr.flush()\nexcept Exception: pass\n");
    engine_close(&c);
    snprintf(status, sizeof(status), "SCRIPT_DONE rc=%d", frc);

done:
    g_watchdog_stop = 1;
    fflush(stdout); fflush(stderr);
    if (savedOut >= 0) { dup2(savedOut, STDOUT_FILENO); close(savedOut); }
    if (savedErr >= 0) { dup2(savedErr, STDERR_FILENO); close(savedErr); }
    (*env)->ReleaseStringUTFChars(env, jSoPath, soPath);
    (*env)->ReleaseStringUTFChars(env, jSysPath, sysPath);
    (*env)->ReleaseStringUTFChars(env, jScriptPath, scriptPath);
    (*env)->ReleaseStringUTFChars(env, jArgsPath, argsPath);
    (*env)->ReleaseStringUTFChars(env, jOutPath, outPath);
    if (crashPath) (*env)->ReleaseStringUTFChars(env, jCrashPath, crashPath);
    return (*env)->NewStringUTF(env, status);
}
