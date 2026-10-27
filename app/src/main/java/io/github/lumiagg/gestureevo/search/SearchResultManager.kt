package io.github.lumiagg.gestureevo.search

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.datastore.preferences.core.Preferences
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityQuickSearchBinding
import io.github.lumiagg.gestureevo.databinding.SearchResultItemBinding
import io.github.lumiagg.gestureevo.databinding.SearchResultPasteImageItemBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.SearchResultPinModel
import io.github.lumiagg.gestureevo.db.ShortcutModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.db.getHiddenSearchResultDao
import io.github.lumiagg.gestureevo.db.getSearchResultPinDao
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.inspire.InspireHelperUtils
import io.github.lumiagg.gestureevo.search.config.SearchResult
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_BOTTOM_INPUT_LAYOUT
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_RESULT_DOUBLE_COLUMN
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_SEARCH_CONTACT
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_SEARCH_HISTORY
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_SEARCH_SETTINGS_SHORTCUTS
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_SHOW_CLIPBOARD_CONTENT
import io.github.lumiagg.gestureevo.settings.SettingsActivity
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.ui.ActionTextMenuPopup
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.PhoneDetector
import io.github.lumiagg.gestureevo.utils.PhoneDetector.PhoneMatch
import io.github.lumiagg.gestureevo.utils.PinyinUtils
import io.github.lumiagg.gestureevo.utils.TextUtil
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ln1p

