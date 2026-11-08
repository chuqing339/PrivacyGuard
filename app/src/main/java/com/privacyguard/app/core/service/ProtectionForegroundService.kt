package com.privacyguard.app.core.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.privacyguard.app.MainActivity
import com.privacyguard.app.PrivacyGuardApp
import com.privacyguard.app.R
import com.privacyguard.app.core.root.SuShellExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull

class ProtectionForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logPollingJob: Job? = null

    companion object {
        const val CHANNEL_ID = "privacy_guard_protection"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ProtectionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProtectionForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("全机防护已启动", "四层反遥测引擎运行中"))
        startLogCollector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        logPollingJob?.cancel()
        serviceScope.cancel()
    }

    private fun startLogCollector() {
        val app = application as PrivacyGuardApp
        logPollingJob = serviceScope.launch {
            // 定时每 15 秒轮询内核拦截日志（需总开关与日志记录开启，且已取得 Root 权限）
            while (isActive) {
                try {
                    val config = app.settingsDataStore.configFlow.firstOrNull()
                    if (config?.masterEnabled == true && config.logEnabled && SuShellExecutor.isRootGranted()) {
                        val count = app.interceptLogRepository.captureKernelLogs()
                        if (count > 0) {
                            updateNotification("已捕获 $count 次隐私偷跑尝试", "小米多媒体/广告/反诈上报已被阻断")
                        }
                    }
                } catch (_: Exception) {}
                delay(15000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "隐私防护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维持后台拦截状态与隐私偷跑监控"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }
}
