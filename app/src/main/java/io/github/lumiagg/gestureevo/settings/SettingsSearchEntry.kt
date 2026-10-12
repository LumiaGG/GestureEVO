package io.github.lumiagg.gestureevo.settings

import android.app.Activity
import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.StringRes

data class SettingsSearchEntry(
    val id: String,
    @StringRes val titleResId: Int,
    @StringRes val pageTitleResId: Int,
    val targetActivity: Class<out Activity>,
    @IdRes val targetViewId: Int = View.NO_ID,
    val targetPreferenceKey: String? = null,
    @StringRes val summaryResId: Int? = null,
    val keywords: List<String> = emptyList()
)
