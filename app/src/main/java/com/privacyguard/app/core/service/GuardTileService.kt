package com.privacyguard.app.core.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.privacyguard.app.PrivacyGuardApp
import com.privacyguard.app.R
import com.privacyguard.app.data.model.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class GuardTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

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

    private fun updateTileState() {
        val tile = qsTile ?: return
        val app = application as PrivacyGuardApp
        val isRunning = app.protectionRepository.dashboardState.value.engineStatus == EngineStatus.RUNNING

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) getString(R.string.tile_active) else getString(R.string.tile_inactive)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield_check)
        tile.updateTile()
    }
}
