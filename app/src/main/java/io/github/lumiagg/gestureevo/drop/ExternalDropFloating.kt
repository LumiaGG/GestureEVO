package io.github.lumiagg.gestureevo.drop

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Color
import android.graphics.Rect
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
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import io.github.lumiagg.gestureevo.search.QuickSearchEnginePager
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.search.setEngineIcon
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_CONVERSION
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_SHOW_ENGINES
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_BOTTOM
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_LEFT
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_RIGHT
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_SIDEBAR_MAX_COLUMNS
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_SIDEBAR_MAX_HEIGHT_PERCENT
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_SIDEBAR_FILL_PRIORITY
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_FILL_HEIGHT
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_FILL_COLUMNS
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
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

/** Listens for global drags without occupying a touchable area while idle. */
internal class ExternalDropFloating(
    private val context: Context,
    private val scope: CoroutineScope,
    private val position: String = EXTERNAL_DROP_POSITION_BOTTOM,
    private val showTools: Boolean,
    private val dispatcher: DropContentDispatcher,
    converter: DragContentConverter,
) : BaseFloatingWindow(context, scope, "ExternalDrop"), ContentDragTarget {
    private enum class Action { POOL, PIN, TOKENIZE, CONVERT, SEARCH }

    private val toolActions = listOf(R.id.externalDropPool to Action.POOL, R.id.externalDropPin to Action.PIN,
        R.id.externalDropTokenize to Action.TOKENIZE, R.id.externalDropConvert to Action.CONVERT)
    private val conversion = DragConversionController(
        scope = scope,
        convert = converter::convert,
        onPreview = { content, hint ->
            updateConversionLabel(content.description)
            contentPreview.show(content, contentX, contentY, sidebarBounds(), hint?.let(context::getString))
        },
        prepareContent = { configureEngines(it.description) },
        onConverted = { content ->
            internalContent = content
            if (sidebar) {
                positionSidebar()
                updateViewLayoutSafely(targets, targetParams)
            }
            io.github.lumiagg.gestureevo.utils.VibratorUtils.vibrator(context, false)
        },
        onError = ::toast,
        onFinished = { updateContentDrag(contentX, contentY) },
    )

    private val sidebar = position == EXTERNAL_DROP_POSITION_LEFT || position == EXTERNAL_DROP_POSITION_RIGHT
    private val themeContext = DynamicColorUtils.dynamicThemedContext(context)
    private val observer = View(context)
    private val appearance = DropOverlayAppearance(context, scope)
    private val targets = LayoutInflater.from(themeContext)
        .inflate(if (sidebar) R.layout.external_drop_sidebar else R.layout.external_drop_floating, null)
    private val enginePager = targets.findViewById<QuickSearchEnginePager>(R.id.externalDropEngines)
    private val actionPreview = DragActionPreview(
        context, scope, themeContext, position, observer, "GestureEVO:ExternalDrop:Preview",
    )
    private var previewAction: Action? = null
    private var previewEngine: SearchEngine? = null
    private var previewPageDirection = 0
    private var showImageBadge = true
    private var dragGeneration = 0
    private var prepareJob: Job? = null
    private var destroyed = false
    private var dragActive = false
    private var internalContent: ClipData? = null
    private val contentPreview = DragContentPreview(context, scope)
    private val contentSession = ContentDragSession()
    private var contentX = 0f
    private var contentY = 0f
    var onSystemDragStarted: (() -> Unit)? = null
    var onSystemDragEnded: (() -> Unit)? = null
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
            if (destroyed) return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    DropDiagnostics.event("drop_system_started", "system", dragGeneration,
                        reason = if (accepts(event)) "accepted" else "rejected", flags = targetParams.flags)
                    onSystemDragStarted?.invoke()
                    cancelContentDrag()
                    // Observe the end of EVO-origin drags too, without opening targets for them.
                    if (!accepts(event)) true else {
                        targetParams.flags = targetParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        dragActive = true
                        dropped = false
                        val generation = ++dragGeneration
                        val description = ClipDescription(event.clipDescription)
                        prepareJob?.cancel()
                        // Attach after the current drag dispatch completes.
                        observer.post {
                            if (!dragActive || destroyed || generation != dragGeneration) return@post
                            prepareJob = scope.launch(Dispatchers.Main) {
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
                    DropDiagnostics.event("drop_system_ended", "system", dragGeneration,
                        reason = if (dropped) "dropped" else "no_drop", flags = targetParams.flags)
                    onSystemDragEnded?.invoke()
                    dragActive = false
                    dragGeneration++
                    prepareJob?.cancel()
                    observer.post { if (!dragActive) hideTargets() }
                    true
                }
                else -> false
            }
        }
        toolActions.forEach { (id, action) -> bindTarget(id, action) }
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
        DropDiagnostics.event("drop_panel_created", "panel", dragGeneration, reason = position)
        if (!destroyed) attachViewSafely(observer, observerParams)
    }

    fun destroy() {
        if (destroyed) return
        val source = if (internalContent != null) "internal" else "system_or_idle"
        cancelContentDrag()
        DropDiagnostics.event("drop_panel_destroyed", source, dragGeneration)
        destroyed = true
        dragActive = false
        dragGeneration++
        prepareJob?.cancel()
        hideTargets()
        detachViewSafely(observer)
        onSystemDragEnded?.invoke()
    }

    override fun beginContentDrag(clip: ClipData, x: Float, y: Float, stillCurrent: () -> Boolean,
        onReady: () -> Unit, onFailed: (String) -> Unit): Boolean {
        if (destroyed || dragActive || clip.itemCount == 0 || !stillCurrent()) {
            DropDiagnostics.event("drop_internal_rejected", "internal", dragGeneration,
                reason = if (destroyed) "destroyed" else if (dragActive) "already_active" else "source_or_content_unavailable")
            Log.i("EVO-Press", "panel_rejected destroyed=$destroyed active=$dragActive items=${clip.itemCount}")
            return false
        }
        contentX = x
        contentY = y
        internalContent = ClipData(clip)
        conversion.retain(checkNotNull(internalContent))
        targetParams.flags = targetParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        dragActive = true
        dropped = false
        val generation = ++dragGeneration
        val session = contentSession.begin()
        fun current() = generation == dragGeneration && contentSession.isCurrent(session) &&
            internalContent != null && !destroyed && stillCurrent()
        fun fail(reason: String) {
            if (!contentSession.isCurrent(session)) return
            DropDiagnostics.event("drop_internal_failed", "internal", generation, reason)
            Log.i("EVO-Press", "panel_failed reason=$reason generation=$generation")
            cancelContentDrag()
            onFailed(reason)
        }
        DropDiagnostics.event("drop_internal_preparing", "internal", generation)
        Log.i("EVO-Press", "panel_preparing generation=$generation")
        prepareJob = scope.launch(Dispatchers.Main) {
            try {
                configureEngines(clip.description)
                if (!current()) { fail("source_expired"); return@launch }
                // Cold overlays have no dimensions until their first traversal. Keep both windows hidden until ready.
                targets.alpha = 0f
                if (!showTargets()) { fail("panel_attach"); return@launch }
                val laidOut = kotlinx.coroutines.withTimeoutOrNull(1500) { awaitContentLayout() } == true
                if (!current()) { fail("source_expired"); return@launch }
                if (!contentSession.confirm(session, stillCurrent(), laidOut)) { fail("panel_layout"); return@launch }
                if (!contentPreview.show(clip, contentX, contentY, sidebarBounds())) { fail("preview_attach"); return@launch }
                targets.alpha = 1f
                DropDiagnostics.event("drop_internal_ready", "internal", generation, width = targets.width,
                    height = targets.height, flags = targetParams.flags)
                Log.i("EVO-Press", "panel_ready generation=$generation width=${targets.width} height=${targets.height}")
                onReady()
                updateContentDrag(contentX, contentY)
            } catch (cancelled: CancellationException) {
                if (contentSession.isCurrent(session)) { cancelContentDrag(); onFailed("prepare_cancelled") }
                throw cancelled
            } catch (error: Exception) {
                Log.w("EVO-Press", "panel_prepare_error generation=$generation type=${error.javaClass.simpleName}")
                fail("prepare_error")
            }
        }
        return true
    }

    private suspend fun awaitContentLayout(): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val observer = targets.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!targets.isAttachedToWindow || targets.width <= 0 || targets.height <= 0 || targets.isLayoutRequested) return true
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (continuation.isActive) continuation.resume(true) { _, _, _ -> }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
        continuation.invokeOnCancellation { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
        targets.requestLayout()
    }

    private fun updateContentPreview() = contentPreview.move(contentX, contentY, sidebarBounds())

    override fun cancelContentDrag() {
        if (internalContent == null) return
        contentSession.cancel()
        conversion.clear()
        contentPreview.clear()
        DropDiagnostics.event("drop_internal_ended", "internal", dragGeneration, reason = if (dropped) "dropped" else "cancelled")
        internalContent = null
        dragActive = false
        dragGeneration++
        prepareJob?.cancel()
        enginePager.endContentDrag()
        hideTargets()
    }

    private fun localPoint(view: View, x: Float, y: Float): Pair<Float, Float> {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return (x - location[0]) to (y - location[1])
    }

    override fun updateContentDrag(x: Float, y: Float, release: Boolean) {
        val content = internalContent ?: return
        contentX = x
        contentY = y
        updateContentPreview()
        if (!contentSession.ready || !targets.isAttachedToWindow || destroyed || dropped) {
            if (release) cancelContentDrag()
            return
        }
        var tool: Action? = null
        toolActions.forEach { (id, action) ->
            val card = targets.findViewById<MaterialCardView>(id)
            val (lx, ly) = localPoint(card, x, y)
            val active = card.isShown && lx >= 0 && ly >= 0 && lx < card.width && ly < card.height
            appearance.setHighlighted(card, active)
            if (active) tool = action
        }
        val (px, py) = localPoint(enginePager, x, y)
        val overEngines = enginePager.isShown && px >= 0 && py >= 0 && px < enginePager.width && py < enginePager.height
        enginePager.updateContentDrag(if (overEngines) px else -1000f, if (overEngines) py else -1000f)
        conversion.hover(tool == Action.CONVERT && !release)
        if (tool != null) showPreview(tool) else if (!overEngines) hidePreview()
        if (sidebar) {
            val (tx, ty) = localPoint(targets, x, y)
            pageHoverX = tx
            pageHoverY = ty
            updatePageHover()
        }
        if (release) {
            val engine = if (overEngines) enginePager.contentDragTarget(px, py) else null
            if (!conversion.busy) {
                if (tool != null && tool != Action.CONVERT) receiveContent(content, checkNotNull(tool))
                else if (engine != null) receiveContent(content, Action.SEARCH, engine.first, engine.second)
            }
            cancelContentDrag()
        }
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
        val store = context.settingsStore
        if (sidebar) {
            sidebarMaxColumns = store.readKey(KEY_EXTERNAL_DROP_SIDEBAR_MAX_COLUMNS, 2).coerceIn(1, 3)
            sidebarMaxHeightPercent = store.readKey(KEY_EXTERNAL_DROP_SIDEBAR_MAX_HEIGHT_PERCENT, 50).coerceIn(30, 80)
            sidebarPreferColumns = store.readKey(KEY_EXTERNAL_DROP_SIDEBAR_FILL_PRIORITY, EXTERNAL_DROP_FILL_HEIGHT) ==
                EXTERNAL_DROP_FILL_COLUMNS
        }
        showImageBadge = store.readKey(SearchEngineSettingsActivity.KEY_SHOW_IMAGE_BADGE, true)
        appearance.configure()
        val config = QuickSearchEnginePager.Config(
            rowCount = if (sidebar) 1 else store.readKey(SearchEngineSettingsActivity.KEY_ROW_COUNT, 1)
                .coerceIn(SearchEngineSettingsActivity.MIN_ROW_COUNT, SearchEngineSettingsActivity.MAX_ROW_COUNT),
            columnCount = if (sidebar) 1 else store.readKey(SearchEngineSettingsActivity.KEY_COLUMN_COUNT, 5)
                .coerceIn(SearchEngineSettingsActivity.MIN_COLUMN_COUNT, SearchEngineSettingsActivity.MAX_COLUMN_COUNT),
            showLabel = store.readKey(SearchEngineSettingsActivity.KEY_ENGINE_SHOW_LABEL, true),
            showImageBadge = showImageBadge,
            previewSortMode = false,
        )
        enginePager.configureExternalDrop(config)
        if (sidebar) sidebarConfig = config
        val content = ExternalDropPolicy.content(
            (0 until description.mimeTypeCount).map(description::getMimeType)
        )
        val convert = targets.findViewById<View>(R.id.externalDropConvert)
        convert.visibility = if (showTools && internalContent != null &&
            content != ExternalDropPolicy.Content.URI &&
            store.readKey(KEY_EXTERNAL_DROP_CONVERSION, false))
            View.VISIBLE else View.GONE
        updateConversionLabel(description)
        val engines = if (store.readKey(KEY_EXTERNAL_DROP_SHOW_ENGINES, true)) {
            SearchEngineDataManager.readSearchEngineList(context).filter {
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
        actionPreview.updatePosition()
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
            enginePager.configureExternalDrop(config.copy(rowCount = 1, columnCount = columns))
            setPagingVisible(false)
            var panelHeight = measureSidebar()
            val rowHeight = (enginePager.measuredHeight - enginePager.paddingTop - enginePager.paddingBottom).coerceAtLeast(1)
            var rows = ExternalDropSidebarPolicy.rowCount(
                maxHeight, panelHeight - rowHeight, rowHeight, sidebarEngines.size, columns,
            )
            if (enginesVisible && sidebarEngines.size > rows * columns) {
                setPagingVisible(true)
                panelHeight = measureSidebar()
                rows = ExternalDropSidebarPolicy.rowCount(
                    maxHeight, panelHeight - rowHeight, rowHeight, sidebarEngines.size, columns,
                )
            }
            enginePager.configureExternalDrop(config.copy(rowCount = rows, columnCount = columns))
            ExternalDropSidebarPolicy.Layout(columns, rows, measureSidebar())
        }
        val layout = ExternalDropSidebarPolicy.choose(layouts, sidebarEngines.size, maxHeight, sidebarPreferColumns)
        sidebarColumns = layout.columns
        sidebarRows = layout.rows
        applySidebarColumns(sidebarColumns)
        enginePager.configureExternalDrop(config.copy(rowCount = sidebarRows, columnCount = sidebarColumns))
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
        val cards = toolActions.filter { it.second != Action.CONVERT }
            .map { targets.findViewById<MaterialCardView>(it.first) }
        cards.forEach { it.layoutParams = GridLayout.LayoutParams() }
        tools.columnCount = columns
        val gap = 4.dp2px.toInt()
        val contentWidth = (columns * SIDEBAR_COLUMN_WIDTH_DP + 4).dp2px.toInt()
        cards.forEachIndexed { index, card ->
            val row = index / columns
            val column = index % columns
            val span = if (index == cards.lastIndex) columns - column else 1
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

    private fun measureSidebar(): Int {
        targets.measure(
            View.MeasureSpec.makeMeasureSpec(targetParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // The detached conversion card must not consume the main panel's row/column budget.
        return targets.findViewById<View>(R.id.externalDropPanel).measuredHeight +
            targets.paddingTop + targets.paddingBottom
    }

    private fun sidebarBounds(): Rect = windowManager.dropSafeBounds(context, observer)

    @SuppressLint("RtlHardcoded")
    private fun positionSidebar() {
        val bounds = sidebarBounds()
        val panelHeight = measureSidebar()
        targetParams.x = if (position == EXTERNAL_DROP_POSITION_LEFT) bounds.left
            else (bounds.right - targetParams.width).coerceAtLeast(bounds.left)
        targetParams.height = targets.measuredHeight.coerceAtMost(bounds.height())
        targetParams.y = bounds.top + ExternalDropSidebarPolicy.topOffset(
            bounds.height(), panelHeight, targetParams.height,
        )
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
        pageHoverJob = scope.launch(Dispatchers.Main) {
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

    private fun showTargets(): Boolean {
        if (!showTools && !enginesVisible) return false
        appearance.applyGlassStyle(targets, internalContent != null)
        toolActions.forEach { (id, _) -> appearance.setHighlighted(targets.findViewById(id), false) }
        if (sidebar) {
            targets.scrollTo(0, 0)
            positionSidebar()
        }
        appearance.show { dragActive && !destroyed }
        val accepted = attachViewSafely(targets, targetParams, shouldRetry = { dragActive && !destroyed })
        actionPreview.attach { dragActive && !destroyed }
        DropDiagnostics.event("drop_targets_shown", if (internalContent != null) "internal" else "system", dragGeneration,
            reason = if (appearance.backgroundBlurActive) "blur_enabled" else "blur_disabled", width = targets.width,
            height = targets.height, flags = targetParams.flags)
        return accepted
    }

    private fun hideTargets() {
        DropDiagnostics.event("drop_targets_hidden", if (internalContent != null) "internal" else "system_or_idle", dragGeneration)
        targets.alpha = 1f
        contentPreview.clear()
        stopPageHover()
        enginePager.animate().cancel()
        enginePager.translationY = 0f
        enginePager.alpha = 1f
        previewAction = null
        previewEngine = null
        previewPageDirection = 0
        actionPreview.detach()
        detachViewSafely(targets)
        appearance.hide()
    }

    private fun showPreview(action: Action? = null, engine: SearchEngine? = null, pageDirection: Int = 0) {
        if (!dragActive || destroyed || dropped) return
        if (previewAction == action && previewEngine == engine && previewPageDirection == pageDirection) return
        previewAction = action
        previewEngine = engine
        previewPageDirection = pageDirection
        actionPreview.show { preview ->
            if (action == Action.SEARCH) {
                setEngineIcon(themeContext, preview, checkNotNull(engine), showImageBadge)
            } else {
                val icon = when (action) {
                    Action.POOL -> R.drawable.pinboard_24px
                    Action.PIN -> R.drawable.baseline_push_pin_24
                    Action.TOKENIZE -> R.drawable.outline_center_focus_weak_24
                    Action.CONVERT -> R.drawable.ic_text_image_convert_24
                    else -> if (pageDirection < 0) R.drawable.ic_chevron_up_24 else R.drawable.ic_chevron_down_24
                }
                preview.setImageWithBackplate(icon)
            }
        }
    }

    private fun hidePreview(action: Action? = null) {
        // An exit from the previous target must not clear a newer target's preview.
        if (action != null && previewAction != action) return
        // Pointer moves outside targets must not keep restarting the fade-out.
        if (previewAction == null && previewEngine == null && previewPageDirection == 0) return
        previewAction = null
        previewEngine = null
        previewPageDirection = 0
        actionPreview.hide()
    }

    private fun bindTarget(id: Int, action: Action) {
        val card = targets.findViewById<MaterialCardView>(id)
        card.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> !destroyed && accepts(event)
                DragEvent.ACTION_DRAG_ENTERED -> {
                    appearance.setHighlighted(card, true)
                    showPreview(action)
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> true
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    appearance.setHighlighted(card, false)
                    hidePreview(action)
                    true
                }
                DragEvent.ACTION_DROP -> receive(event, action)
                else -> false
            }
        }
    }

    private fun receive(
        event: DragEvent,
        action: Action,
        engine: SearchEngine? = null,
        freeform: Boolean = false,
    ): Boolean {
        hidePreview()
        if (destroyed || dropped || action == Action.CONVERT) return false
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val permissions = DropUriPermissions.take(event)
        return receiveContent(ClipData(clip), action, engine, freeform) { runCatching { permissions?.release() } }
    }

    private fun receiveContent(
        content: ClipData,
        action: Action,
        engine: SearchEngine? = null,
        freeform: Boolean = false,
        release: () -> Unit = {},
    ): Boolean {
        if (destroyed || dropped || action == Action.CONVERT) { release(); return false }
        dropped = true
        scope.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            try {
                when (action) {
                    Action.SEARCH -> dispatcher.search(content, checkNotNull(engine), freeform)
                    Action.POOL -> dispatcher.process(content, DropContentDispatcher.Tool.POOL)
                    Action.PIN -> dispatcher.process(content, DropContentDispatcher.Tool.PIN)
                    Action.TOKENIZE -> dispatcher.process(content, DropContentDispatcher.Tool.TOKENIZE)
                    Action.CONVERT -> error("Conversion is handled during hover")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ExternalDrop", "Cannot process dropped content", error)
                toast(R.string.external_drop_failed)
            } finally {
                release()
            }
        }
        return true
    }

    private fun updateConversionLabel(description: ClipDescription) {
        val image = description.hasMimeType("image/*")
        val label = if (conversion.busy && image) R.string.external_drop_recognizing
            else if (conversion.busy) R.string.external_drop_converting
            else if (image) R.string.external_drop_to_text else R.string.external_drop_to_image
        val convert = targets.findViewById<MaterialCardView>(R.id.externalDropConvert)
        convert.contentDescription = context.getString(label)
        convert.alpha = if (conversion.busy) 0.5f else 1f
    }

    private fun toast(message: Int) = ToastHelper.showInUIThread(context, context.getString(message))

    private companion object {
        const val SIDEBAR_COLUMN_WIDTH_DP = 52
    }
}