class SearchResultManager(
    private val activity: QuickSearchActivity,
    private val binding: ActivityQuickSearchBinding
) {
    private val uiDispatcher = Dispatchers.Main.immediate
    private val computeDispatcher = Dispatchers.Default
    private val ioDispatcher = Dispatchers.IO
    private val imageLoadMutex = Mutex()
    private val imageJobs = WeakHashMap<View, Job>()

    private var contactSearchEnabled = false
    private var settingsShortcutsSearchEnabled = false
    private var searchHistoryEnabled = true
    private var bottomInputLayoutEnabled = false
    private var resultDoubleColumnEnabled = false
    private var resultGridLayout: SearchResultGridLayout? = null
    private var showClipboardContentEnabled = true
    private val lifecycleScope: LifecycleCoroutineScope
        get() = activity.lifecycleScope

    // State management
    private val initMutex = Mutex()
    private val resultPinMutex = Mutex()
    private var cachedPinQuery: String? = null
    private var cachedPinOrder: Map<String, Int> = emptyMap()
    private var displayedPinOrder: Map<String, Int> = emptyMap()
    private var hiddenResultKeys: Set<String> = emptySet()

    @Volatile
    private var isInitialized = false
    private var initializationJob: Job? = null
    private var searchTask: SearchTask? = null
    private var lastResults: List<SearchResult> = emptyList()
    private var lastResultsQuery: String = ""
    private var lastRenderedResults: List<SearchResult>? = null
    private var panelHeightAnimator: ValueAnimator? = null
    private var isTransitioning = false
    private var pendingResults: List<SearchResult>? = null
    private var revealingKeys: Set<String> = emptySet()
    private var revealProgress = 1f
    private var lastAvailablePanelHeight = -1
    private val availableSpaceLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            onAvailableSpaceChanged()
        }
    private val panelHeightInterpolator = FastOutSlowInInterpolator()
    private val panelCollapseInterpolator = FastOutLinearInInterpolator()
    private val gestureActionSearchCache = mutableMapOf<String, List<String>>()
    private val appSearchNormalizeRegex = Regex("[\\s\\n\\r\\t_\\-./\\\\]+")

    private data class SearchTask(
        val query: String,
        val job: Job? = null
    )

    data class QueryAnalysis(
        val text: String,
        val urls: List<String>,
        val phoneMatches: List<PhoneMatch>
    )

    private data class GestureActionSearchIndex(
        val tokens: List<String>
    )

    private data class LoadedResults(
        val results: List<SearchResult>,
        val pinOrder: Map<String, Int>
    )

    companion object {
        private const val RESULT_DOUBLE_COLUMN_SPAN_COUNT = 2
        private const val RESULT_VIEW_CACHE_SIZE = 12
    }

    fun initialize(
        preferences: Preferences? = null,
        firstFrame: Deferred<Unit>? = null,
        inputWindowReady: Deferred<Unit>? = null
    ) {
        initializationJob = lifecycleScope.launch(uiDispatcher) {
            initMutex.withLock {
                if (isInitialized) return@launch

                initConfigData(preferences ?: activity.settingsStore.data.first())
                hiddenResultKeys = withContext(ioDispatcher) {
                    getHiddenSearchResultDao().getKeys().toHashSet()
                }
                firstFrame?.await()
                inputWindowReady?.await()
                initResultRecycler()
                bindAvailableSpaceListener()
                activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        panelHeightAnimator?.cancel()
                        binding.searchContainer.removeOnLayoutChangeListener(availableSpaceLayoutListener)
                        binding.topSearchCard.removeOnLayoutChangeListener(availableSpaceLayoutListener)
                        binding.bottomEngineCard.removeOnLayoutChangeListener(availableSpaceLayoutListener)
                    }
                })

                isInitialized = true
                observeHiddenResults()
            }
        }
    }

    private fun initConfigData(preferences: Preferences) {
        contactSearchEnabled = preferences.readKey(KEY_SEARCH_CONTACT, false)
        settingsShortcutsSearchEnabled =
            preferences.readKey(KEY_SEARCH_SETTINGS_SHORTCUTS, false)
        searchHistoryEnabled = preferences.readKey(KEY_SEARCH_HISTORY, true)
        bottomInputLayoutEnabled = preferences.readKey(KEY_BOTTOM_INPUT_LAYOUT, false)
        resultDoubleColumnEnabled = preferences.readKey(KEY_RESULT_DOUBLE_COLUMN, false)
        showClipboardContentEnabled = preferences.readKey(KEY_SHOW_CLIPBOARD_CONTENT, true)
    }

    private fun observeHiddenResults() {
        lifecycleScope.launch(uiDispatcher) {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                getHiddenSearchResultDao().observeKeys().collect { keys ->
                    val newKeys = keys.toHashSet()
                    if (newKeys != hiddenResultKeys) {
                        hiddenResultKeys = newKeys
                        updateSearchResults(binding.searchInput.text?.toString().orEmpty())
                    }
                }
            }
        }
    }

    suspend fun initResultRecycler() {
        resultGridLayout = if (resultDoubleColumnEnabled) SearchResultGridLayout(activity, binding.resultRecycler) else null
        binding.resultRecycler.layoutManager = if (resultDoubleColumnEnabled) {
            GridLayoutManager(activity, RESULT_DOUBLE_COLUMN_SPAN_COUNT).apply {
                reverseLayout = bottomInputLayoutEnabled
                spanSizeLookup = resultGridLayout!!.spanSizeLookup
            }
        } else {
            LinearLayoutManager(activity).apply {
                reverseLayout = bottomInputLayoutEnabled
            }
        }
        if (bottomInputLayoutEnabled) {
            binding.resultRecycler.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    (binding.resultRecycler.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
            }
        }
        applyPanelHeight(binding.resultPanel, 0)
        binding.resultRecycler.itemAnimator = null
        binding.resultRecycler.setItemViewCacheSize(RESULT_VIEW_CACHE_SIZE)
        binding.resultRecycler.setup {
            val resultItemLayout = if (resultDoubleColumnEnabled) {
                R.layout.search_result_grid_item
            } else {
                R.layout.search_result_item
            }
            addType<SearchResult.AppResult>(resultItemLayout)
            addType<SearchResult.ShortcutResult>(resultItemLayout)
            addType<SearchResult.ContactResult>(resultItemLayout)
            addType<SearchResult.HistoryResult>(resultItemLayout)
            addType<SearchResult.ClearHistoryAction>(resultItemLayout)
            addType<SearchResult.UrlResult>(resultItemLayout)
            addType<SearchResult.PhoneResult>(resultItemLayout)
            addType<SearchResult.PasteResult>(resultItemLayout)
            addType<SearchResult.PasteImageResult>(R.layout.search_result_paste_image_item)
            addType<SearchResult.GestureActionResult>(resultItemLayout)
            addType<SearchResult.AppSettingsResult>(resultItemLayout)

            onBind {
                imageJobs.remove(itemView)?.cancel()
                val model = getModel<SearchResult>()
                itemView.alpha = if (searchResultKey(model) in revealingKeys) revealProgress else 1f
                when (model) {
                    is SearchResult.AppResult -> bindAppResult(itemView, model)
                    is SearchResult.ContactResult -> bindContactResult(itemView, model)
                    is SearchResult.ShortcutResult -> bindShortcutResult(itemView, model)
                    is SearchResult.HistoryResult -> bindHistoryResult(itemView, model)
                    is SearchResult.ClearHistoryAction -> bindClearHistoryAction(itemView)
                    is SearchResult.UrlResult -> bindUrlResult(itemView, model)
                    is SearchResult.PhoneResult -> bindPhoneResult(itemView, model)
                    is SearchResult.PasteResult -> bindPasteResult(itemView, model)
                    is SearchResult.PasteImageResult -> bindPasteImageResult(itemView, model)
                    is SearchResult.GestureActionResult -> bindGestureActionResult(itemView, model)
                    is SearchResult.AppSettingsResult -> bindAppSettingsResult(itemView)
                }
                val functionIcon = itemView.findViewById<RoundImageTextView?>(R.id.function_icon)
                val hasMenu = when (model) {
                    is SearchResult.ClearHistoryAction, is SearchResult.PasteImageResult -> false
                    is SearchResult.ShortcutResult -> !model.shortcut.uri.isNullOrBlank()
                    else -> true
                }
                functionIcon?.visibility = if (hasMenu) View.VISIBLE else View.GONE
                if (hasMenu) {
                    functionIcon?.setImage(R.drawable.baseline_menu_24)
                    functionIcon?.setTint()
                }
                if (model !is SearchResult.PasteImageResult) {
                    resultGridLayout?.bind(itemView, model, bindingAdapterPosition)
                }
            }

            onClick(R.id.result_container) {
                when (val result = getModel<SearchResult>()) {
                    is SearchResult.AppResult -> openApp(result.app, false)
                    is SearchResult.ShortcutResult -> openShortcut(result.shortcut, false)
                    is SearchResult.ContactResult -> callContact(activity, result.contact)
                    is SearchResult.HistoryResult -> clickText(result.text)
                    is SearchResult.ClearHistoryAction -> confirmClearAllHistory()
                    is SearchResult.UrlResult -> openUrl(result.url)
                    is SearchResult.PhoneResult -> callPhone(result.phone)
                    is SearchResult.PasteResult -> clickText(result.text)
                    is SearchResult.PasteImageResult -> clickImage(result.uri)
                    is SearchResult.GestureActionResult -> executeGestureAction(result.actionType)
                    is SearchResult.AppSettingsResult -> openGestureEvoSettings()
                }
            }

            onLongClick(R.id.result_container) {
                when (val result = getModel<SearchResult>()) {
                    is SearchResult.AppResult -> openApp(result.app, true)
                    is SearchResult.ShortcutResult -> openShortcut(result.shortcut, true)
                    is SearchResult.HistoryResult -> removeHistory(result.text)
                    is SearchResult.ContactResult -> copyContactNumber(activity, result.contact)

                    else -> {}
                }
            }

            onClick(R.id.function_icon) {
                showResultOptions(getModel<SearchResult>(), itemView)
            }

        }
    }

    private fun bindAppResult(itemView: View, model: SearchResult.AppResult) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = model.app.name
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        itemBinding.icon.applyGlobalImageRoundPercent = true
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.baseline_image_24)
        loadResultImage(itemView, { IconHelper.getIcon(activity, model.app.pkgName) }) { icon ->
            itemBinding.icon.applyGlobalImageRoundPercent = true
            itemBinding.icon.setImage(icon)
            itemBinding.icon.clearTint()
        }
    }

    private fun bindShortcutResult(itemView: View, model: SearchResult.ShortcutResult) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = model.shortcut.name
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        itemBinding.icon.applyGlobalImageRoundPercent = true
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.baseline_settings_24)
        loadResultImage(itemView, { IconHelper.getIcon(activity, "com.android.settings") }) { icon ->
            itemBinding.icon.applyGlobalImageRoundPercent = true
            itemBinding.icon.setImage(icon)
            itemBinding.icon.clearTint()
        }
    }

    private fun bindContactResult(itemView: View, model: SearchResult.ContactResult) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = activity.getString(R.string.contact_result_label, model.contact.name, model.contact.phoneNumber)
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        val photoUri = model.contact.photoUri
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.person_24px)
        if (photoUri != null) {
            loadResultImage(itemView, { decodeSampledBitmapFromUri(photoUri.toUri(), 128, 128) }) { bitmap ->
                itemBinding.icon.applyGlobalImageRoundPercent = true
                itemBinding.icon.setImage(bitmap)
                itemBinding.icon.clearTint()
            }
        }
    }

    private fun bindHistoryResult(itemView: View, model: SearchResult.HistoryResult) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = model.text
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.history_24px)
    }

    private fun bindClearHistoryAction(itemView: View) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = activity.getString(R.string.quick_search_clear_all_history)
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.clear_all_24px)
    }

    private fun bindUrlResult(itemView: View, model: SearchResult.UrlResult) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = model.url
        applyResultTextStyle(itemBinding.resultText, isUrl = true)
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.link_20px)
    }

    private fun bindPhoneResult(itemView: View, model: SearchResult.PhoneResult) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = model.phone
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.call_20px)
    }

    private fun bindPasteResult(itemView: View, model: SearchResult.PasteResult) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = model.text
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.content_paste_go_20px)
    }

    private fun bindPasteImageResult(itemView: View, model: SearchResult.PasteImageResult) {
        val itemBinding = SearchResultPasteImageItemBinding.bind(itemView)
        itemBinding.imagePreview.setImageResource(R.drawable.baseline_image_24)
        loadResultImage(itemView, { decodeSampledBitmapFromUri(model.uri, 800, 800) }) { bitmap ->
            itemBinding.imagePreview.setImageBitmap(bitmap)
        }
    }

    private fun <T : Any> loadResultImage(itemView: View, load: () -> T?, apply: (T) -> Unit) {
        imageJobs[itemView] = lifecycleScope.launch(uiDispatcher) {
            try {
                val image = withContext(ioDispatcher) { imageLoadMutex.withLock { load() } }
                if (image != null) apply(image)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the placeholder for unavailable packages, photos, or revoked URI grants.
            }
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            openUriInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            val height: Int = options.outHeight
            val width: Int = options.outWidth
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false

            openUriInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openUriInputStream(uri: Uri): java.io.InputStream? {
        return try {
            activity.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            uri.path?.let { path ->
                val file = java.io.File(path)
                if (file.exists()) java.io.FileInputStream(file) else null
            }
        }
    }

    private fun bindGestureActionResult(
        itemView: View,
        model: SearchResult.GestureActionResult
    ) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = model.actionType.showName(activity)
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        bindBuiltInResultIcon(itemBinding.icon, actionIconRes(model.actionType))
    }

    private fun bindAppSettingsResult(itemView: View) {
        val itemBinding = SearchResultItemBinding.bind(itemView)
        itemBinding.resultText.text = activity.getString(R.string.quick_search_app_settings)
        applyResultTextStyle(itemBinding.resultText, isUrl = false)
        bindBuiltInResultIcon(itemBinding.icon, R.drawable.baseline_settings_24)
    }

    private fun bindBuiltInResultIcon(icon: RoundImageTextView, resId: Int) {
        icon.applyGlobalImageRoundPercent = false
        icon.setImageWithBackplate(resId, iconInset = 6.dp2px.toInt())
    }

    private fun applyResultTextStyle(textView: TextView, isUrl: Boolean) {
        val maxLineCount = if (isUrl || resultDoubleColumnEnabled) 2 else 1
        textView.ellipsize = TextUtils.TruncateAt.END
        if (isUrl) {
            textView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (resultDoubleColumnEnabled) 13f else 14f
            )
        } else {
            textView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (resultDoubleColumnEnabled) 15f else 16f
            )
        }
        textView.isSingleLine = maxLineCount == 1
        textView.setHorizontallyScrolling(false)
        textView.maxLines = maxLineCount
    }

    fun updateSearchResults(query: String) {
        updateSearchResults(
            QueryAnalysis(
                text = query,
                urls = UrlDetector.findUrls(query),
                phoneMatches = PhoneDetector.findPhoneMatches(query)
            )
        )
    }

    suspend fun awaitCurrentSearch() {
        initializationJob?.join()
        // The IME may notify TextWatcher again after its newline is filtered out.
        // If that replaced the task while we waited, wait for the replacement too.
        while (true) {
            val task = searchTask
            task?.job?.join()
            if (task === searchTask) return
        }
    }

    fun updateSearchResults(analysis: QueryAnalysis) {
        // Cancel previous search task
        searchTask?.job?.cancel()

        val newJob = lifecycleScope.launch(uiDispatcher) {
            // Check initialization state
            if (!isInitialized) {
                // Await initialization completion
                initializationJob?.join()
            }

            val query = analysis.text
            val (results, pinOrder) = loadSearchResults(analysis, query)
            lastResults = results
            lastResultsQuery = normalizePinQuery(query)
            displayedPinOrder = pinOrder

            if ((pendingResults ?: lastRenderedResults) == results) return@launch
            transitionResults(results)
        }

        // Save current search task
        searchTask = SearchTask(analysis.text, newJob)
    }

    private fun bindAvailableSpaceListener() {
        binding.searchContainer.addOnLayoutChangeListener(availableSpaceLayoutListener)
        binding.topSearchCard.addOnLayoutChangeListener(availableSpaceLayoutListener)
        binding.bottomEngineCard.addOnLayoutChangeListener(availableSpaceLayoutListener)
    }

    internal fun onAvailableSpaceChanged() {
        val available = maxResultPanelHeight()
        if (available == Int.MAX_VALUE || available == lastAvailablePanelHeight) return
        lastAvailablePanelHeight = available
        relayoutResultPanelForSpace()
    }

    private fun relayoutResultPanelForSpace() {
        // The running timeline clamps against the latest keyboard space each frame.
        if (panelHeightAnimator != null || isTransitioning) return
        val results = lastRenderedResults ?: return
        if (results.isEmpty() || !binding.resultPanel.isVisible) return
        applyPanelHeight(binding.resultPanel, resultPanelHeight(results.size))
    }

    private fun applyResults(results: List<SearchResult>) {
        resultGridLayout?.updateResults(results)
        // Commit on the UI thread at the timeline's swap point. An asynchronous diff
        // could otherwise replace the contents after they have already faded in.
        binding.resultRecycler.models = results
        lastRenderedResults = results
    }

    private fun updateRevealingItems() {
        val recycler = binding.resultRecycler
        val results = lastRenderedResults.orEmpty()
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index)
            val model = results.getOrNull(recycler.getChildAdapterPosition(child))
            child.alpha = if (model != null && searchResultKey(model) in revealingKeys) revealProgress else 1f
        }
    }

    private fun resultPanelHeight(itemCount: Int): Int =
        binding.resultRecycler.heightForCount(itemCount).coerceAtMost(maxResultPanelHeight())

    private fun maxResultPanelHeight(): Int {
        val parent = binding.searchContainer
        if (parent.height <= 0) return Int.MAX_VALUE
        val card = binding.topSearchCard
        val margins = card.layoutParams as? ViewGroup.MarginLayoutParams
        val cardGap = activity.resources.getDimensionPixelSize(R.dimen.quick_search_card_gap)
        val inputHeight = binding.searchInputRow.height.takeIf { it > 0 }
            ?: binding.searchInputRow.layoutParams?.height?.coerceAtLeast(0)
            ?: 64.dp2px.toInt()
        val dragBar = binding.root.findViewById<View>(R.id.drag_bar_container)
        val dragBarHeight = if (dragBar?.isVisible == true) {
            dragBar.height.takeIf { it > 0 }
                ?: dragBar.layoutParams?.height?.coerceAtLeast(0)
                ?: 24.dp2px.toInt()
        } else {
            0
        }
        val staticTopCardHeight = inputHeight + dragBarHeight + card.paddingTop + card.paddingBottom
        val topMargin = (margins?.topMargin ?: 0).coerceAtLeast(8.dp2px.toInt())
        val bottomMargin = (margins?.bottomMargin ?: 0).coerceAtLeast(8.dp2px.toInt())

        val available = if (bottomInputLayoutEnabled) {
            val engineHeight = if (binding.bottomEngineCard.isVisible) {
                var h = binding.bottomEngineCard.height.takeIf { it > 0 }
                    ?: binding.bottomEngineCard.measuredHeight.takeIf { it > 0 }
                    ?: 0
                if (h <= 0) {
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(
                        (card.width.takeIf { it > 0 } ?: parent.width).coerceAtLeast(1),
                        View.MeasureSpec.AT_MOST
                    )
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    binding.bottomEngineCard.measure(widthSpec, heightSpec)
                    h = binding.bottomEngineCard.measuredHeight
                }
                if (h <= 0) {
                    h = 72.dp2px.toInt()
                }
                h
            } else {
                0
            }
            val staticHeight = staticTopCardHeight + engineHeight
            val cardBottom = parent.height - parent.paddingBottom - bottomMargin
            val topLimit = parent.paddingTop + topMargin
            val maxCardHeight = (cardBottom - topLimit).coerceAtLeast(0)
            return (maxCardHeight - staticHeight).coerceAtLeast(0)
        } else {
            val topLimit = card.top.takeIf { it > 0 }
                ?: (parent.paddingTop + topMargin)
            val bottomLimit = if (binding.bottomEngineCard.isVisible) {
                val engineTop = binding.bottomEngineCard.top
                if (engineTop > 0) {
                    engineTop - cardGap
                } else {
                    val engineHeight = binding.bottomEngineCard.height.takeIf { it > 0 } ?: 72.dp2px.toInt()
                    val engineBottomMargin = (binding.bottomEngineCard.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: bottomMargin
                    parent.height - parent.paddingBottom - engineBottomMargin - engineHeight - cardGap
                }
            } else {
                parent.height - parent.paddingBottom - bottomMargin
            }
            (bottomLimit - topLimit).coerceAtLeast(0)
        }
        return (available - staticTopCardHeight).coerceAtLeast(0)
    }

    private fun applyPanelHeight(panel: View, height: Int) {
        val clamped = height.coerceAtLeast(0)
        val lp = panel.layoutParams
        if (lp.height != clamped) {
            lp.height = clamped
            panel.layoutParams = lp
        }
    }

    private fun transitionResults(results: List<SearchResult>) {
        val panel = binding.resultPanel
        val recycler = binding.resultRecycler
        val previous = lastRenderedResults.orEmpty()
        val wasAnimating = panelHeightAnimator != null
        panelHeightAnimator?.cancel()
        isTransitioning = true
        pendingResults = results
        val fromHeight = if (panel.isVisible) panel.height.coerceAtLeast(0) else 0
        val fromAlpha = recycler.alpha

        // Ensure the panel starts at fromHeight (e.g. 0) before making it visible,
        // so it does NOT measure at wrap_content and jump up on the first layout pass.
        applyPanelHeight(panel, fromHeight)

        // Only append without fading existing rows when their order/content is unchanged.
        val appendOnly = !wasAnimating && results.size > previous.size &&
            results.take(previous.size) == previous
        val fadeOld = results.isEmpty() && previous.isNotEmpty() && fromHeight > 0 && fromAlpha > 0f
        val fadeOutMillis = if (fadeOld) 70L else 0L
        var swapped = false
        var targetHeight = fromHeight

        fun swapResults() {
            if (swapped) return
            swapped = true
            revealingKeys = if (appendOnly) {
                results.drop(previous.size).map(::searchResultKey).toSet()
            } else emptySet()
            revealProgress = if (appendOnly) 0f else 1f
            applyResults(results)
            targetHeight = if (results.isEmpty()) 0 else resultPanelHeight(results.size)
            recycler.alpha = if (appendOnly) 1f else if (results.isEmpty()) 0f else 1f
            updateRevealingItems()
        }

        if (!fadeOld) swapResults()
        if (results.isNotEmpty()) panel.isVisible = true
        // One clock owns both content opacity and height; RecyclerView's item animator stays off.
        val resizeMillis = if (fadeOld) 200L else
            (180L + abs(targetHeight - fromHeight) / 3).coerceIn(180L, 300L)
        val totalMillis = fadeOutMillis + resizeMillis
        panelHeightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalMillis
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val elapsed = animator.animatedFraction * totalMillis
                if (elapsed < fadeOutMillis) {
                    recycler.alpha = fromAlpha * (1f - elapsed / fadeOutMillis)
                    applyPanelHeight(panel, fromHeight.coerceAtMost(maxResultPanelHeight()))
                } else {
                    swapResults()
                    val progress = ((elapsed - fadeOutMillis) / resizeMillis).coerceIn(0f, 1f)
                    val eased = if (results.isEmpty()) panelCollapseInterpolator.getInterpolation(progress)
                        else panelHeightInterpolator.getInterpolation(progress)
                    targetHeight = if (results.isEmpty()) 0 else resultPanelHeight(results.size)
                    applyPanelHeight(panel, (fromHeight + (targetHeight - fromHeight) * eased).toInt()
                        .coerceAtMost(maxResultPanelHeight()))
                    if (appendOnly) {
                        revealProgress = eased
                        updateRevealingItems()
                    } else if (results.isEmpty()) {
                        recycler.alpha = (1f - progress)
                    } else {
                        recycler.alpha = 1f
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (panelHeightAnimator !== animation) return
                    panelHeightAnimator = null
                    pendingResults = null
                    isTransitioning = false
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (panelHeightAnimator !== animation) return
                    swapResults()
                    panelHeightAnimator = null
                    pendingResults = null
                    revealingKeys = emptySet()
                    revealProgress = 1f
                    recycler.alpha = 1f
                    updateRevealingItems()
                    panel.isVisible = results.isNotEmpty()
                    applyPanelHeight(panel, if (results.isEmpty()) 0 else resultPanelHeight(results.size))
                    isTransitioning = false
                }
            })
        }
        panelHeightAnimator?.start()
    }

    private fun searchResultKey(result: SearchResult): String = when (result) {
        is SearchResult.AppResult -> "app:${result.app.pkgName}:${result.app.userId}"
        is SearchResult.ShortcutResult ->
            "shortcut:${result.shortcut.id}:${result.shortcut.name}:${result.shortcut.uri}"
        is SearchResult.ContactResult -> "contact:${result.contact.phoneNumber}:${result.contact.name}"
        is SearchResult.HistoryResult -> "history:${result.text}"
        is SearchResult.UrlResult -> "url:${result.url}"
        is SearchResult.PhoneResult -> "phone:${result.phone}"
        is SearchResult.PasteResult -> "paste:${result.text}"
        is SearchResult.PasteImageResult -> "paste_image:${result.uri}"
        is SearchResult.GestureActionResult -> "gesture:${result.actionType.name}"
        is SearchResult.ClearHistoryAction -> "clear-history"
        is SearchResult.AppSettingsResult -> "app-settings"
    }

    private suspend fun loadSearchResults(
        analysis: QueryAnalysis,
        query: String
    ): LoadedResults = coroutineScope {
        val actionDeferred = async(computeDispatcher) { buildActionResults(analysis) }
        val contentDeferred = async { loadContentResults(query) }
        val pinQuery = normalizePinQuery(query)
        val pinsDeferred = async { loadResultPinOrder(pinQuery) }
        val results = actionDeferred.await() + contentDeferred.await()
        val pinOrder = pinsDeferred.await()
        LoadedResults(applyResultPins(filterHiddenResults(results, hiddenResultKeys), pinOrder), pinOrder)
    }

    private suspend fun loadResultPinOrder(query: String): Map<String, Int> =
        resultPinMutex.withLock {
            if (cachedPinQuery == query) return@withLock cachedPinOrder
            // Keep only this query, including an empty result, for the lifetime of the search page.
            cachedPinQuery = null
            cachedPinOrder = emptyMap()
            val pins = getSearchResultPinDao().findByQuery(query)
            val order = if (pins.isEmpty()) emptyMap() else {
                val byKey = HashMap<String, Int>(pins.size)
                pins.forEachIndexed { index, pin ->
                    byKey[pin.resultKey] = index
                }
                byKey
            }
            cachedPinQuery = query
            cachedPinOrder = order
            order
        }

    private suspend fun loadContentResults(query: String): List<SearchResult> {
        if (query.isEmpty()) return emptyList()

        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() && query.contains(' ')) {
            return withContext(ioDispatcher) {
                SearchHistoryManager.getAll()
                    .map { SearchResult.HistoryResult(it) }
                    .withClearHistoryAction()
            }
        }

        return coroutineScope {
            val historyDeferred = async { filterHistory(normalizedQuery) }
            val appDeferred = async { filterApps(normalizedQuery) }
            val shortcutDeferred = async { filterShortcuts(normalizedQuery) }
            val contactDeferred = async { filterContacts(normalizedQuery) }

            appDeferred.await() +
                    shortcutDeferred.await() +
                    contactDeferred.await() +
                    historyDeferred.await()
        }
    }

    fun triggerFirstResult(): Boolean {
        val first = lastResults.firstOrNull() ?: return false
        when (first) {
            is SearchResult.AppResult -> openApp(first.app, false)
            is SearchResult.ShortcutResult -> openShortcut(first.shortcut, false)
            is SearchResult.ContactResult -> callContact(activity, first.contact)
            is SearchResult.HistoryResult -> {
                if (first.text == binding.searchInput.text?.toString().orEmpty()) return false
                clickText(first.text)
            }
            is SearchResult.ClearHistoryAction -> return false
            is SearchResult.UrlResult -> openUrl(first.url)
            is SearchResult.PhoneResult -> callPhone(first.phone)
            is SearchResult.PasteResult -> clickText(first.text)
            is SearchResult.PasteImageResult -> clickImage(first.uri)
            is SearchResult.GestureActionResult -> executeGestureAction(first.actionType)
            is SearchResult.AppSettingsResult -> openGestureEvoSettings()
        }
        return true
    }

    private fun buildActionResults(analysis: QueryAnalysis): List<SearchResult> {
        val results = ArrayList<SearchResult>(8)
        val query = analysis.text
        if (query.isEmpty()) {
            if (!showClipboardContentEnabled) return results
            val clipboardImage = ClipboardUtils.getImage(activity.applicationContext)
            if (clipboardImage != null && activity.getAttachedImageUris().isEmpty()) {
                results.add(SearchResult.PasteImageResult(clipboardImage))
            }
            val clipboardText = ClipboardUtils.getText(activity.applicationContext)
            if (clipboardText.isNotEmpty()) {
                results.add(SearchResult.PasteResult(clipboardText))
            }
            return results
        }

        results.addAll(buildGestureActionResults(query))
        if (matchesGestureEvoSettings(query)) {
            results.add(SearchResult.AppSettingsResult)
        }

        val urlSet = LinkedHashSet<String>()
        if (query.contains("#Intent;")) {
            urlSet.add(query)
        }
        analysis.urls.forEach { urlSet.add(it) }
        urlSet.forEach { results.add(SearchResult.UrlResult(it)) }

        val phoneSet = LinkedHashSet<String>()
        analysis.phoneMatches.forEach { phoneSet.add(it.value) }
        phoneSet.forEach { results.add(SearchResult.PhoneResult(it)) }

        return results
    }

    private fun buildGestureActionResults(query: String): List<SearchResult.GestureActionResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()
        val normalizedQueryLowercase = normalizedQuery.lowercase()
        return GestureActionType.entries
            .asSequence()
            .filter { it.isQuickSearchWhitelisted() }
            .filter { actionType ->
                getGestureActionSearchIndex(actionType).tokens.any {
                    it.contains(normalizedQueryLowercase)
                }
            }
            .map { SearchResult.GestureActionResult(it) }
            .toList()
    }

    private fun getGestureActionSearchIndex(actionType: GestureActionType): GestureActionSearchIndex {
        val cacheKey = "${Locale.getDefault().toLanguageTag()}:${actionType.name}"
        val tokens = gestureActionSearchCache.getOrPut(cacheKey) {
            buildGestureActionSearchTokens(actionType)
        }
        return GestureActionSearchIndex(tokens)
    }

    private fun buildGestureActionSearchTokens(actionType: GestureActionType): List<String> {
        val displayName = actionType.showName(activity)
        val pinyinFull = PinyinUtils.getPinyinParse(displayName)
        val pinyinInitials = PinyinUtils.getPinyinFP(displayName)

        return buildList {
            add(displayName.lowercase())
            add(actionType.name.lowercase())
            add(pinyinFull)
            add(pinyinInitials)
        }.filter { it.isNotEmpty() }.distinct()
    }

    private fun openUrl(url: String) {
        lifecycleScope.launch(uiDispatcher) {
            InspireHelperUtils.extractAndOpenURL(activity.applicationContext, url, side = activity.triggerSide)
            activity.finishWithAnimation()
        }
    }

    private fun callPhone(phone: String) {
        activity.openLink("tel:$phone", false)
    }

    private suspend fun filterApps(inputSequence: String): List<SearchResult.AppResult> {
        val normalizedQuery = normalizeAppSearchToken(inputSequence)
        val queryPinyin = normalizeAppSearchToken(PinyinUtils.getPinyinParse(inputSequence))
        val queryInitials = normalizeAppSearchToken(PinyinUtils.getPinyinFP(inputSequence))
        val resultSet = withContext(ioDispatcher) {
            LinkedHashSet<APPModel>().apply {
                addAll(getAPPDao().findByName(inputSequence, APPModel.FLAG_LAUNCHER))
                addAll(getAPPDao().findByInitialsFK(inputSequence, APPModel.FLAG_LAUNCHER))
                addAll(getAPPDao().findByPinYinFK(inputSequence, APPModel.FLAG_LAUNCHER))
            }
        }
        return withContext(computeDispatcher) {
            resultSet
                .map {
                    scoreApp(
                        app = it,
                        normalizedQuery = normalizedQuery,
                        queryPinyin = queryPinyin,
                        queryInitials = queryInitials
                    )
                }
                .sortedWith(
                    compareByDescending<AppSearchScore> { it.totalScore }
                        .thenByDescending { it.matchScore }
                        .thenByDescending { it.recencyScore }
                        .thenByDescending { it.usageScore }
                        .thenByDescending { it.app.lastUsageTime }
                        .thenByDescending { it.app.openCount }
                        .thenBy { it.app.name.lowercase(Locale.ROOT) }
                )
                .map { SearchResult.AppResult(it.app) }
        }
    }

    private fun matchesGestureEvoSettings(query: String): Boolean {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return false
        val title = activity.getString(R.string.quick_search_app_settings).lowercase()
        return listOf(
            title,
            "设置",
            "settings",
            PinyinUtils.getPinyinParse(title),
            PinyinUtils.getPinyinFP(title),
        ).any { token -> token.contains(normalizedQuery) }
    }

    private fun openGestureEvoSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
        activity.finishWithAnimation()
    }

    private suspend fun filterContacts(query: String): List<SearchResult.ContactResult> {
        if (!contactSearchEnabled) return emptyList()
        return try {
            ContactSearchHelper.searchContacts(activity, query).take(10) // Limit number of contact results
                .map { SearchResult.ContactResult(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun filterShortcuts(query: String): List<SearchResult.ShortcutResult> {
        if (!settingsShortcutsSearchEnabled) return emptyList()
        return withContext(ioDispatcher) {
            SettingsShortcutSearchHelper.searchShortcuts(query)
                .map { SearchResult.ShortcutResult(it) }
        }
    }

    private suspend fun filterHistory(query: String): List<SearchResult> {
        if (!searchHistoryEnabled) return emptyList()
        return withContext(ioDispatcher) {
            SearchHistoryManager.search(query)
                .map { SearchResult.HistoryResult(it) }
        }
    }

    private fun openApp(model: APPModel, freeform: Boolean) {
        lifecycleScope.launch(uiDispatcher) {
            val link = uriBuilder(GestureActionType.OPEN_APP)
                .appendQueryParameter("pkg", model.pkgName)
                .appendQueryParameter("freeform", freeform.toString())
                .apply {
                    activity.triggerSide?.let { appendQueryParameter("triggerSide", it) }
                }
                .build()
            ActionProxy(activity, link.toString()).execute(side = activity.triggerSide)
            activity.finishWithAnimation()
        }
    }

    private fun openShortcut(model: ShortcutModel, freeform: Boolean) {
        lifecycleScope.launch(uiDispatcher) {
            val link = uriBuilder(GestureActionType.OTHER_LINK)
                .appendQueryParameter("uri", model.uri)
                .appendQueryParameter("freeform", freeform.toString())
                .apply {
                    activity.triggerSide?.let { appendQueryParameter("triggerSide", it) }
                }
                .build()
            ActionProxy(activity, link.toString()).execute(side = activity.triggerSide)
            activity.finishWithAnimation()
        }
    }

    private fun normalizePinQuery(query: String): String = query.trim().lowercase(Locale.ROOT)

    private fun showResultOptions(result: SearchResult, itemView: View) {
        val resultKey = result.pinKey() ?: return
        // Capture the displayed query so an input change cannot pin a result for another query.
        val query = lastResultsQuery
        val pinned = resultKey in displayedPinOrder
        val anchorView = itemView.findViewById<View>(R.id.function_icon)
        val actions = buildList {
            if (result is SearchResult.AppResult) {
                add(ActionTextMenuItem(activity.getString(R.string.menu_search_app_info)) {
                    openAppSettings(result.app)
                })
            }
            if (result is SearchResult.HistoryResult) {
                add(ActionTextMenuItem(activity.getString(R.string.menu_search_delete_history), destructive = true) {
                    removeHistory(result.text)
                })
            }
            if (result is SearchResult.ContactResult) {
                add(ActionTextMenuItem(activity.getString(R.string.menu_contact_sms)) {
                    sendSmsToContact(activity, result.contact)
                })
                add(ActionTextMenuItem(activity.getString(R.string.menu_contact_copy)) {
                    copyContactNumber(activity, result.contact)
                })
            }
            add(ActionTextMenuItem(
                activity.getString(if (pinned) R.string.menu_search_result_unpin else R.string.menu_search_result_pin)
            ) {
                setResultPinned(query, resultKey, !pinned)
            })
            add(ActionTextMenuItem(activity.getString(R.string.menu_search_result_hide), destructive = true) {
                hideResult(result)
            })
        }
        ActionTextMenuPopup(
            activity,
            binding.root,
            cardBlurEnabled = activity.searchCardBlurEnabled,
            backgroundBlurEnabled = activity.searchWindowBlurEnabled,
        ).show(anchorView, actions)
    }

    private fun hideResult(result: SearchResult) {
        val record = result.hiddenRecord(activity) ?: return
        lifecycleScope.launch(uiDispatcher) {
            try {
                getHiddenSearchResultDao().insert(record)
                if (record.resultKey !in hiddenResultKeys) {
                    hiddenResultKeys = hiddenResultKeys + record.resultKey
                    updateSearchResults(binding.searchInput.text?.toString().orEmpty())
                }
                ToastHelper.showInUIThread(activity.applicationContext, R.string.hidden_search_result_hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                ToastHelper.showInUIThread(activity.applicationContext, R.string.hidden_search_result_update_failed)
            }
        }
    }

    private fun setResultPinned(query: String, resultKey: String, pinned: Boolean) {
        lifecycleScope.launch(uiDispatcher) {
            try {
                resultPinMutex.withLock {
                    val dao = getSearchResultPinDao()
                    if (pinned) {
                        dao.insert(SearchResultPinModel(query, resultKey, System.currentTimeMillis()))
                    } else {
                        dao.delete(query, resultKey)
                    }
                    cachedPinQuery = null
                    cachedPinOrder = emptyMap()
                }
                updateSearchResults(binding.searchInput.text?.toString().orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                ToastHelper.showInUIThread(activity.applicationContext, R.string.quick_search_pin_failed)
            }
        }
    }

    private fun openAppSettings(app: APPModel) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${app.pkgName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun executeGestureAction(actionType: GestureActionType) {
        if (!actionType.isQuickSearchWhitelisted()) return
        lifecycleScope.launch(uiDispatcher) {
            val link = uriBuilder(actionType)
                .apply {
                    activity.triggerSide?.let { appendQueryParameter("triggerSide", it) }
                }
                .build()
            ActionProxy(activity, link.toString()).execute(side = activity.triggerSide)
            activity.finishWithAnimation()
        }
    }

    private fun clickText(text: String) {
        activity.applySearchInput(text)
    }

    private fun clickImage(uri: Uri) {
        activity.attachClipboardImage(uri)
    }

    private fun removeHistory(text: String) = lifecycleScope.launch(uiDispatcher) {
        SearchHistoryManager.remove(text)
        refreshHistoryResults()
    }

    private fun confirmClearAllHistory() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.quick_search_clear_all_history)
            .setMessage(R.string.quick_search_clear_history_confirm_message)
            .setPositiveButton(R.string.quick_search_clear_history_confirm_action) { _, _ ->
                clearAllHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAllHistory() = lifecycleScope.launch(uiDispatcher) {
        SearchHistoryManager.clearAll()
        refreshHistoryResults()
        ToastHelper.showInUIThread(
            activity.applicationContext,
            R.string.quick_search_history_cleared
        )
    }

    private fun actionIconRes(actionType: GestureActionType): Int {
        return when (actionType) {
            GestureActionType.SCREEN_CONTENT -> R.drawable.outline_center_focus_weak_24
            GestureActionType.SCREEN_CONTENT_FULLSCREEN -> R.drawable.ic_screen_content_fullscreen_text_24
            GestureActionType.SCREEN_SEARCH -> R.drawable.search_24px
            GestureActionType.PIN_POOL -> R.drawable.pinboard_24px
            GestureActionType.POINTER -> R.drawable.baseline_control_point_24
            GestureActionType.KEYBOARD -> R.drawable.baseline_keyboard_24
            GestureActionType.FREEFORM_FOREGROUND -> R.drawable.ic_little_window
            GestureActionType.TEMP_DND -> R.drawable.do_not_disturb_on_24px
            GestureActionType.PAUSE_GESTURE -> R.drawable.baseline_gesture_24
            else -> R.drawable.baseline_miscellaneous_services_24
        }
    }

    private fun scoreApp(
        app: APPModel,
        normalizedQuery: String,
        queryPinyin: String,
        queryInitials: String
    ): AppSearchScore {
        val normalizedName = normalizeAppSearchToken(app.name)
        val normalizedPinyin = normalizeAppSearchToken(app.pinyinFK)
        val normalizedInitials = normalizeAppSearchToken(app.initialsFK)

        val nameScore = computeTokenMatchScore(normalizedQuery, normalizedName)
        val includePinyin = normalizedPinyin.isNotEmpty() && normalizedPinyin != normalizedName
        val includeInitials = normalizedInitials.isNotEmpty() &&
                normalizedInitials != normalizedName &&
                normalizedInitials != normalizedPinyin

        val pinyinScore = if (includePinyin) {
            maxOf(
                computeTokenMatchScore(normalizedQuery, normalizedPinyin),
                computeTokenMatchScore(queryPinyin, normalizedPinyin)
            )
        } else {
            0.0
        }
        val initialsScore = if (includeInitials) {
            maxOf(
                computeTokenMatchScore(normalizedQuery, normalizedInitials),
                computeTokenMatchScore(queryInitials, normalizedInitials)
            )
        } else {
            0.0
        }

        val similarityScore = listOfNotNull(
            safeSimilarity(normalizedQuery, normalizedName),
            safeSimilarity(queryPinyin, normalizedPinyin).takeIf { includePinyin },
            safeSimilarity(queryInitials, normalizedInitials).takeIf { includeInitials }
        ).maxOrNull()?.times(28.0) ?: 0.0

        val usageScore = ln1p(app.openCount.coerceAtLeast(0L).toDouble()) * 14.0
        val recencyScore = computeRecencyScore(app.lastUsageTime)
        val matchScore = (nameScore * 1.2) + (pinyinScore * 0.95) + (initialsScore * 0.88) + similarityScore

        return AppSearchScore(
            app = app,
            totalScore = matchScore + usageScore + recencyScore,
            matchScore = matchScore,
            usageScore = usageScore,
            recencyScore = recencyScore
        )
    }

    private fun computeTokenMatchScore(query: String, candidate: String): Double {
        if (query.isBlank() || candidate.isBlank()) return 0.0
        if (candidate == query) return 320.0
        if (candidate.startsWith(query)) {
            val coverageScore = (query.length.toDouble() / candidate.length.coerceAtLeast(1)) * 40.0
            val lengthScore = query.length.coerceAtMost(12) * 2.0
            return 250.0 + coverageScore + lengthScore
        }
        val containsIndex = candidate.indexOf(query)
        if (containsIndex >= 0) {
            val coverageScore = (query.length.toDouble() / candidate.length.coerceAtLeast(1)) * 36.0
            val positionScore = (28 - containsIndex.coerceAtMost(20)).toDouble()
            return 165.0 + coverageScore + positionScore
        }
        return 0.0
    }

    private fun safeSimilarity(query: String, candidate: String): Double {
        if (query.isBlank() || candidate.isBlank()) return 0.0
        val similarity = TextUtil.calculateSimilarity(TextUtil.createVector(query), candidate)
        return if (similarity.isFinite()) similarity else 0.0
    }

    private fun computeRecencyScore(lastUsageTime: Long): Double {
        if (lastUsageTime <= 0L) return 0.0
        val elapsedHours =
            ((System.currentTimeMillis() - lastUsageTime).coerceAtLeast(0L) / 3_600_000.0)
        return when {
            elapsedHours <= 1.0 -> 72.0
            elapsedHours <= 24.0 -> 72.0 - ((elapsedHours - 1.0) / 23.0) * 24.0
            elapsedHours <= 24.0 * 7 -> 48.0 - ((elapsedHours - 24.0) / (24.0 * 6)) * 24.0
            elapsedHours <= 24.0 * 30 -> 24.0 - ((elapsedHours - 24.0 * 7) / (24.0 * 23)) * 16.0
            else -> maxOf(4.0, 8.0 - ln1p(elapsedHours / (24.0 * 30.0)) * 4.0)
        }
    }

    private fun normalizeAppSearchToken(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(appSearchNormalizeRegex, "")
    }

    private data class AppSearchScore(
        val app: APPModel,
        val totalScore: Double,
        val matchScore: Double,
        val usageScore: Double,
        val recencyScore: Double
    )

    private fun List<SearchResult>.withClearHistoryAction(): List<SearchResult> {
        if (isEmpty()) return this
        return listOf(SearchResult.ClearHistoryAction) + this
    }

    private fun refreshHistoryResults() {
        updateSearchResults(binding.searchInput.text?.toString().orEmpty())
    }
}
