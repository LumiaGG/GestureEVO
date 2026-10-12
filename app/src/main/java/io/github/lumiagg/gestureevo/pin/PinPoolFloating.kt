package io.github.lumiagg.gestureevo.pin

import androidx.core.graphics.drawable.toDrawable

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.animation.addListener
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.PinPoolFloatingViewBinding
import io.github.lumiagg.gestureevo.databinding.PinPoolItemBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.gesture.EventBusManager
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.gesture.SearchEvent
import io.github.lumiagg.gestureevo.inspire.ClipboardToInspireActivityUtils
import io.github.lumiagg.gestureevo.inspire.InspireHelperUtils
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.search.InjectionTask
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_PIN_POOL_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_PIN_POOL_CARD_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_PIN_POOL_DISPLAY_MODE
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_PIN_POOL_LOCAL_RULE_EXTRACTION
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.PIN_POOL_DISPLAY_MODE_FULLSCREEN
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.DEFAULT_PIN_POOL_LOCAL_RULE_EXTRACTION
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.ui.ActionTextMenuPopup
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider.resolveCurrentPackageName
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.NotificationPermissionUtils
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.TransparentActivityLauncher
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.min

class PinPoolFloating : AppCompatActivity() {
    private lateinit var binding: PinPoolFloatingViewBinding
    private var pendingScrollItemId: String? = null
    private var reminderCandidateDialog: AlertDialog? = null

