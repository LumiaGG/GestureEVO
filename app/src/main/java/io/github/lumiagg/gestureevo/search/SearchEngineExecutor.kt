package io.github.lumiagg.gestureevo.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceSearchEvent
import io.github.lumiagg.gestureevo.gesture.SearchEvent
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.inspire.InspireDataHolder
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.search.SearchHistoryManager
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.app.isActivityExported
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SearchEngineExecutor(
    val activity: AppCompatActivity,
    val engine: SearchEngine,
    val freeFrom: Boolean,
    val executedCallback: () -> Unit
) {
    private val uiDispatcher = Dispatchers.Main.immediate
    private val ioDispatcher = Dispatchers.IO
    private val imageShareLauncher by lazy { ImageShareLauncher(activity.applicationContext) }

    fun execute(
        query: String = "",
        bitmapHandle: ManagedBitmap? = null,
        imageUris: List<Uri> = emptyList()
    ) =
        activity.lifecycleScope.launch(uiDispatcher) {
            val triggerSide = if (activity is QuickSearchActivity) {
                activity.triggerSide
            } else {
                InspireDataHolder.triggerSide
            }
            when (engine.engineType) {
                EngineType.DIRECT_LINK -> {
                    val pkg = engine.targetPackage ?: ""
                    var searchUrl = engine.searchLink?.replace("%s", Uri.encode(query))
                    searchUrl = searchUrl?.replace("%q", query)

                    val link = uriBuilder(GestureActionType.OTHER_LINK)
                        .appendQueryParameter("uri", searchUrl)
                        .appendQueryParameter("pkg", pkg)
                        .appendQueryParameter("freeform", freeFrom.toString())
                        .appendQueryParameter("useCustomTabs", engine.useCustomTabs.toString())
                        .apply {
                            triggerSide?.let { appendQueryParameter("triggerSide", it) }
                        }
                        .build()
                    ActionProxy(activity, link.toString()).execute(side = triggerSide)
                }

                EngineType.EXTERN_JUMP_LINK -> {
                    val pkg = engine.targetPackage ?: ""
                    val activityName = engine.targetActivity ?: ""
                    val externLink = engine.externJumpLink ?: ""
                    if (pkg.isEmpty() || externLink.isEmpty()) {
                        return@launch
                    }
                    val link = uriBuilder(GestureActionType.OTHER_LINK)
                        .appendQueryParameter("uri", externLink)
                        .appendQueryParameter("pkg", engine.externJumpPackage)
                        .appendQueryParameter("freeform", freeFrom.toString())
                        .apply {
                            triggerSide?.let { appendQueryParameter("triggerSide", it) }
                        }
                        .build()

                    ActionProxy(activity, link.toString()).execute(side = triggerSide)

                    produceSearchEvent(
                        SearchEvent(
                            InjectionTask(
                                query = query,
                                targetPackage = pkg,
                                targetActivity = activityName,
                                inputEnter = engine.autoInputEnter
                            )
                        )
                    )
                }

                EngineType.JUMP_TO_ACTIVITY -> {
                    val pkg = engine.targetPackage ?: ""
                    val activityName = engine.targetActivity ?: ""
                    if (pkg.isEmpty() || activityName.isEmpty()) {
                        return@launch
                    }
                    val intent = Intent().apply {
                        component = ComponentName(pkg, activityName)
                    }
                    val uri = intent.toUri(Intent.URI_INTENT_SCHEME)
                    val isExported = withContext(ioDispatcher) {
                        isActivityExported(activity, pkg, activityName)
                    }
                    val link = uriBuilder(GestureActionType.OPEN_ACTIVITY)
                        .appendQueryParameter("uri", uri)
                        .appendQueryParameter("pkg", pkg)
                        .appendQueryParameter("activityName", activityName)
                        .appendQueryParameter(
                            "isExported",
                            isExported.toString()
                        )
                        .appendQueryParameter("freeform", freeFrom.toString())
                        .apply {
                            triggerSide?.let { appendQueryParameter("triggerSide", it) }
                        }
                        .build()

                    ActionProxy(activity, link.toString()).execute(side = triggerSide)

                    produceSearchEvent(
                        SearchEvent(
                            InjectionTask(
                                query = query,
                                targetPackage = pkg,
                                targetActivity = activityName,
                                inputEnter = engine.autoInputEnter
                            )
                        )
                    )
                }

                EngineType.SHARE_TO_APP -> {
                    val pkg = engine.targetPackage ?: return@launch

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, query)
                        if (!engine.targetActivity.isNullOrEmpty()) {
                            setClassName(pkg, engine.targetActivity ?: "")
                        } else {
                            setPackage(pkg)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val uri = shareIntent.toUri(Intent.URI_INTENT_SCHEME)
                    val link = uriBuilder(GestureActionType.OTHER_LINK)
                        .appendQueryParameter("uri", uri)
                        .appendQueryParameter("pkg", pkg)
                        .appendQueryParameter("freeform", freeFrom.toString())
                        .apply {
                            triggerSide?.let { appendQueryParameter("triggerSide", it) }
                        }
                        .build()

                    ActionProxy(activity, link.toString()).execute(side = triggerSide)
                }

                EngineType.SHARE_IMAGE_TO_APP -> {
                    if (!executeShareImageSearch(bitmapHandle, imageUris, query, triggerSide)) {
                        return@launch
                    }
                }
            }
            executedAfterSearch(query)
        }

    private suspend fun executeShareImageSearch(
        bitmapHandle: ManagedBitmap?,
        imageUris: List<Uri>,
        query: String,
        triggerSide: String? = null
    ): Boolean {
        val pkg = engine.targetPackage ?: return false
        val result = when {
            imageUris.isNotEmpty() -> {
                imageShareLauncher.launchForSearchEngine(
                    activity = activity,
                    imageUris = imageUris,
                    targetPackage = pkg,
                    targetActivity = engine.targetActivity,
                    freeform = freeFrom,
                    triggerSide = triggerSide
                )
            }

            bitmapHandle != null -> {
                bitmapHandle.acquire()?.use { handle ->
                    imageShareLauncher.launchForSearchEngine(
                        activity = activity,
                        bitmap = handle.requireBitmap(),
                        targetPackage = pkg,
                        targetActivity = engine.targetActivity,
                        freeform = freeFrom,
                        triggerSide = triggerSide
                    )
                } ?: ImageShareLauncher.LaunchResult.LAUNCH_FAILED
            }

            else -> {
                ToastHelper.showInUIThread(
                    activity.applicationContext,
                    activity.getString(io.github.lumiagg.gestureevo.R.string.search_engine_image_required)
                )
                return false
            }
        }

        return when (result) {
            ImageShareLauncher.LaunchResult.SUCCESS -> {
                if (!freeFrom) {
                    executedAfterSearch(query)
                }
                true
            }

            ImageShareLauncher.LaunchResult.TARGET_NOT_FOUND -> {
                ToastHelper.showInUIThread(
                    activity.applicationContext,
                    activity.getString(io.github.lumiagg.gestureevo.R.string.inspire_share_image_target_failed)
                )
                false
            }

            ImageShareLauncher.LaunchResult.LAUNCH_FAILED -> {
                ToastHelper.showInUIThread(
                    activity.applicationContext,
                    activity.getString(io.github.lumiagg.gestureevo.R.string.inspire_share_image_failed)
                )
                false
            }
        }
    }

    private suspend fun executedAfterSearch(query: String) {
        if (query.isNotBlank()) {
            withContext(ioDispatcher) {
                SearchHistoryManager.add(activity.applicationContext, query)
            }
        }
        executedCallback()
    }

}
