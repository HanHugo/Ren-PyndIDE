package com.renpy.mobile.installer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.renpy.mobile.i18n.t

/**
 * SDK 下载前台服务：提升进程优先级，保证后台下载不被回收，并在通知栏显示真实进度。
 * 实际下载逻辑在 SdkInstaller（单例协程），Service 仅负责生命周期与通知。
 */
class SdkDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val CHANNEL_ID = "sdk_download"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SdkDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关键：服务常驻时再次 start() 只会走这里，不会走 onCreate。
        // 暂停后点"继续/重试"正是这个场景——每次都尝试启动尚未安装的组件。
        SdkInstaller.installAll()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        SdkInstaller.bind(this)
        createChannel()

        // 立即显示一个前台通知（满足 startForegroundService 的 5 秒约束）
        startForegroundCompat(0, t("正在准备下载…"))

        // 订阅真实进度，更新通知
        scope.launch {
            SdkInstaller.states.collectLatest { states ->
                val active = states.firstOrNull {
                    it.phase == SdkInstaller.Phase.DOWNLOADING ||
                        it.phase == SdkInstaller.Phase.VERIFYING ||
                        it.phase == SdkInstaller.Phase.EXTRACTING
                }
                if (active == null) {
                    // 无进行中任务：若已全部装完则收尾通知并停止服务
                    if (SdkInstaller.isAllInstalled()) {
                        notificationManager.notify(
                            NOTIF_ID,
                            NotificationCompat.Builder(this@SdkDownloadService, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle(t("SDK 安装完成"))
                                .setContentText("${SdkInstaller.SDK_VERSION} · 三个组件已就绪")
                                .setOngoing(false)
                                .build(),
                        )
                        stopSelf()
                    } else {
                        updateNotification(t("等待下载…"), 0, false)
                    }
                } else {
                    val pct = if (active.total > 0) {
                        ((active.downloaded.toFloat() / active.total) * 100).toInt().coerceIn(0, 100)
                    } else 0
                    val title = when (active.phase) {
                        SdkInstaller.Phase.VERIFYING -> t("校验文件中…")
                        SdkInstaller.Phase.EXTRACTING -> t("解压安装中…")
                        else -> "下载 SDK · $pct%"
                    }
                    updateNotification(title, pct, true)
                }
            }
        }
        // 真实下载由 onStartCommand 统一触发（首次启动它必在 onCreate 之后调用）。
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                t("SDK 下载"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = t("Ren'Py SDK 组件下载进度") }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, pct: Int, ongoing: Boolean) {
        startForegroundCompat(
            pct,
            title,
            ongoing,
        )
    }

    private fun startForegroundCompat(pct: Int, title: String, ongoing: Boolean = true) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (ongoing) "Ren'Py SDK 组件 · $pct%" else "Ren'Py SDK")
            .setProgress(100, pct, pct == 0 && ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            // Android 14+ 缺少 dataSync 前台服务权限时，退回普通通知，不影响下载协程
            notificationManager.notify(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
