package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.content.ClipDescription
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
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
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_EXTERNAL_DROP_SHOW_ENGINES
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Listens for global drags without occupying a touchable area while idle. */
class ExternalDropFloating(private val service: GestureService) :
    BaseFloatingWindow(service, service, "ExternalDrop") {
    private enum class Action { POOL, PIN, TOKENIZE, SEARCH }

    private val themeContext = DynamicColorUtils.dynamicThemedContext(service)
    private val observer = View(service)
    private val targets = LayoutInflater.from(themeContext)
        .inflate(R.layout.external_drop_floating, null)
    private val enginePager = targets.findViewById<QuickSearchEnginePager>(R.id.externalDropEngines)
    private val imageShareLauncher = ImageShareLauncher(service.applicationContext)
    private var dragGeneration = 0
    private var prepareJob: Job? = null
    private var destroyed = false
    private var dragActive = false
    private var dropped = false
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
    private val targetParams = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            gravity = Gravity.BOTTOM,
            allowDisplayCutout = false,
            title = "GestureEVO:ExternalDrop:Targets",
        )
    }

    init {
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
                                    targets.findViewById<View>(R.id.externalDropEnginesCard).visibility = View.GONE
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
            onDrop = { engine, event -> receive(event, Action.SEARCH, engine) },
        )
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
        if (!store.readKey(KEY_EXTERNAL_DROP_SHOW_ENGINES, true)) {
            targets.findViewById<View>(R.id.externalDropEnginesCard).visibility = View.GONE
            enginePager.submitList(emptyList())
            return
        }
        enginePager.configure(QuickSearchEnginePager.Config(
            rowCount = store.readKey(SearchEngineSettingsActivity.KEY_ROW_COUNT, 1)
                .coerceIn(SearchEngineSettingsActivity.MIN_ROW_COUNT, SearchEngineSettingsActivity.MAX_ROW_COUNT),
            columnCount = store.readKey(SearchEngineSettingsActivity.KEY_COLUMN_COUNT, 5)
                .coerceIn(SearchEngineSettingsActivity.MIN_COLUMN_COUNT, SearchEngineSettingsActivity.MAX_COLUMN_COUNT),
            showLabel = store.readKey(SearchEngineSettingsActivity.KEY_ENGINE_SHOW_LABEL, true),
            showImageBadge = store.readKey(SearchEngineSettingsActivity.KEY_SHOW_IMAGE_BADGE, true),
            previewSortMode = false,
        ))
        val content = ExternalDropPolicy.content(
            (0 until description.mimeTypeCount).map(description::getMimeType)
        )
        val engines = SearchEngineDataManager.readSearchEngineList(service).filter {
            it.showInSearchEngineList && ExternalDropPolicy.showsEngine(
                content, it.engineType == EngineType.SHARE_IMAGE_TO_APP
            )
        }
        enginePager.submitList(engines)
        enginePager.scrollTo(0, 0)
        targets.findViewById<View>(R.id.externalDropEnginesCard).visibility =
            if (engines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showTargets() {
        listOf(R.id.externalDropPool, R.id.externalDropPin, R.id.externalDropTokenize).forEach {
            setHighlighted(targets.findViewById(it), false)
        }
        attachViewSafely(targets, targetParams, shouldRetry = { dragActive && !destroyed })
    }

    private fun hideTargets() = detachViewSafely(targets)

    private fun bindTarget(id: Int, action: Action) {
        val card = targets.findViewById<MaterialCardView>(id)
        card.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> !destroyed && accepts(event)
                DragEvent.ACTION_DRAG_ENTERED -> { setHighlighted(card, true); true }
                DragEvent.ACTION_DRAG_LOCATION -> true
                DragEvent.ACTION_DRAG_EXITED -> { setHighlighted(card, false); true }
                DragEvent.ACTION_DROP -> receive(event, action)
                DragEvent.ACTION_DRAG_ENDED -> { setHighlighted(card, false); true }
                else -> false
            }
        }
    }

    private fun setHighlighted(card: MaterialCardView, active: Boolean) {
        card.strokeWidth = if (active) 2.dp2px.toInt() else 0
        card.strokeColor = if (active) DynamicColorUtils.primaryColor else DynamicColorUtils.outlineColor
        card.setCardBackgroundColor(
            if (active) DynamicColorUtils.secondaryContainerColor else DynamicColorUtils.surfaceContainerHighColor
        )
    }

    private fun receive(event: DragEvent, action: Action, engine: SearchEngine? = null): Boolean {
        if (destroyed || dropped) return false
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val permissions = DropUriPermissions.take(event)
        val content = ClipData(clip)
        dropped = true
        service.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            try {
                if (action == Action.SEARCH) search(content, checkNotNull(engine))
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

    private suspend fun search(clip: ClipData, engine: SearchEngine) {
        val images = mutableListOf<Uri>()
        val texts = mutableListOf<String>()
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            if (engine.engineType == EngineType.SHARE_IMAGE_TO_APP) {
                if (isImage(clip, uri)) {
                    decodeImage(checkNotNull(uri))?.use { image ->
                        // Copy into EVO's sharing cache before the source's transient grant ends.
                        val payload = withContext(Dispatchers.IO) {
                            imageShareLauncher.buildPayload(image.requireBitmap(), null)
                        }
                        images += checkNotNull(payload).contentUris
                    }
                }
            } else if (!isImage(clip, uri)) {
                val text = withContext(Dispatchers.IO) { item.coerceToText(service)?.toString() }
                if (!text.isNullOrBlank()) texts += text
            }
        }
        if (images.isEmpty() && texts.isEmpty()) {
            toast(R.string.external_drop_empty)
            return
        }
        DropSearchActivity.start(service, engine.tag, texts.joinToString("\n"), images)
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
        const val MAX_IMAGE_SIDE = 4096
    }
}
