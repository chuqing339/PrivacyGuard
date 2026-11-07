package com.privacyguard.app.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.privacyguard.app.PrivacyGuardApp
import com.privacyguard.app.core.service.ProtectionForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val app = context.applicationContext as PrivacyGuardApp
            CoroutineScope(Dispatchers.IO).launch {
                val config = app.settingsDataStore.configFlow.first()
                if (config.bootAutoStart && config.masterEnabled) {
                    val serviceIntent = Intent(context, ProtectionForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    app.protectionRepository.startProtection(config)
                }
            }
        }
    }
}
