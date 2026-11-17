package io.github.lumiagg.gestureevo.settings.ui

import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lumiagg.gestureevo.pointer.radar.LinkRadarPreferences
import io.github.lumiagg.gestureevo.utils.observeKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Both entry points observe the same preference, including changes made on the other page. */
internal fun AppCompatActivity.bindLinkRadarSwitch(switch: CompoundButton) {
    var applyingStoredValue = false
    switch.setOnCheckedChangeListener { _, checked ->
        if (!applyingStoredValue) {
            lifecycleScope.launch { settingsStore.saveKey(LinkRadarPreferences.KEY_ENABLED, checked) }
        }
    }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            settingsStore.observeKey(LinkRadarPreferences.KEY_ENABLED, LinkRadarPreferences.DEFAULT_ENABLED)
                .distinctUntilChanged().collect { enabled ->
                    applyingStoredValue = true
                    try { switch.isChecked = enabled } finally { applyingStoredValue = false }
                }
        }
    }
}
