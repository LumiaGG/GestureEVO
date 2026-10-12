package io.github.lumiagg.gestureevo.tile

import androidx.core.graphics.drawable.toDrawable

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.FrameLayout
import io.github.lumiagg.gestureevo.utils.ActionProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class ScreenContentService : TileService(), CoroutineScope {
    lateinit var job: Job

    // CoroutineScope implementation
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    override fun onStartListening() {
        super.onStartListening()
        // Called when the tile becomes visible; update tile state or register listeners.
        val tile = qsTile
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val uri = Uri.Builder()
            .scheme("gesture-evo")
            .authority("action")
            .path("/SCREEN_CONTENT")
            .build()
        collapseAndExecute(uri.toString())
    }

    private fun collapseAndExecute(link: String) {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(FrameLayout(this@ScreenContentService))
            setCancelable(true)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window?.setDimAmount(0f)
            setOnShowListener {
                dismiss()
                launch {
                    ActionProxy(applicationContext, link).execute()
                }
            }
        }
        showDialog(dialog)
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