    private var pinPoolBackgroundBlurEnabled = false
    private var pinPoolCardBackgroundBlurEnabled = false
    private var isClosing = false
    private var isAnimating = false
    private var hasLoadedInitialConfig = false
    private var pendingPinsLoad = false
    private var currentCategory = PinPoolCategory.GENERAL
    private var displayMode = PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR
    private var triggerSide: String? = null
    private var dragSending = false
    private var preparedDragItemId: String? = null
    private var preparedDragUri: Uri? = null
    private val imageBoundsCache = mutableMapOf<String, Pair<Int, Int>>()
    private val previewBitmapCache = object : LruCache<String, Bitmap>(previewCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }
    private val textPinPaddingPx by lazy { 12.dp2px.toInt() }
    private val imagePinPaddingPx by lazy { 4.dp2px.toInt() }
    private val pinnedStrokeWidthPx by lazy { 4.dp2px.toInt() }
    private val previewMaxWidthPx by lazy { 720.dp2px.toInt() }
    private val previewMaxHeightPx by lazy { 420.dp2px.toInt() }
    private val imageShareLauncher by lazy { ImageShareLauncher(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = PinPoolFloatingViewBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        DynamicColorUtils.refreshSurfaceColors(this)
        applyWindowInsets()
        initView()
        pendingScrollItemId = intent.getStringExtra(EXTRA_SCROLL_TO_PIN_ID)
        triggerSide = intent.getStringExtra(EXTRA_SIDE)
        lifecycleScope.launch {
            initAppearanceConfig()
            displayMode = resolveDisplayMode(
                settingsStore.readKey(
                    KEY_PIN_POOL_DISPLAY_MODE,
                    PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR
                ),
                triggerSide
            )
            applyDisplayMode(displayMode)
            hasLoadedInitialConfig = true
            if (pendingPinsLoad) {
                pendingPinsLoad = false
                loadPins()
            }
            binding.root.doOnPreDraw {
                if (!isClosing && !isFinishing && !isDestroyed) showWithAnimation()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        currentActivity = WeakReference(this)
        if (hasLoadedInitialConfig) {
            loadPins()
        } else {
            pendingPinsLoad = true
        }
    }

    override fun onStop() {
        super.onStop()
        currentActivity?.get()?.let { activity ->
            if (activity === this) {
                currentActivity = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingScrollItemId = intent.getStringExtra(EXTRA_SCROLL_TO_PIN_ID)
        triggerSide = intent.getStringExtra(EXTRA_SIDE)
        if (hasLoadedInitialConfig) {
            lifecycleScope.launch {
                displayMode = resolveDisplayMode(
                    settingsStore.readKey(
                        KEY_PIN_POOL_DISPLAY_MODE,
                        PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR
                    ),
                    triggerSide
                )
                applyDisplayMode(displayMode)
                loadPins()
            }
        } else {
            pendingPinsLoad = true
        }
    }

    private fun resolveDisplayMode(mode: String, side: String?): String {
        if (mode != PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER) return mode
        return if (side == TriggerSide.LEFT) {
            PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR
        } else {
            PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR
        }
    }

    private suspend fun initAppearanceConfig() {
        pinPoolBackgroundBlurEnabled = settingsStore.readKey(KEY_PIN_POOL_BACKGROUND_BLUR, false)
        pinPoolCardBackgroundBlurEnabled = settingsStore.readKey(
                KEY_PIN_POOL_CARD_BACKGROUND_BLUR,
                false
            )
        GlassEffectUtils.applyWindowBlurProgress(window, 0f)
    }

    private fun initView() {
        binding.cardContainer.alpha = 0f
        binding.cardContainer.scaleX = 0.96f
        binding.cardContainer.scaleY = 0.96f
        binding.pinRecycler.linear().setup {
            addType<PinPoolItem>(R.layout.pin_pool_item)

            onBind {
                val item = getModel<PinPoolItem>()
                val itemBinding = PinPoolItemBinding.bind(itemView)
                val itemCard = itemView as MaterialCardView
                val hasImage = item.type == PinType.IMAGE
                val contentPadding = if (hasImage) imagePinPaddingPx else textPinPaddingPx
                itemBinding.itemContentContainer.setPadding(
                    contentPadding,
                    contentPadding,
                    contentPadding,
                    contentPadding
                )

                val isPinned = item.pinnedAt > 0L
                GlassEffectUtils.applyCardStyle(
                    card = itemCard,
                    color = pinnedCardColor(isPinned),
                    glassEnabled = pinPoolCardBackgroundBlurEnabled,
                    clearForeground = false,
                    blurRegion = false,
                )
                applyPinnedChrome(itemCard, isPinned, hasImage)
                bindReminderIcon(itemBinding, item)

                if (hasImage) {
                    bindImagePreview(itemBinding, item)
                } else {
                    itemBinding.previewImage.visibility = View.GONE
                    itemBinding.previewImage.tag = null
                    itemBinding.previewText.visibility = View.VISIBLE
                    itemBinding.previewText.text = item.text.orEmpty()
                    itemBinding.previewImage.setImageDrawable(null)
                }
                itemView.setOnTouchListener(
                    LongPressDragDetector(
                        view = itemView,
                        onLongPress = { prepareItemDrag(item) },
                        onDragSend = { startItemDragSend(itemView, item) },
                        onLongPressUp = { showShareMenu(itemView, item) },
                    )
                )
            }

            onClick(R.id.pinPoolItem) {
                if (!dragSending) {
                    showPinMenu(itemView, getModel())
                }
            }
        }
        binding.closeButton.setOnClickListener { finish() }
        binding.root.setOnClickListener { finish() }
        binding.cardContainer.setOnClickListener { }
        binding.clearButton.setOnClickListener { confirmClearPins() }
        binding.categorySwitcher.setOnClickListener { toggleCategory() }
        bindCategoryTitle()
    }

    private fun toggleCategory() {
        currentCategory = if (currentCategory == PinPoolCategory.SCREEN_CONTENT) {
            PinPoolCategory.GENERAL
        } else {
            PinPoolCategory.SCREEN_CONTENT
        }
        bindCategoryTitle()
        if (hasLoadedInitialConfig) {
            loadPins()
        }
    }

    private fun bindCategoryTitle() {
        binding.title.setText(
            if (currentCategory == PinPoolCategory.SCREEN_CONTENT) {
                R.string.pin_pool_category_screen_content
            } else {
                R.string.pin_pool_title
            }
        )
    }

    private fun showWithAnimation() {
        if (isAnimating) return
        isAnimating = true
        AnimatorSet().apply {
            val animators = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(binding.cardContainer, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.cardContainer, View.SCALE_X, 0.96f, 1f),
                ObjectAnimator.ofFloat(binding.cardContainer, View.SCALE_Y, 0.96f, 1f)
            )
            if (pinPoolBackgroundBlurEnabled) {
                GlassEffectUtils.createWindowBlurAnimator(window, 0f, 1f)?.let(animators::add)
            }
            playTogether(animators)
            duration = 260L
            addListener(onEnd = { isAnimating = false })
            start()
        }
    }

    private fun finishWithAnimation() {
        if (isClosing) return
        isClosing = true
        if (isAnimating) {
            finish()
            return
        }
        isAnimating = true
        AnimatorSet().apply {
            val animators = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(binding.cardContainer, View.ALPHA, binding.cardContainer.alpha, 0f),
                ObjectAnimator.ofFloat(binding.cardContainer, View.SCALE_X, binding.cardContainer.scaleX, 0.96f),
                ObjectAnimator.ofFloat(binding.cardContainer, View.SCALE_Y, binding.cardContainer.scaleY, 0.96f)
            )
            if (pinPoolBackgroundBlurEnabled) {
                GlassEffectUtils.createWindowBlurAnimator(window, 1f, 0f)?.let(animators::add)
            }
            playTogether(animators)
            duration = 200L
            addListener(onEnd = {
                isAnimating = false
                finish()
            })
            start()
        }
    }

    override fun finish() {
        if (!isClosing && ::binding.isInitialized) {
            finishWithAnimation()
            return
        }
        super.finish()
    }

    private fun applyDisplayMode(displayMode: String) {
        val metrics = resources.displayMetrics
        val cardLayoutParams = binding.cardContainer.layoutParams as FrameLayout.LayoutParams

        val windowScrim = pinPoolBackgroundBlurEnabled
        val cardRegionBlur = GlassEffectUtils.usesCardRegionBlur(
            pinPoolBackgroundBlurEnabled,
            pinPoolCardBackgroundBlurEnabled,
        )
        if (displayMode == PIN_POOL_DISPLAY_MODE_FULLSCREEN) {
            binding.root.setBackgroundColor(
                GlassEffectUtils.glassScrimColor(
                    DynamicColorUtils.surfaceContainerHighColor,
                    windowScrim
                )
            )
            if (binding.cardContainer.strokeWidth != 0) binding.cardContainer.strokeWidth = 0
            GlassEffectUtils.setCardShadowEnabled(binding.cardContainer, false)
            cardLayoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            cardLayoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            cardLayoutParams.gravity = Gravity.CENTER
            cardLayoutParams.setMargins(0, 0, 0, 0)
            binding.cardContainer.radius = 0f
            applySystemBarColors(
                GlassEffectUtils.glassScrimColor(
                    DynamicColorUtils.surfaceContainerHighColor,
                    windowScrim
                )
            )
        } else {
            val margin = 16.dp2px.toInt()
            binding.root.setBackgroundColor(Color.TRANSPARENT)
            cardLayoutParams.width = (metrics.widthPixels * 0.5f).toInt()
            cardLayoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            val horizontalGravity = if (displayMode == PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR) {
                Gravity.START
            } else {
                Gravity.END
            }
            cardLayoutParams.gravity = horizontalGravity or Gravity.CENTER_VERTICAL
            cardLayoutParams.setMargins(margin, margin, margin, margin)
            binding.cardContainer.radius = 20.dp2px
            applySystemBarColors(Color.TRANSPARENT)

            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                cardLayoutParams.height = (metrics.heightPixels * 0.7f).toInt()
                cardLayoutParams.gravity = horizontalGravity or Gravity.BOTTOM
            }
        }
        binding.cardContainer.layoutParams = cardLayoutParams
        // Configure blur after the display mode has supplied its final corners.
        GlassEffectUtils.applyCardStyle(
            card = binding.cardContainer,
            color = DynamicColorUtils.surfaceContainerHighColor,
            glassEnabled = pinPoolCardBackgroundBlurEnabled,
            blurRegion = cardRegionBlur,
            backgroundBlurEnabled = pinPoolBackgroundBlurEnabled,
        )
        if (displayMode != PIN_POOL_DISPLAY_MODE_FULLSCREEN) {
            GlassEffectUtils.applyFixedGlassChrome(binding.cardContainer)
        }
    }

    private fun applySystemBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun loadPins() = lifecycleScope.launch(Dispatchers.Main) {
        val items = withContext(Dispatchers.IO) {
            PinPoolStore.sortPins(PinPoolStore.loadPinsForDisplay(this@PinPoolFloating))
        }
        updatePins(items)
    }

    private fun togglePin(item: PinPoolItem) = lifecycleScope.launch(Dispatchers.Main) {
        val updated = withContext(Dispatchers.IO) {
            PinPoolStore.sortPins(PinPoolStore.togglePinTop(this@PinPoolFloating, item))
        }
        updatePins(updated)
    }

    private fun deletePin(item: PinPoolItem) = lifecycleScope.launch(Dispatchers.Main) {
        val updated = withContext(Dispatchers.IO) {
            PinPoolStore.sortPins(PinPoolStore.deletePin(this@PinPoolFloating, item))
        }
        if (item.type == PinType.IMAGE) {
            item.imagePath?.let(::evictImagePreviewCache)
        }
        updatePins(updated)
    }

    private fun bindImagePreview(itemBinding: PinPoolItemBinding, item: PinPoolItem) {
        val imageView = itemBinding.previewImage
        val path = item.imagePath
        imageView.visibility = View.VISIBLE
        itemBinding.previewText.visibility = View.GONE
        itemBinding.previewText.text = ""

        if (path.isNullOrEmpty()) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }

        val cacheKey = previewCacheKey(path, previewMaxWidthPx, previewMaxHeightPx)
        imageView.tag = cacheKey
        applyImageBounds(imageView, imageBoundsCache[path])

        previewBitmapCache.get(cacheKey)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            return
        }

        imageView.setImageDrawable(null)
        lifecycleScope.launch(Dispatchers.Main) {
            val preview = withContext(Dispatchers.IO) {
                loadImagePreview(path, previewMaxWidthPx, previewMaxHeightPx)
            }
            if (imageView.tag != cacheKey) return@launch
            applyImageBounds(imageView, preview.bounds)
            preview.bounds?.let { imageBoundsCache[path] = it }
            preview.bitmap?.let { bitmap ->
                previewBitmapCache.put(cacheKey, bitmap)
                imageView.setImageBitmap(bitmap)
            } ?: imageView.setImageDrawable(null)
        }
    }

