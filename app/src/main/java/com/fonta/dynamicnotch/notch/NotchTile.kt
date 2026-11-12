package com.fonta.dynamicnotch.notch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fonta.dynamicnotch.data.SettingsStore

/** The Quick Settings tile: turns the notch on and off. */
class NotchTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val on = !SettingsStore.settings.notchEnabled
        SettingsStore.update { it.copy(notchEnabled = on) }
        if (!on) NotchState.close(force = true)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = SettingsStore.settings.notchEnabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            !NotchState.serviceRunning -> "Service off"
            on -> "On"
            else -> "Off"
        }
        tile.updateTile()
    }
}
