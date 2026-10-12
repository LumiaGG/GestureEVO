package io.github.lumiagg.gestureevo.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceFreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.FreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.FreeTriggerFloating
import io.github.lumiagg.gestureevo.settings.autoOpenAccessibilityService
import io.github.lumiagg.gestureevo.settings.evoAccessibilityServiceIsEnable
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class GestureTileService : TileService(), CoroutineScope {
    lateinit var job: Job

    // CoroutineScope implementation
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    override fun onStartListening() {
        super.onStartListening()
        // Called when the tile becomes visible; update tile state or register listeners.
        val tile = qsTile
        val isRunning =
            evoAccessibilityServiceIsEnable(applicationContext) && FreeTriggerFloating.realTriggerIsEnable
        if (!isRunning) {
            launch {
                if (applicationContext.settingsStore.readKey("gesture_tile_enable", true)) {
                    autoOpenAccessibilityService(applicationContext)
                    FreeTriggerFloating.expectTriggerIsEnable = true
                    produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, isEnable = true))
                }
            }
        }
        tile.state =
            if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile
        if (tile.state == Tile.STATE_ACTIVE) {
            tile.state = Tile.STATE_INACTIVE
            launch {
                applicationContext.settingsStore.saveKey("gesture_tile_enable", false)
                FreeTriggerFloating.expectTriggerIsEnable = false
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, isEnable = false))
            }
        } else {
            tile.state = Tile.STATE_ACTIVE
            launch {
                applicationContext.settingsStore.saveKey("gesture_tile_enable", true)
                autoOpenAccessibilityService(applicationContext)
                FreeTriggerFloating.expectTriggerIsEnable = true
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, isEnable = true))
            }
        }
        tile.updateTile()
    }

    override fun onCreate() {
        super.onCreate()
        job = Job()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