    private fun repin(item: PinPoolItem) = lifecycleScope.launch(Dispatchers.Main) {
        when (item.type) {
            PinType.TEXT -> {
                val content = item.text?.trim().orEmpty()
                if (content.isNotEmpty()) {
                    EventBusManager.produceInspireEvent(
                        InspireEvent(text = content, skipAutoPin = true)
                    )
                } else {
                    ToastHelper.showInUIThread(
                        this@PinPoolFloating,
                        getString(R.string.pin_pool_text_not_found)
                    )
                }
            }

            PinType.IMAGE -> {
                val metrics = resources.displayMetrics
                val bitmap = withContext(Dispatchers.IO) {
                    item.imagePath?.let {
                        loadScaledBitmap(it, metrics.widthPixels, metrics.heightPixels)
                    }
                }
                if (bitmap != null) {
                    EventBusManager.produceInspireEvent(
                        InspireEvent(image = ManagedBitmap.from(bitmap), skipAutoPin = true)
                    )
                } else {
                    ToastHelper.showInUIThread(
                        this@PinPoolFloating,
                        getString(R.string.pin_pool_image_read_failed)
                    )
                }
            }
        }
    }

    private suspend fun tokenizeItem(item: PinPoolItem) {
        var textToTokenize: String? = null
        var imageToTokenize: ManagedBitmap? = null

        when (item.type) {
            PinType.TEXT -> {
                val content = item.text?.trim().orEmpty()
                if (content.isEmpty()) {
                    ToastHelper.showInUIThread(
                        this@PinPoolFloating,
                        getString(R.string.pin_pool_text_not_found)
                    )
                    return
                }
                textToTokenize = content
            }

            PinType.IMAGE -> {
                val metrics = resources.displayMetrics
                val bitmap = withContext(Dispatchers.IO) {
                    item.imagePath?.let {
                        loadScaledBitmap(it, metrics.widthPixels, metrics.heightPixels)
                    }
                }
                if (bitmap != null) {
                    imageToTokenize = ManagedBitmap.from(bitmap)
                } else {
                    ToastHelper.showInUIThread(
                        this@PinPoolFloating,
                        getString(R.string.pin_pool_image_read_failed)
                    )
                    return
                }
            }
        }

        ClipboardToInspireActivityUtils(this@PinPoolFloating).launcherInspire(
            image = imageToTokenize,
            text = textToTokenize
        )
    }

