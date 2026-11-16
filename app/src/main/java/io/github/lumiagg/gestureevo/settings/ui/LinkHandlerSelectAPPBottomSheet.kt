package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import androidx.lifecycle.LifecycleCoroutineScope

/**
 * App picker used when a link rule needs a target application.
 *
 * The dispatch rules live in [io.github.lumiagg.gestureevo.link] now; this file only keeps the
 * picker so rule editing can reuse the same app list as the rest of the settings screens.
 */
class LinkHandlerSelectAPPBottomSheet(
    activity: Activity,
    coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack
) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack)
