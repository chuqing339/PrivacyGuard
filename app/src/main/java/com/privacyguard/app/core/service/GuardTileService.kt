package com.privacyguard.app.core.service

import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.privacyguard.app.PrivacyGuardApp
import com.privacyguard.app.R
import com.privacyguard.app.data.model.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class GuardTileService : TileService() {

    // SupervisorJob：单个协程异常不会波及作用域内其他任务；onDestroy 时整体取消
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = application as PrivacyGuardApp
        serviceScope.launch {
            val currentState = app.protectionRepository.dashboardState.value.engineStatus
            val config = app.settingsDataStore.configFlow.first()

            if (currentState == EngineStatus.RUNNING) {
                app.protectionRepository.stopProtection()
                app.settingsDataStore.setMasterEnabled(false)
                ProtectionForegroundService.stop(this@GuardTileService)
            } else {
                app.settingsDataStore.setMasterEnabled(true)
                ProtectionForegroundService.start(this@GuardTileService)
                app.protectionRepository.startProtection(config.copy(masterEnabled = true))
            }
            updateTileState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun updateTileState() {
        val app = application as PrivacyGuardApp
        val isRunning = app.protectionRepository.dashboardState.value.engineStatus == EngineStatus.RUNNING

        // qsTile 仅允许在主线程访问；从 IO 协程调用时必须切回主线程
        mainHandler.post {
            val tile = qsTile ?: return@post
            tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (isRunning) getString(R.string.tile_active) else getString(R.string.tile_inactive)
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_shield_check)
            tile.updateTile()
        }
    }
}