    private fun prepareItemDrag(item: PinPoolItem) {
        if (item.type != PinType.IMAGE) return
        val path = item.imagePath ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = PinDragSend.publishFile(this@PinPoolFloating, File(path))
            withContext(Dispatchers.Main) {
                preparedDragItemId = item.id
                preparedDragUri = uri
            }
        }
    }

    private fun startItemDragSend(anchorView: View, item: PinPoolItem): Boolean {
        val clip = when (item.type) {
            PinType.TEXT -> {
                val text = item.text?.trim().orEmpty()
                if (text.isEmpty()) {
                    ToastHelper.showInUIThread(this, getString(R.string.pin_pool_text_not_found))
                    return false
                }
                PinDragSend.clipForText(text)
            }
            PinType.IMAGE -> {
                val uri = if (preparedDragItemId == item.id) {
                    preparedDragUri
                } else {
                    null
                } ?: item.imagePath?.let { PinDragSend.publishFile(this, File(it)) }
                if (uri == null) {
                    ToastHelper.showInUIThread(this, getString(R.string.pin_pool_share_missing_image))
                    return false
                }
                PinDragSend.clipForImage(this, uri)
            }
        }
        PinDragCancelOverlay.show(applicationContext, item) {
            finishAfterDragSend()
        }
        if (!PinDragSend.start(anchorView, clip, shadowView = anchorView)) {
            PinDragCancelOverlay.hide()
            ToastHelper.showInUIThread(this, getString(R.string.pin_drag_send_failed))
            return false
        }
        dragSending = true
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        moveTaskToBack(true)
        overridePendingTransition(0, 0)
        return true
    }

    private fun finishAfterDragSend() {
        PinDragCancelOverlay.hide()
        dragSending = false
        if (!isFinishing) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun showShareMenu(anchorView: View, item: PinPoolItem) {
        lifecycleScope.launch(Dispatchers.Main) {
            val targetPackage = withContext(Dispatchers.IO) { resolveCurrentPackageName() }
            if (targetPackage.isNullOrEmpty()) {
                ToastHelper.showInUIThread(
                    this@PinPoolFloating,
                    getString(R.string.pin_pool_share_no_recent_app)
                )
                return@launch
            }
            val shareActivities = withContext(Dispatchers.IO) {
                queryShareActivities(targetPackage, item)
            }
            showSharePopup(anchorView, item, targetPackage, shareActivities)
        }
    }

    private fun queryShareActivities(
        targetPackage: String,
        item: PinPoolItem
    ): List<ShareActivityItem> {
        val type = if (item.type == PinType.TEXT) "text/plain" else "image/*"
        val intent = Intent(Intent.ACTION_SEND).apply { this.type = type }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return resolveInfos.mapNotNull { resolveInfo ->
            val info = resolveInfo.activityInfo ?: return@mapNotNull null
            if (info.packageName != targetPackage) return@mapNotNull null
            val label = resolveInfo.loadLabel(packageManager)
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { packageManager.getApplicationLabel(info.applicationInfo) }
                    .getOrNull()
                ?: info.name
            ShareActivityItem(
                packageName = info.packageName,
                activityName = info.name,
                label = label,
                icon = runCatching { info.loadIcon(packageManager) }.getOrNull()
            )
        }
            .sortedBy { it.label.toString() }
    }

    private fun showSharePopup(
        anchorView: View,
        item: PinPoolItem,
        targetPackage: String,
        shareActivities: List<ShareActivityItem>
    ) {
        val targetAppIcon = runCatching { packageManager.getApplicationIcon(targetPackage) }
            .getOrNull()
        val pasteIcon = targetAppIcon
            ?: ContextCompat.getDrawable(this, R.drawable.content_paste_go_20px)
        val shareFallback = ContextCompat.getDrawable(this, R.drawable.ic_baseline_share_24)
        val actions = buildList {
            add(
                ActionTextMenuItem(
                    label = getString(R.string.pin_pool_share_paste),
                    icon = pasteIcon
                ) {
                    pasteToRecentApp(targetPackage, item)
                }
            )
            shareActivities.forEach { target ->
                add(
                    ActionTextMenuItem(
                        label = target.label,
                        icon = target.icon ?: shareFallback
                    ) {
                        shareToActivity(target, item)
                    }
                )
            }
        }
        ActionTextMenuPopup(
            this,
            binding.root,
            minWidth = 180.dp2px.toInt(),
            cardBlurEnabled = pinPoolCardBackgroundBlurEnabled,
            backgroundBlurEnabled = pinPoolBackgroundBlurEnabled,
        ).show(anchorView, actions)
    }

    private fun pasteToRecentApp(targetPackage: String, item: PinPoolItem) {
        when (item.type) {
            PinType.TEXT -> {
                val content = item.text?.trim().orEmpty()
                if (content.isEmpty()) {
                    ToastHelper.showInUIThread(
                        this@PinPoolFloating,
                        getString(R.string.pin_pool_text_not_found)
                    )
                    return
                }
                ClipboardUtils.copyText(applicationContext, content)
                lifecycleScope.launch(Dispatchers.IO) {
                    EventBusManager.produceSearchEvent(
                        SearchEvent(
                            InjectionTask(
                                query = content,
                                targetPackage = targetPackage,
                                targetActivity = "",
                                inputEnter = false,
                                usePaste = true
                            )
                        )
                    )
                }
                launchTargetApp(targetPackage)
            }

            PinType.IMAGE -> {
                lifecycleScope.launch(Dispatchers.Main) {
                    val metrics = resources.displayMetrics
                    val bitmap = withContext(Dispatchers.IO) {
                        item.imagePath?.let {
                            loadScaledBitmap(it, metrics.widthPixels, metrics.heightPixels)
                        }
                    }
                        if (bitmap != null) {
                            ManagedBitmap.from(bitmap).use {
                                InspireHelperUtils.copyBitmapToClipboard(
                                    this@PinPoolFloating,
                                    it,
                                    showToast = false
                                )
                            }
                            lifecycleScope.launch(Dispatchers.IO) {
                                EventBusManager.produceSearchEvent(
                                    SearchEvent(
                                    InjectionTask(
                                        query = "",
                                        targetPackage = targetPackage,
                                        targetActivity = "",
                                        inputEnter = false,
                                        usePaste = true
                                    )
                                )
                            )
                        }
                        launchTargetApp(targetPackage)
                    } else {
                        ToastHelper.showInUIThread(
                            this@PinPoolFloating,
                            getString(R.string.pin_pool_image_read_failed)
                        )
                    }
                }
            }
        }
    }

    private fun launchTargetApp(targetPackage: String) {
        val intent = packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent != null) {
            runCatching { startActivity(intent) }
                .onFailure {
                    ToastHelper.showInUIThread(
                        this@PinPoolFloating,
                        getString(R.string.pin_pool_share_failed)
                    )
                }
        } else {
            ToastHelper.showInUIThread(
                this@PinPoolFloating,
                getString(R.string.pin_pool_share_failed)
            )
        }
    }

    private fun shareToActivity(target: ShareActivityItem, item: PinPoolItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(target.packageName, target.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        when (item.type) {
            PinType.TEXT -> {
                val content = item.text?.trim().orEmpty()
                if (content.isEmpty()) {
                    ToastHelper.showInUIThread(
                        this,
                        getString(R.string.pin_pool_text_not_found)
                    )
                    return
                }
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, content)
            }

            PinType.IMAGE -> {
                val path = item.imagePath
                val imageUri = path?.let { getPinImageUri(it) }
                if (imageUri == null) {
                    ToastHelper.showInUIThread(
                        this,
                        getString(R.string.pin_pool_share_missing_image)
                    )
                    return
                }
                intent.type = "image/*"
                intent.putExtra(Intent.EXTRA_STREAM, imageUri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            ToastHelper.showInUIThread(this, getString(R.string.pin_pool_share_failed))
        }
    }

    private fun getPinImageUri(path: String): Uri? {
        val file = File(path)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
    }

    private fun showPinMenu(anchorView: View, item: PinPoolItem) {
        val pinTitle = if (item.pinnedAt > 0L) {
            getString(R.string.pin_pool_action_unpin)
        } else {
            getString(R.string.pin_pool_action_pin)
        }

        ActionTextMenuPopup(
            this,
            binding.root,
            cardBlurEnabled = pinPoolCardBackgroundBlurEnabled,
            backgroundBlurEnabled = pinPoolBackgroundBlurEnabled,
        ).show(
            anchorView,
            listOf(
                ActionTextMenuItem(pinTitle) {
                    togglePin(item)
                },
                ActionTextMenuItem(getString(R.string.pin_pool_action_repin)) {
                    repin(item)
                },
                ActionTextMenuItem(getString(R.string.pin_pool_action_tokenize)) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        tokenizeItem(item)
                    }
                },
                ActionTextMenuItem(getString(R.string.pin_pool_action_copy)) {
                    copyPinContent(item)
                },
                ActionTextMenuItem(getString(R.string.pin_pool_action_share)) {
                    sharePinContent(item)
                },
                ActionTextMenuItem(getString(R.string.pin_pool_action_reminder)) {
                    startReminderFlow(item)
                },
                ActionTextMenuItem(
                    label = getString(R.string.pin_pool_action_delete),
                    destructive = true
                ) {
                    deletePin(item)
                }
            )
        )
    }

    private fun sharePinContent(item: PinPoolItem) {
        when (item.type) {
            PinType.TEXT -> {
                val content = item.text?.trim().orEmpty()
                if (content.isEmpty()) {
                    ToastHelper.showInUIThread(this, getString(R.string.pin_pool_text_not_found))
                    return
                }
                InspireHelperUtils.shareText(this, content)
            }

            PinType.IMAGE -> {
                val uri = item.imagePath?.let { getPinImageUri(it) }
                if (uri == null) {
                    ToastHelper.showInUIThread(this, getString(R.string.pin_pool_share_missing_image))
                    return
                }
                val payload = imageShareLauncher.buildPayload(
                    uris = listOf(uri),
                    targetPackage = null,
                    targetActivity = null,
                )
                if (payload == null || !imageShareLauncher.launchViaChooser(payload)) {
                    ToastHelper.showInUIThread(this, getString(R.string.pin_pool_share_failed))
                }
            }
        }
    }

    private fun copyPinContent(item: PinPoolItem) {
        when (item.type) {
            PinType.TEXT -> {
                val content = item.text?.trim().orEmpty()
                if (content.isNotEmpty()) {
                    ClipboardUtils.copyText(applicationContext, content)
                } else {
                    ToastHelper.showInUIThread(
                        this@PinPoolFloating,
                        getString(R.string.pin_pool_text_not_found)
                    )
                }
            }

            PinType.IMAGE -> {
                lifecycleScope.launch(Dispatchers.Main) {
                    val metrics = resources.displayMetrics
                    val bitmap = withContext(Dispatchers.IO) {
                        item.imagePath?.let {
                            loadScaledBitmap(it, metrics.widthPixels, metrics.heightPixels)
                        }
                    }
                    if (bitmap != null) {
                        ManagedBitmap.from(bitmap).use {
                            InspireHelperUtils.copyBitmapToClipboard(
                                this@PinPoolFloating,
                                it
                            )
                        }
                    } else {
                        ToastHelper.showInUIThread(
                            this@PinPoolFloating,
                            getString(R.string.pin_pool_image_read_failed)
                        )
                    }
                }
            }
        }
    }

    private fun showReminderDatePicker(item: PinPoolItem, initialTimeMillis: Long? = null) {
        dismissReminderPickers()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pin_pool_reminder_pick_date)
            .setSelection(
                initialTimeMillis?.let { toDatePickerSelection(it) }
                    ?: MaterialDatePicker.todayInUtcMilliseconds()
            )
            .build()
        picker.addOnPositiveButtonClickListener { selectedDate ->
            showReminderTimePicker(item, selectedDate, initialTimeMillis)
        }
        picker.show(supportFragmentManager, "pin_pool_reminder_date")
    }

    private fun showReminderTimePicker(
        item: PinPoolItem,
        selectedDate: Long,
        initialTimeMillis: Long? = null
    ) {
        val initialTime = Calendar.getInstance().apply {
            timeInMillis = initialTimeMillis ?: System.currentTimeMillis()
        }
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(initialTime.get(Calendar.HOUR_OF_DAY))
            .setMinute(initialTime.get(Calendar.MINUTE))
            .setTitleText(R.string.pin_pool_reminder_pick_time)
            .build()
        picker.addOnPositiveButtonClickListener {
            val triggerAt = buildReminderTime(selectedDate, picker.hour, picker.minute)
            if (triggerAt <= System.currentTimeMillis()) {
                ToastHelper.showInUIThread(this, getString(R.string.pin_pool_reminder_time_invalid))
                return@addOnPositiveButtonClickListener
            }
            setReminder(item, triggerAt)
        }
        picker.show(supportFragmentManager, "pin_pool_reminder_time")
    }

    private fun dismissReminderPickers() {
        listOf("pin_pool_reminder_date", "pin_pool_reminder_time").forEach { tag ->
            (supportFragmentManager.findFragmentByTag(tag) as? DialogFragment)
                ?.dismissAllowingStateLoss()
        }
    }

    private fun toDatePickerSelection(timeMillis: Long): Long {
        val localCalendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(Calendar.YEAR, localCalendar.get(Calendar.YEAR))
            set(Calendar.MONTH, localCalendar.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, localCalendar.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun setReminder(item: PinPoolItem, triggerAt: Long) {
        reminderCandidateDialog?.dismiss()
        PinPoolReminderManager.schedule(applicationContext, item, triggerAt)
        lifecycleScope.launch(Dispatchers.Main) {
            val updated = withContext(Dispatchers.IO) {
                PinPoolStore.sortPins(
                    PinPoolStore.updateReminderAt(this@PinPoolFloating, item, triggerAt)
                )
            }
            updatePins(updated)
        }
        ToastHelper.showInUIThread(
            this,
            getString(R.string.pin_pool_reminder_scheduled, formatReminderTime(triggerAt))
        )
    }

    private fun buildReminderTime(selectedDate: Long, hour: Int, minute: Int): Long {
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = selectedDate
        }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, utcCalendar.get(Calendar.YEAR))
            set(Calendar.MONTH, utcCalendar.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utcCalendar.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun pinnedCardColor(isPinned: Boolean): Int {
        val base = if (isPinned) {
            DynamicColorUtils.surfaceContainerHighColor
        } else {
            DynamicColorUtils.surfaceContainerColor
        }
        val primary = DynamicColorUtils.primaryColor
        if (!isPinned || primary == 0) return base
        return ColorUtils.blendARGB(base, primary, 0.22f)
    }

    private fun applyPinnedChrome(
        card: MaterialCardView,
        isPinned: Boolean,
        hasImage: Boolean
    ) {
        if (isPinned) {
            card.strokeWidth = 0
            val border = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = card.radius
                setColor(Color.TRANSPARENT)
                setStroke(pinnedStrokeWidthPx, DynamicColorUtils.primaryColor)
            }
            card.foreground = border
            return
        }
        card.foreground = null
        if (hasImage) {
            if (card.strokeWidth != 0) card.strokeWidth = 0
        } else {
            GlassEffectUtils.applyGlassEdge(card)
        }
    }

    private fun bindReminderIcon(itemBinding: PinPoolItemBinding, item: PinPoolItem) {
        val reminderAt = item.reminderAt
        if (reminderAt > System.currentTimeMillis()) {
            itemBinding.reminderIcon.visibility = View.VISIBLE
            itemBinding.reminderIcon.setOnClickListener {
                ToastHelper.showInUIThread(
                    this,
                    getString(
                        R.string.pin_pool_reminder_time_tip,
                        formatReminderTime(reminderAt)
                    )
                )
            }
        } else {
            itemBinding.reminderIcon.visibility = View.GONE
            itemBinding.reminderIcon.setOnClickListener(null)
        }
    }

    private fun formatReminderTime(timeMillis: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(timeMillis)
    }

    private fun startReminderFlow(item: PinPoolItem) {
        if (NotificationPermissionUtils.canPostNotifications(this)) {
            showReminderDatePicker(item)
            detectReminderCandidateAsync(item)
            return
        }
        NotificationPermissionUtils.showDisabledPromptIfNeeded(this)
    }

    private fun detectReminderCandidateAsync(item: PinPoolItem) {
        lifecycleScope.launch(Dispatchers.Main) {
            val candidate = withContext(Dispatchers.IO) {
                if (!isLocalRuleExtractionEnabled()) return@withContext null
                val text = extractReminderText(item) ?: return@withContext null
                PinPoolReminderTimeParser.findBest(text)
            } ?: return@launch
            showReminderCandidateCard(item, candidate)
        }
    }

    private suspend fun extractReminderText(item: PinPoolItem): String? {
        return when (item.type) {
            PinType.TEXT -> item.text?.trim()?.takeIf { it.isNotEmpty() }
            PinType.IMAGE -> {
                val metrics = resources.displayMetrics
                val bitmap = item.imagePath?.let {
                    loadScaledBitmap(it, metrics.widthPixels, metrics.heightPixels)
                } ?: return null
                try {
                    MlKitOcr.detect(bitmap)
                        .mapNotNull { it.content?.trim()?.takeIf(String::isNotEmpty) }
                        .joinToString(" ")
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private suspend fun isLocalRuleExtractionEnabled(): Boolean {
        return settingsStore.readKey(
            KEY_PIN_POOL_LOCAL_RULE_EXTRACTION,
            DEFAULT_PIN_POOL_LOCAL_RULE_EXTRACTION
        )
    }

    private fun showReminderCandidateCard(
        item: PinPoolItem,
        candidate: PinPoolReminderTimeCandidate
    ) {
        if (isFinishing) return
        reminderCandidateDialog?.dismiss()
        val card = LayoutInflater.from(this)
            .inflate(R.layout.pin_pool_reminder_candidate_card, binding.root, false)
        card.findViewById<TextView>(R.id.reminderCandidateTitle).text =
            getString(R.string.pin_pool_reminder_candidate_title)
        card.findViewById<TextView>(R.id.reminderCandidateTime).text = getString(
            R.string.pin_pool_reminder_candidate_time,
            formatReminderTime(candidate.triggerAt),
            candidate.matchedText
        )
        card.setOnClickListener {
            reminderCandidateDialog?.dismiss()
            showReminderDatePicker(item, candidate.triggerAt)
        }

        reminderCandidateDialog = MaterialAlertDialogBuilder(this)
            .setView(card)
            .create()
            .apply {
                setOnShowListener {
                    window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                    window?.setDimAmount(0.12f)
                    window?.setLayout(
                        (resources.displayMetrics.widthPixels * 0.86f).toInt(),
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                }
                show()
            }
    }

    override fun onDestroy() {
        GlassEffectUtils.applyWindowBlur(window, false)
        reminderCandidateDialog?.dismiss()
        PinDragCancelOverlay.hide()
        super.onDestroy()
    }

    private fun confirmClearPins() {
        val categoryName = getString(
            if (currentCategory == PinPoolCategory.SCREEN_CONTENT) {
                R.string.pin_pool_category_screen_content
            } else {
                R.string.pin_pool_title
            }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pin_pool_clear_title, categoryName))
            .setMessage(getString(R.string.pin_pool_clear_message, categoryName))
            .setNegativeButton(R.string.pin_pool_cancel, null)
            .setPositiveButton(R.string.pin_pool_clear_action) { _, _ ->
                lifecycleScope.launch(Dispatchers.Main) {
                    val remaining = withContext(Dispatchers.IO) {
                        PinPoolStore.sortPins(
                            PinPoolStore.clearPins(this@PinPoolFloating, currentCategory)
                        )
                    }
                    clearMissingImagePreviewCache(remaining)
                    updatePins(remaining)
                }
            }
            .show()
    }

    private fun updatePins(items: List<PinPoolItem>) {
        val visible = items.filter { it.matchesCategory(currentCategory) }
        binding.pinRecycler.models = visible
        binding.emptyView.setText(
            if (currentCategory == PinPoolCategory.SCREEN_CONTENT) {
                R.string.pin_pool_screen_content_empty
            } else {
                R.string.pin_pool_empty
            }
        )
        binding.emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.clearButton.isEnabled = visible.any { it.pinnedAt <= 0L }
        scrollToPendingItem(visible)
    }

    private fun scrollToPendingItem(items: List<PinPoolItem>) {
        val targetId = pendingScrollItemId ?: return
        val index = items.indexOfFirst { it.id == targetId }
        if (index < 0) return
        pendingScrollItemId = null
        binding.pinRecycler.post {
            binding.pinRecycler.smoothScrollToPosition(index)
        }
    }

    private fun applyWindowInsets() {
        val initialPadding = binding.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = initialPadding + systemBars.top)
            insets
        }
    }

    private fun loadImagePreview(path: String, reqWidth: Int, reqHeight: Int): ImagePreview {
        val file = File(path)
        if (!file.exists()) return ImagePreview(null, null)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        val bounds = if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
        if (bounds == null) return ImagePreview(null, null)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return ImagePreview(bounds, BitmapFactory.decodeFile(path, options))
    }

    private fun applyImageBounds(imageView: AppCompatImageView, bounds: Pair<Int, Int>?) {
        imageView.maxWidth = min(bounds?.first ?: previewMaxWidthPx, previewMaxWidthPx)
        imageView.maxHeight = min(bounds?.second ?: previewMaxHeightPx, previewMaxHeightPx)
    }

    private fun previewCacheKey(path: String, reqWidth: Int, reqHeight: Int): String {
        return "$path@$reqWidth:$reqHeight"
    }

    private fun evictImagePreviewCache(path: String) {
        imageBoundsCache.remove(path)
        previewBitmapCache.remove(previewCacheKey(path, previewMaxWidthPx, previewMaxHeightPx))
    }

    private fun clearMissingImagePreviewCache(items: List<PinPoolItem>) {
        val retainedPaths = items.mapNotNull { it.imagePath }.toSet()
        imageBoundsCache.keys.toList()
            .filterNot { it in retainedPaths }
            .forEach(::evictImagePreviewCache)
    }

    private fun loadScaledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeImageBounds(path: String): Pair<Int, Int>? {
        val file = File(path)
        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    companion object {
        const val EXTRA_SCROLL_TO_PIN_ID = "extra_scroll_to_pin_id"
        const val EXTRA_SIDE = "extra_pin_pool_side"
        private var currentActivity: WeakReference<PinPoolFloating>? = null

        private fun previewCacheSizeKb(): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            return (maxMemoryKb / 16).coerceIn(4 * 1024, 24 * 1024)
        }

        fun start(context: Context, scrollToPinId: String? = null, side: String? = null) {
            val intent = Intent(context, PinPoolFloating::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                scrollToPinId?.let { putExtra(EXTRA_SCROLL_TO_PIN_ID, it) }
                side?.let { putExtra(EXTRA_SIDE, it) }
            }
            TransparentActivityLauncher.start(context, intent)
        }

        fun finishIfShowing() {
            currentActivity?.get()?.finish()
        }
    }
}

private data class ImagePreview(
    val bounds: Pair<Int, Int>?,
    val bitmap: Bitmap?
)

private data class ShareActivityItem(
    val packageName: String,
    val activityName: String,
    val label: CharSequence,
    val icon: Drawable?
)
