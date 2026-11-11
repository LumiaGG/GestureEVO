package io.github.lumiagg.gestureevo.drop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.inspire.InspireContentActivity
import io.github.lumiagg.gestureevo.inspire.InspireDataHolder
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.OcrImageTextProcessor
import io.github.lumiagg.gestureevo.inspire.PictureFloating
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.search.QuickSearchEnginePager
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.search.setEngineIcon
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_EXTERNAL_DROP_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_EXTERNAL_DROP_CARD_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_EXTERNAL_DROP_SHOW_ENGINES
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.EXTERNAL_DROP_POSITION_BOTTOM
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.EXTERNAL_DROP_POSITION_LEFT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.EXTERNAL_DROP_POSITION_RIGHT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_EXTERNAL_DROP_SIDEBAR_MAX_COLUMNS
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_EXTERNAL_DROP_SIDEBAR_MAX_HEIGHT_PERCENT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_EXTERNAL_DROP_SIDEBAR_FILL_PRIORITY
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.EXTERNAL_DROP_FILL_HEIGHT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.EXTERNAL_DROP_FILL_COLUMNS
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.OverlaySurfaceBlur
import io.github.lumiagg.gestureevo.utils.Rom
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Listens for global drags without occupying a touchable area while idle. */
class ExternalDropFloating(
    private val service: GestureService,
    private val position: String = EXTERNAL_DROP_POSITION_BOTTOM,
    private val showTools: Boolean = true,
) : BaseFloatingWindow(service, service, "ExternalDrop") {
    private enum class Action { POOL, PIN, TOKENIZE, SEARCH }

    private val sidebar = position == EXTERNAL_DROP_POSITION_LEFT || position == EXTERNAL_DROP_POSITION_RIGHT
    private val themeContext = DynamicColorUtils.dynamicThemedContext(service)
    private val observer = View(service)
    private val blurBackground = View(service).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val blurParams = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:ExternalDrop:Background",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }
    private val targets = LayoutInflater.from(themeContext)
        .inflate(if (sidebar) R.layout.external_drop_sidebar else R.layout.external_drop_floating, null)
    private val enginePager = targets.findViewById<QuickSearchEnginePager>(R.id.externalDropEngines)
    private val previewIcon = RoundImageTextView(themeContext).apply {
        alpha = 0f
        setTransparentBackground(true)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var previewAction: Action? = null
    private var previewEngine: SearchEngine? = null
    private var previewPageDirection = 0
    private var showImageBadge = true
    private var backgroundBlurEnabled = false
    private var cardBlurEnabled = false
    private var backgroundBlurRadius = 0
    private var backgroundBlurAnimator: ValueAnimator? = null
    private val originOsSurfaceBlur = Rom.isOriginOS()
    private val imageShareLauncher = ImageShareLauncher(service.applicationContext)
    private var dragGeneration = 0
    private var prepareJob: Job? = null
    private var destroyed = false
    private var dragActive = false
    private var dropped = false
    private var sidebarEngines: List<SearchEngine> = emptyList()
    private var sidebarRows = 1
    private var sidebarColumns = 2
    private var sidebarMaxColumns = 2
    private var sidebarMaxHeightPercent = 50
    private var sidebarPreferColumns = false
    private var enginesVisible = false
    private var sidebarConfig: QuickSearchEnginePager.Config? = null
    private var sidebarPage = 0
    private var pageHoverDirection = 0
    private var pageHoverX = 0f
    private var pageHoverY = 0f
    private var pageHoverJob: Job? = null
    private val sidebarPageCount: Int
        get() = ExternalDropSidebarPolicy.pageCount(sidebarEngines.size, sidebarRows, sidebarColumns)
    private val observerParams = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            width = 1,
            height = 1,
            title = "GestureEVO:ExternalDrop:Observer",
        )
    }
    // The position preference names physical screen edges, including in RTL locales.
    @SuppressLint("RtlHardcoded")
    private val targetParams = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            width = if (sidebar) 176.dp2px.toInt() else WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            gravity = if (sidebar) Gravity.TOP or Gravity.LEFT else Gravity.BOTTOM,
            allowDisplayCutout = sidebar,
            title = "GestureEVO:ExternalDrop:Targets",
        )
        if (sidebar) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
    }
    private val previewParams = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            width = 100.dp2px.toInt(),
            height = 100.dp2px.toInt(),
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            allowDisplayCutout = false,
            title = "GestureEVO:ExternalDrop:Preview",
        )
        if (sidebar) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
    }

    init {
        targets.findViewById<View>(R.id.externalDropTools).visibility = if (showTools) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            targets.findViewById<MaterialCardView>(R.id.externalDropPanel).apply {
                outlineAmbientShadowColor = Color.argb(48, 0, 0, 0)
                outlineSpotShadowColor = Color.argb(64, 0, 0, 0)
            }
        }
        observer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        observer.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    if (!accepts(event)) false else {
                        dragActive = true
                        dropped = false
                        val generation = ++dragGeneration
                        val description = ClipDescription(event.clipDescription)
                        prepareJob?.cancel()
                        // Attach after the current drag dispatch completes.
                        observer.post {
                            if (!dragActive || destroyed || generation != dragGeneration) return@post
                            prepareJob = service.launch(Dispatchers.Main) {
                                try {
                                    configureEngines(description)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Log.w("ExternalDrop", "Cannot load search engines", error)
                                    setEnginesVisible(false)
                                    sidebarEngines = emptyList()
                                    enginePager.submitList(emptyList())
                                    if (sidebar) sidebarConfig?.let(::configureSidebar)
                                }
                                if (dragActive && !destroyed && generation == dragGeneration) showTargets()
                            }
                        }
                        true
                    }
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragActive = false
                    dragGeneration++
                    prepareJob?.cancel()
                    observer.post { if (!dragActive) hideTargets() }
                    true
                }
                else -> false
            }
        }
        bindTarget(R.id.externalDropPool, Action.POOL)
        bindTarget(R.id.externalDropPin, Action.PIN)
        bindTarget(R.id.externalDropTokenize, Action.TOKENIZE)
        enginePager.setExternalDropCallbacks(
            accepts = { !destroyed && accepts(it) },
            onDrop = { engine, event, freeform -> receive(event, Action.SEARCH, engine, freeform) },
            onHoverChanged = { engine ->
                if (engine == null) hidePreview(Action.SEARCH)
                else showPreview(Action.SEARCH, engine)
            },
            edgePagingEnabled = !sidebar,
        )
        if (sidebar) {
            targets.setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> !destroyed && accepts(event)
                    DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> true
                    else -> false
                }
            }
            (targets as ExternalDropScrollView).onDragPositionChanged = { event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                        pageHoverX = event.x
                        pageHoverY = event.y
                        updatePageHover()
                    }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED, DragEvent.ACTION_DROP -> stopPageHover()
                }
            }
            targets.setOnScrollChangeListener { _, _, _, _, _ ->
                if (dragActive && targets.isAttachedToWindow) updatePageHover()
            }
        }
    }

    fun start() {
        if (!destroyed) attachViewSafely(observer, observerParams)
    }

    fun destroy() {
        destroyed = true
        dragActive = false
        dragGeneration++
        prepareJob?.cancel()
        hideTargets()
        detachViewSafely(observer)
    }

    private fun accepts(event: DragEvent): Boolean {
        val description = event.clipDescription ?: return false
        return ExternalDropPolicy.accepts(
            fromEvo = description.extras?.getBoolean(ExternalDropPolicy.EVO_SOURCE_EXTRA) == true,
            hasLocalState = event.localState != null,
            mimeTypes = (0 until description.mimeTypeCount).map(description::getMimeType),
        )
    }

    private suspend fun configureEngines(description: ClipDescription) {
        val store = service.settingsStore
        if (sidebar) {
            sidebarMaxColumns = store.readKey(KEY_EXTERNAL_DROP_SIDEBAR_MAX_COLUMNS, 2).coerceIn(1, 3)
            sidebarMaxHeightPercent = store.readKey(KEY_EXTERNAL_DROP_SIDEBAR_MAX_HEIGHT_PERCENT, 50).coerceIn(30, 80)
            sidebarPreferColumns = store.readKey(KEY_EXTERNAL_DROP_SIDEBAR_FILL_PRIORITY, EXTERNAL_DROP_FILL_HEIGHT) ==
                EXTERNAL_DROP_FILL_COLUMNS
        }
        showImageBadge = store.readKey(SearchEngineSettingsActivity.KEY_SHOW_IMAGE_BADGE, true)
        backgroundBlurEnabled = store.readKey(KEY_EXTERNAL_DROP_BACKGROUND_BLUR, false) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        cardBlurEnabled = store.readKey(KEY_EXTERNAL_DROP_CARD_BACKGROUND_BLUR, false)
        val config = QuickSearchEnginePager.Config(
            rowCount = if (sidebar) 1 else store.readKey(SearchEngineSettingsActivity.KEY_ROW_COUNT, 1)
                .coerceIn(SearchEngineSettingsActivity.MIN_ROW_COUNT, SearchEngineSettingsActivity.MAX_ROW_COUNT),
            columnCount = if (sidebar) 1 else store.readKey(SearchEngineSettingsActivity.KEY_COLUMN_COUNT, 5)
                .coerceIn(SearchEngineSettingsActivity.MIN_COLUMN_COUNT, SearchEngineSettingsActivity.MAX_COLUMN_COUNT),
            showLabel = store.readKey(SearchEngineSettingsActivity.KEY_ENGINE_SHOW_LABEL, true),
            showImageBadge = showImageBadge,
            previewSortMode = false,
        )
        enginePager.configure(config)
        if (sidebar) sidebarConfig = config
        val content = ExternalDropPolicy.content(
            (0 until description.mimeTypeCount).map(description::getMimeType)
        )
        val engines = if (store.readKey(KEY_EXTERNAL_DROP_SHOW_ENGINES, true)) {
            SearchEngineDataManager.readSearchEngineList(service).filter {
                it.showInSearchEngineList && ExternalDropPolicy.showsEngine(content, it)
            }
        } else emptyList()
        setEnginesVisible(engines.isNotEmpty())
        if (sidebar) {
            sidebarEngines = engines
            sidebarPage = 0
            sidebarConfig = config
            configureSidebar(config)
        } else {
            enginePager.submitList(engines)
        }
        enginePager.scrollTo(0, 0)
    }

    private fun setEnginesVisible(visible: Boolean) {
        enginesVisible = visible
        targets.findViewById<View>(R.id.externalDropEnginesCard).apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            (layoutParams as LinearLayout.LayoutParams).bottomMargin =
                if (showTools) (if (sidebar) 8 else 12).dp2px.toInt() else 0
        }
    }

    fun onConfigurationChanged() {
        if (!sidebar || !dragActive || !targets.isAttachedToWindow) return
        stopPageHover()
        sidebarConfig?.let(::configureSidebar)
        positionSidebar()
        updateViewLayoutSafely(targets, targetParams)
        positionPreview()
        updateViewLayoutSafely(previewIcon, previewParams)
    }

    private fun configureSidebar(config: QuickSearchEnginePager.Config) {
        val bounds = sidebarBounds()
        val maxHeight = bounds.height() * sidebarMaxHeightPercent / 100
        val widthLimit = ((bounds.width() - 32.dp2px) / SIDEBAR_COLUMN_WIDTH_DP.dp2px).toInt().coerceAtLeast(1)
        val maxColumns = sidebarMaxColumns.coerceAtMost(widthLimit)
        val columnOptions = if (enginesVisible) 1..minOf(maxColumns, sidebarEngines.size)
            else maxColumns..maxColumns
        val layouts = columnOptions.map { columns ->
            applySidebarColumns(columns)
            enginePager.configure(config.copy(rowCount = 1, columnCount = columns))
            setPagingVisible(false)
            measureSidebar()
            val rowHeight = (enginePager.measuredHeight - enginePager.paddingTop - enginePager.paddingBottom).coerceAtLeast(1)
            var rows = ExternalDropSidebarPolicy.rowCount(
                maxHeight, targets.measuredHeight - rowHeight, rowHeight, sidebarEngines.size, columns,
            )
            if (enginesVisible && sidebarEngines.size > rows * columns) {
                setPagingVisible(true)
                measureSidebar()
                rows = ExternalDropSidebarPolicy.rowCount(
                    maxHeight, targets.measuredHeight - rowHeight, rowHeight, sidebarEngines.size, columns,
                )
            }
            enginePager.configure(config.copy(rowCount = rows, columnCount = columns))
            measureSidebar()
            ExternalDropSidebarPolicy.Layout(columns, rows, targets.measuredHeight)
        }
        val layout = ExternalDropSidebarPolicy.choose(layouts, sidebarEngines.size, maxHeight, sidebarPreferColumns)
        sidebarColumns = layout.columns
        sidebarRows = layout.rows
        applySidebarColumns(sidebarColumns)
        enginePager.configure(config.copy(rowCount = sidebarRows, columnCount = sidebarColumns))
        sidebarPage = sidebarPage.coerceIn(0, sidebarPageCount - 1)
        showSidebarPage()
    }

    private fun applySidebarColumns(columns: Int) {
        targetParams.width = (columns * SIDEBAR_COLUMN_WIDTH_DP + 32).dp2px.toInt()
        targets.findViewById<MaterialCardView>(R.id.externalDropPanel).apply {
            layoutParams = layoutParams.apply { width = (columns * SIDEBAR_COLUMN_WIDTH_DP + 16).dp2px.toInt() }
        }
        val tools = targets.findViewById<GridLayout>(R.id.externalDropTools)
        // Clear specs before reducing columnCount; GridLayout validates old specs immediately.
        val cards = listOf(R.id.externalDropPool, R.id.externalDropPin, R.id.externalDropTokenize)
            .map { targets.findViewById<MaterialCardView>(it) }
        cards.forEach { it.layoutParams = GridLayout.LayoutParams() }
        tools.columnCount = columns
        val gap = 4.dp2px.toInt()
        val contentWidth = (columns * SIDEBAR_COLUMN_WIDTH_DP + 4).dp2px.toInt()
        cards.forEachIndexed { index, card ->
            val row = if (columns == 1) index else if (columns == 2 && index == 2) 1 else 0
            val column = if (columns == 1 || (columns == 2 && index == 2)) 0 else index
            val span = if (columns == 2 && index == 2) 2 else 1
            card.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(row), GridLayout.spec(column, span),
            ).apply {
                width = if (span == columns) contentWidth else (contentWidth - gap * (columns - 1)) / columns
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(if (column > 0) gap else 0, if (row > 0) gap else 0, 0, 0)
            }
        }
    }

    private fun setPagingVisible(visible: Boolean) {
        listOf(R.id.externalDropPrevious, R.id.externalDropNext).forEach {
            targets.findViewById<View>(it).visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun measureSidebar() {
        targets.measure(
            View.MeasureSpec.makeMeasureSpec(targetParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }

    private fun sidebarBounds(): Rect {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(windowManager.currentWindowMetrics.bounds)
        } else {
            Rect(0, 0, ScreenUtil.getScreenWidth(service), ScreenUtil.getScreenHeight(service))
        }
        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsCompat.toWindowInsetsCompat(windowManager.currentWindowMetrics.windowInsets)
        } else {
            observer.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it, observer) }
        }?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        if (insets != null) {
            bounds.left += insets.left
            bounds.top += insets.top
            bounds.right -= insets.right
            bounds.bottom -= insets.bottom
        }
        return bounds
    }

    @SuppressLint("RtlHardcoded")
    private fun positionSidebar() {
        val bounds = sidebarBounds()
        measureSidebar()
        targetParams.x = if (position == EXTERNAL_DROP_POSITION_LEFT) bounds.left
            else (bounds.right - targetParams.width).coerceAtLeast(bounds.left)
        targetParams.height = targets.measuredHeight.coerceAtMost(bounds.height())
        targetParams.y = bounds.top + ((bounds.height() - targetParams.height) / 2).coerceAtLeast(0)
    }

    @SuppressLint("RtlHardcoded")
    private fun positionPreview() {
        if (!sidebar) {
            previewParams.y = ScreenUtil.getScreenHeight(service) / 3
            return
        }
        val bounds = sidebarBounds()
        val horizontalFraction = if (position == EXTERNAL_DROP_POSITION_LEFT) 0.7f else 0.3f
        previewParams.gravity = Gravity.TOP or Gravity.LEFT
        previewParams.x = (bounds.left + bounds.width() * horizontalFraction - previewParams.width / 2f)
            .toInt().coerceIn(bounds.left, (bounds.right - previewParams.width).coerceAtLeast(bounds.left))
        previewParams.y = (bounds.top + bounds.height() * 0.25f - previewParams.height / 2f)
            .toInt().coerceIn(bounds.top, (bounds.bottom - previewParams.height).coerceAtLeast(bounds.top))
    }

    private fun showSidebarPage() {
        val pageSize = sidebarRows * sidebarColumns
        enginePager.submitList(sidebarEngines.drop(sidebarPage * pageSize).take(pageSize))
        enginePager.scrollTo(0, 0)
        listOf(R.id.externalDropPrevious to -1, R.id.externalDropNext to 1).forEach { (id, direction) ->
            targets.findViewById<View>(id).apply {
                visibility = if (enginesVisible && sidebarPageCount > 1) View.VISIBLE else View.GONE
                alpha = if (ExternalDropSidebarPolicy.canFlip(sidebarPage, direction, sidebarPageCount)) 1f else 0.3f
            }
        }
    }

    private fun pageHoverBounds(id: Int): ExternalDropSidebarPolicy.HoverBounds? {
        val view = targets.findViewById<View>(id)
        if (!view.isShown) return null
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        targets.getLocationInWindow(rootLocation)
        view.getLocationInWindow(viewLocation)
        val left = viewLocation[0] - rootLocation[0]
        val top = viewLocation[1] - rootLocation[1]
        return ExternalDropSidebarPolicy.HoverBounds(
            left.coerceAtLeast(0), top.coerceAtLeast(0),
            (left + view.width).coerceAtMost(targets.width),
            (top + view.height).coerceAtMost(targets.height),
        )
    }

    private fun pageHoverAtPointer(): Int {
        if (listOf(R.id.externalDropEngines, R.id.externalDropTools).any {
                pageHoverBounds(it)?.contains(pageHoverX, pageHoverY) == true
            }) return 0
        return ExternalDropSidebarPolicy.hoverDirection(
            pageHoverX, pageHoverY,
            pageHoverBounds(R.id.externalDropPrevious), pageHoverBounds(R.id.externalDropNext),
            pageHoverDirection, 6.dp2px.toInt(),
        )
    }

    private fun updatePageHover() {
        val direction = pageHoverAtPointer()
        if (direction == 0) stopPageHover() else startPageHover(direction)
    }

    private fun startPageHover(direction: Int) {
        if (!dragActive || destroyed || dropped || pageHoverDirection == direction) return
        stopPageHover(clearPreview = false)
        showPreview(pageDirection = direction)
        if (!ExternalDropSidebarPolicy.canFlip(sidebarPage, direction, sidebarPageCount)) return
        pageHoverDirection = direction
        val progress = targets.findViewById<View>(if (direction < 0)
            R.id.externalDropPreviousProgress else R.id.externalDropNextProgress)
        val band = progress.parent as View
        band.setBackgroundColor(DynamicColorUtils.secondaryContainerColor)
        pageHoverJob = service.launch(Dispatchers.Main) {
            var waitMillis = 300L
            while (dragActive && !destroyed && !dropped &&
                ExternalDropSidebarPolicy.canFlip(sidebarPage, direction, sidebarPageCount)) {
                progress.scaleX = 0f
                progress.animate().scaleX(1f).setDuration(waitMillis).setInterpolator(LinearInterpolator()).start()
                delay(waitMillis)
                if (!dragActive || destroyed || dropped || pageHoverAtPointer() != direction) break
                progress.animate().cancel()
                progress.scaleX = 0f
                sidebarPage += direction
                showSidebarPage()
                enginePager.animate().cancel()
                enginePager.translationY = direction * 6.dp2px
                enginePager.alpha = 0.7f
                enginePager.animate().translationY(0f).alpha(1f).setDuration(120).start()
                waitMillis = 450L
            }
            progress.animate().cancel()
            progress.scaleX = 0f
            band.setBackgroundColor(Color.TRANSPARENT)
            pageHoverDirection = 0
        }
    }

    private fun stopPageHover(clearPreview: Boolean = true) {
        if (clearPreview && previewPageDirection != 0) hidePreview()
        pageHoverJob?.cancel()
        pageHoverJob = null
        pageHoverDirection = 0
        if (!sidebar) return
        listOf(R.id.externalDropPreviousProgress, R.id.externalDropNextProgress).forEach { id ->
            targets.findViewById<View>(id).apply {
                animate().cancel()
                scaleX = 0f
                (parent as View).setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun showTargets() {
        if (!showTools && !enginesVisible) return
        applyGlassStyle()
        listOf(R.id.externalDropPool, R.id.externalDropPin, R.id.externalDropTokenize).forEach {
            setHighlighted(targets.findViewById(it), false)
        }
        if (sidebar) {
            targets.scrollTo(0, 0)
            positionSidebar()
        }
        positionPreview()
        if (backgroundBlurEnabled) {
            attachViewSafely(
                blurBackground,
                blurParams,
                // Retrying after the targets attach would put blur above the targets.
                shouldRetry = { false },
                onAttached = {
                    blurBackground.post {
                        if (dragActive && !destroyed && blurBackground.isAttachedToWindow) startBackgroundBlur()
                    }
                },
            )
        }
        attachViewSafely(targets, targetParams, shouldRetry = { dragActive && !destroyed })
        attachViewSafely(previewIcon, previewParams, shouldRetry = { dragActive && !destroyed })
    }

    private fun hideTargets() {
        stopPageHover()
        clearBackgroundBlur()
        enginePager.animate().cancel()
        enginePager.translationY = 0f
        enginePager.alpha = 1f
        previewAction = null
        previewEngine = null
        previewIcon.animate().cancel()
        previewIcon.alpha = 0f
        detachViewSafely(previewIcon)
        detachViewSafely(targets)
        detachViewSafely(blurBackground)
    }

    private fun showPreview(action: Action? = null, engine: SearchEngine? = null, pageDirection: Int = 0) {
        if (!dragActive || destroyed || dropped) return
        if (previewAction == action && previewEngine == engine && previewPageDirection == pageDirection) return
        previewAction = action
        previewEngine = engine
        previewPageDirection = pageDirection
        previewIcon.animate().cancel()
        previewIcon.setBadge(null)
        if (action == Action.SEARCH) {
            setEngineIcon(themeContext, previewIcon, checkNotNull(engine), showImageBadge)
        } else {
            val icon = when (action) {
                Action.POOL -> R.drawable.pinboard_24px
                Action.PIN -> R.drawable.baseline_push_pin_24
                Action.TOKENIZE -> R.drawable.outline_center_focus_weak_24
                else -> if (pageDirection < 0) R.drawable.baseline_arrow_upward_24 else R.drawable.baseline_arrow_downward_24
            }
            previewIcon.setImageWithBackplate(icon)
        }
        previewIcon.animate().alpha(1f).setDuration(150).start()
    }

    private fun hidePreview(action: Action? = null) {
        // An exit from the previous target must not clear a newer target's preview.
        if (action != null && previewAction != action) return
        previewAction = null
        previewEngine = null
        previewPageDirection = 0
        previewIcon.animate().cancel()
        previewIcon.animate().alpha(0f).setDuration(150).start()
    }

    private fun bindTarget(id: Int, action: Action) {
        val card = targets.findViewById<MaterialCardView>(id)
        card.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> !destroyed && accepts(event)
                DragEvent.ACTION_DRAG_ENTERED -> {
                    setHighlighted(card, true)
                    showPreview(action)
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> true
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    setHighlighted(card, false)
                    hidePreview(action)
                    true
                }
                DragEvent.ACTION_DROP -> receive(event, action)
                else -> false
            }
        }
    }

    private fun setHighlighted(card: MaterialCardView, active: Boolean) {
        card.strokeWidth = if (active) 2.dp2px.toInt() else 0
        card.strokeColor = if (active) DynamicColorUtils.primaryColor else DynamicColorUtils.outlineColor
        card.setCardBackgroundColor(
            when {
                active -> DynamicColorUtils.secondaryContainerColor
                cardBlurEnabled -> Color.TRANSPARENT
                else -> DynamicColorUtils.surfaceContainerHighColor
            }
        )
    }

    /** Applies the panel glass style from the current blur settings before each show. */
    private fun applyGlassStyle() {
        val panel = targets.findViewById<MaterialCardView>(R.id.externalDropPanel)
        val enginesCard = targets.findViewById<MaterialCardView>(R.id.externalDropEnginesCard)
        if (cardBlurEnabled) {
            GlassEffectUtils.applyCardStyle(
                card = panel,
                color = DynamicColorUtils.surfaceContainerColor,
                glassEnabled = true,
                blurRegion = GlassEffectUtils.usesCardRegionBlur(backgroundBlurEnabled, cardBlurEnabled),
                backgroundBlurEnabled = backgroundBlurEnabled,
            )
            // Like screen content cards, keep glass within the rounded card bounds.
            panel.strokeWidth = 0
            enginesCard.setCardBackgroundColor(Color.TRANSPARENT)
        } else {
            GlassEffectUtils.applyCardStyle(
                card = panel,
                color = DynamicColorUtils.surfaceContainerColor,
                glassEnabled = false,
                blurRegion = false,
            )
            panel.strokeWidth = 0
            panel.cardElevation = PANEL_ELEVATION_DP.dp2px
            enginesCard.setCardBackgroundColor(DynamicColorUtils.surfaceContainerHighColor)
        }
    }

    private fun startBackgroundBlur() {
        if (!backgroundBlurEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (backgroundBlurRadius == BACKGROUND_BLUR_RADIUS) return
        val animator = ValueAnimator.ofInt(backgroundBlurRadius, BACKGROUND_BLUR_RADIUS).apply {
            duration = BLUR_ANIMATION_MILLIS
            interpolator = LinearInterpolator()
            addUpdateListener { valueAnimator ->
                applyBackgroundBlurRadius(valueAnimator.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (backgroundBlurAnimator !== this@apply) return
                    backgroundBlurAnimator = null
                }
            })
        }
        val previous = backgroundBlurAnimator
        backgroundBlurAnimator = animator
        previous?.cancel()
        animator.start()
    }

    private fun applyBackgroundBlurRadius(radius: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        backgroundBlurRadius = radius
        if (originOsSurfaceBlur) {
            OverlaySurfaceBlur.apply(blurBackground, radius)
            return
        }
        val blurFlag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        blurParams.blurBehindRadius = radius
        blurParams.flags = if (radius > 0) {
            blurParams.flags or blurFlag
        } else {
            blurParams.flags and blurFlag.inv()
        }
        if (!blurBackground.isAttachedToWindow) return
        updateViewLayoutSafely(blurBackground, blurParams)
    }

    private fun clearBackgroundBlur() {
        backgroundBlurAnimator?.cancel()
        backgroundBlurAnimator = null
        applyBackgroundBlurRadius(0)
    }

    private fun receive(
        event: DragEvent,
        action: Action,
        engine: SearchEngine? = null,
        freeform: Boolean = false,
    ): Boolean {
        hidePreview()
        if (destroyed || dropped) return false
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val permissions = DropUriPermissions.take(event)
        val content = ClipData(clip)
        dropped = true
        service.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            try {
                if (action == Action.SEARCH) search(content, checkNotNull(engine), freeform)
                else process(content, action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ExternalDrop", "Cannot process dropped content", error)
                toast(R.string.external_drop_failed)
            } finally {
                runCatching { permissions?.release() }
            }
        }
        return true
    }

    private suspend fun isImage(clip: ClipData, uri: Uri?): Boolean =
        uri != null && withContext(Dispatchers.IO) {
            val mime = service.contentResolver.getType(uri)
            if (mime != null) mime.startsWith("image/") else clip.description.hasMimeType("image/*")
        }

    private suspend fun search(clip: ClipData, engine: SearchEngine, freeform: Boolean) {
        val images = mutableListOf<Uri>()
        val texts = mutableListOf<String>()
        // Dual-mode engines keep both content types for the executor's mode selection.
        val collectImages = engine.supportsImageShare()
        val collectTexts = engine.engineType != EngineType.SHARE_IMAGE_TO_APP
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            if (collectImages && isImage(clip, uri)) {
                decodeImage(checkNotNull(uri))?.use { image ->
                    // Copy into EVO's sharing cache before the source's transient grant ends.
                    val payload = withContext(Dispatchers.IO) {
                        imageShareLauncher.buildPayload(image.requireBitmap(), null)
                    }
                    images += checkNotNull(payload).contentUris
                }
            } else if (collectTexts && !isImage(clip, uri)) {
                val text = withContext(Dispatchers.IO) { item.coerceToText(service)?.toString() }
                if (!text.isNullOrBlank()) texts += text
            }
        }
        if (images.isEmpty() && texts.isEmpty()) {
            toast(R.string.external_drop_empty)
            return
        }
        DropSearchActivity.start(service, engine.tag, texts.joinToString("\n"), images, freeform)
    }

    private suspend fun process(clip: ClipData, action: Action) {
        val tokenText = mutableListOf<String>()
        var tokenImage: ManagedBitmap? = null
        var handled = 0
        try {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                val uri = item.uri
                if (isImage(clip, uri)) {
                    val image = decodeImage(checkNotNull(uri)) ?: error("Cannot decode dropped image")
                    image.use {
                        when (action) {
                            Action.POOL -> checkNotNull(PinPoolStore.addImagePin(service, it.requireBitmap()))
                            Action.PIN -> PictureFloating.build(service, checkNotNull(it.acquire()))
                            Action.TOKENIZE -> {
                                if (clip.itemCount == 1) tokenImage = it.acquire()
                                else tokenText += withContext(Dispatchers.Default) {
                                    OcrImageTextProcessor(service).process(it.requireBitmap()).rawText
                                }
                            }
                            Action.SEARCH -> error("Search is handled separately")
                        }
                    }
                } else {
                    val text = withContext(Dispatchers.IO) { item.coerceToText(service)?.toString() }
                        ?.takeIf { it.isNotBlank() } ?: continue
                    when (action) {
                        Action.POOL -> checkNotNull(PinPoolStore.addTextPin(service, text))
                        Action.PIN -> PictureFloating.build(service, text)
                        Action.TOKENIZE -> tokenText += text
                        Action.SEARCH -> error("Search is handled separately")
                    }
                }
                handled++
            }
            if (handled == 0) {
                toast(R.string.external_drop_empty)
            } else if (action == Action.TOKENIZE) {
                InspireDataHolder.clear()
                InspireDataHolder.replaceScreenshotBitmap(tokenImage)
                InspireDataHolder.accessibilityContent = tokenText
                    .joinToString("\n")
                    .takeIf { it.isNotEmpty() }
                InspireContentActivity.start(service)
            } else if (action == Action.POOL) {
                toast(R.string.external_drop_added)
            }
        } finally {
            tokenImage?.close()
        }
    }

    private suspend fun decodeImage(uri: Uri): ManagedBitmap? {
        var decoded: ManagedBitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                val resolver = service.contentResolver
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > MAX_IMAGE_SIDE) {
                            val scale = MAX_IMAGE_SIDE.toFloat() / longest
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                } else {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        inSampleSize = 1
                    }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    options.inJustDecodeBounds = false
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > MAX_IMAGE_SIDE) {
                        options.inSampleSize *= 2
                    }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                }
                bitmap?.let(ManagedBitmap::from).also { decoded = it }
            }
        } catch (error: Throwable) {
            decoded?.close()
            throw error
        }
    }

    private fun toast(message: Int) = ToastHelper.showInUIThread(service, service.getString(message))

    private companion object {
        const val SIDEBAR_COLUMN_WIDTH_DP = 52
        const val MAX_IMAGE_SIDE = 4096
        const val BACKGROUND_BLUR_RADIUS = 72
        const val BLUR_ANIMATION_MILLIS = 250L
        const val PANEL_ELEVATION_DP = 2
    }
}
