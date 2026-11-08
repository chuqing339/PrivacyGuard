package com.privacyguard.app.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.privacyguard.app.PrivacyGuardApp
import com.privacyguard.app.core.service.ProtectionForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val app = context.applicationContext as PrivacyGuardApp
            // goAsync 拉长广播处理窗口，异步读取配置后再决定是否拉起前台服务
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val config = app.settingsDataStore.configFlow.first()
                    if (config.bootAutoStart && config.masterEnabled) {
                        // 清爽模式（保活通知关闭）不启动前台服务，规则照常应用；
                        // 拦截统计由下次打开 App 时的前台补采循环按水位线补齐
                        if (config.keepAliveNotification) {
                            val serviceIntent = Intent(context, ProtectionForegroundService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        }
                        app.protectionRepository.startProtection(config)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "开机自启防护失败", e)
                } finally {
                    // 无论成败都必须 finish，否则广播处理超时会被系统判定 ANR
                    pending.finish()
                }
            }
        }
    }
}
