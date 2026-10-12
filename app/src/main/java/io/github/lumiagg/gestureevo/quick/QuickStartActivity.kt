package io.github.lumiagg.gestureevo.quick

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.Animator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.addCallback
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.addListener
import androidx.core.view.allViews
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.utils.grid
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.QuickStartFloatingViewBinding
import io.github.lumiagg.gestureevo.databinding.QuickStartItemBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.quick.ui.KeyboardOnChangeListener
import io.github.lumiagg.gestureevo.quick.ui.KeyboardType
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_KEYBOARD_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_KEYBOARD_CARD_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_KEYBOARD_TYPE
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.TransparentActivityLauncher
import io.github.lumiagg.gestureevo.utils.TextUtil
import io.github.lumiagg.gestureevo.utils.coerceInSafe
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalogIfNeeded
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickStartActivity : AppCompatActivity() {
    companion object {
        private const val KEY_KEYBOARD_WIDTH_PORT = "key_keyboard_width_port"
        private const val KEY_KEYBOARD_HEIGHT_PORT = "key_keyboard_height_port"
        private const val KEY_KEYBOARD_LOC_X_PORT = "key_keyboard_loc_x_port"
        private const val KEY_KEYBOARD_LOC_Y_PORT = "key_keyboard_loc_y_port"

        private const val KEY_KEYBOARD_WIDTH_LAND = "key_keyboard_width_land"
        private const val KEY_KEYBOARD_HEIGHT_LAND = "key_keyboard_height_land"
        private const val KEY_KEYBOARD_LOC_X_LAND = "key_keyboard_loc_x_land"
        private const val KEY_KEYBOARD_LOC_Y_LAND = "key_keyboard_loc_y_land"

        private const val DEFAULT_WIDTH_RATIO_PORT = 0.9f
        private const val DEFAULT_HEIGHT_RATIO_PORT = 0.58f
        private const val DEFAULT_WIDTH_RATIO_LAND = 0.62f
        private const val DEFAULT_HEIGHT_RATIO_LAND = 0.72f
        private const val MIN_SIZE = 200
        private const val MAX_SCREEN_RATIO = 0.9f // Maximum screen size ratio

        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

        private var inputKeys = ""

        fun start(context: Context, side: String? = null) {
            val intent = Intent(context, QuickStartActivity::class.java).apply {
                side?.let { putExtra(TriggerSide.EXTRA_TRIGGER_SIDE, it) }
            }
            TransparentActivityLauncher.start(context, intent)
        }
    }

    private data class KeySet(
        val widthKey: String,
        val heightKey: String,
        val xKey: String,
        val yKey: String,
    )

    private lateinit var binding: QuickStartFloatingViewBinding
    private var isResizeMode = false
    private var isAnimating = false
    private var keyboardBackgroundBlurEnabled = false
    private var keyboardCardBackgroundBlurEnabled = false
    private val minSize = MIN_SIZE.dp2px.toInt()
    private var triggerSide: String? = null

    private fun isLandscape(): Boolean {
        val displayMetrics = resources.displayMetrics
        return displayMetrics.widthPixels > displayMetrics.heightPixels
    }

    private fun getOrientationSpecificKeys(): KeySet {
        return if (isLandscape()) {
            KeySet(
                widthKey = KEY_KEYBOARD_WIDTH_LAND,
                heightKey = KEY_KEYBOARD_HEIGHT_LAND,
                xKey = KEY_KEYBOARD_LOC_X_LAND,
                yKey = KEY_KEYBOARD_LOC_Y_LAND
            )
        } else {
            KeySet(
                widthKey = KEY_KEYBOARD_WIDTH_PORT,
                heightKey = KEY_KEYBOARD_HEIGHT_PORT,
                xKey = KEY_KEYBOARD_LOC_X_PORT,
                yKey = KEY_KEYBOARD_LOC_Y_PORT
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        triggerSide = intent?.getStringExtra(TriggerSide.EXTRA_TRIGGER_SIDE)
        binding = QuickStartFloatingViewBinding.inflate(layoutInflater)

        binding.root.visibility = View.INVISIBLE
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this) { finishWithAnimation() }

        lifecycleScope.launch {
            initAppearanceConfig()
            applyKeyboardGlassStyle()
            initializeLayout()
            initAll()
            requestAppDbSync()

            withContext(Dispatchers.Main) {
                binding.root.visibility = View.VISIBLE
                binding.root.doOnPreDraw {
                    if (!isFinishing && !isDestroyed) showWithAnimation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        triggerSide = intent.getStringExtra(TriggerSide.EXTRA_TRIGGER_SIDE)
    }

    private suspend fun initAppearanceConfig() {
        keyboardBackgroundBlurEnabled = settingsStore.readKey(KEY_KEYBOARD_BACKGROUND_BLUR, false)
        keyboardCardBackgroundBlurEnabled = settingsStore.readKey(
            KEY_KEYBOARD_CARD_BACKGROUND_BLUR,
            false
        )
    }

    private fun applyKeyboardGlassStyle() {
        GlassEffectUtils.applyWindowBlurProgress(window, 0f)
        binding.root.setBackgroundColor(Color.TRANSPARENT)
        GlassEffectUtils.applyCardStyle(
            card = binding.quickStart,
            color = DynamicColorUtils.surfaceContainerHighColor,
            glassEnabled = keyboardCardBackgroundBlurEnabled,
            backgroundBlurEnabled = keyboardBackgroundBlurEnabled,
            blurRegion = GlassEffectUtils.usesCardRegionBlur(
                keyboardBackgroundBlurEnabled,
                keyboardCardBackgroundBlurEnabled,
            ),
        )
        GlassEffectUtils.applyFixedGlassChrome(binding.quickStart)
        applyKeyboardKeyGlassStyle()
    }

    private fun applyKeyboardKeyGlassStyle() {
        binding.keyboard.allViews.forEach { view ->
            val card = view as? MaterialCardView ?: return@forEach
            GlassEffectUtils.applyCardStyle(
                card = card,
                color = DynamicColorUtils.surfaceContainerColor,
                glassEnabled = keyboardCardBackgroundBlurEnabled,
                clearForeground = true,
                blurRegion = false,
            )
        }
    }

    private suspend fun initializeLayout() = withContext(Dispatchers.IO) {
        val keys = getOrientationSpecificKeys()
        val width = settingsStore.readKey(keys.widthKey, -1)
        val height = settingsStore.readKey(keys.heightKey, -1)
        val savedX = settingsStore.readKey(keys.xKey, -1)
        val savedY = settingsStore.readKey(keys.yKey, -1)
        val keyboardType = settingsStore.readKey(KEY_KEYBOARD_TYPE, KeyboardType.DOUBLE_KEY.name)

        withContext(Dispatchers.Main) {
            binding.quickStart.apply {
                alpha = 0f
                scaleX = 0.8f
                scaleY = 0.8f
            }

            // Retrieve screen dimensions
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Compute maximum allowed dimensions
            val maxWidth = (screenWidth * MAX_SCREEN_RATIO).toInt()
            val maxHeight = (screenHeight * MAX_SCREEN_RATIO).toInt()

            val (defaultWidth, defaultHeight) = if (isLandscape()) {
                Pair(
                    (screenWidth * DEFAULT_WIDTH_RATIO_LAND).toInt(),
                    (screenHeight * DEFAULT_HEIGHT_RATIO_LAND).toInt()
                )
            } else {
                Pair(
                    (screenWidth * DEFAULT_WIDTH_RATIO_PORT).toInt(),
                    (screenHeight * DEFAULT_HEIGHT_RATIO_PORT).toInt()
                )
            }

            val hasSavedPosition = savedX > 0 && savedY > 0
            val targetWidth = when {
                width <= 0 -> defaultWidth
                !hasSavedPosition && width < defaultWidth -> defaultWidth
                else -> width
            }
            val targetHeight = when {
                height <= 0 -> defaultHeight
                !hasSavedPosition && height < defaultHeight -> defaultHeight
                else -> height
            }

            // Clamp dimensions within valid range
            val finalWidth = targetWidth.coerceInSafe(minSize, maxWidth)
            val finalHeight = targetHeight.coerceInSafe(minSize, maxHeight)

            // Apply layout dimensions
            binding.quickStart.layoutParams = binding.quickStart.layoutParams.apply {
                this.width = finalWidth
                this.height = finalHeight
            }

            // Position layout once view dimensions are measured
            binding.quickStart.doOnLayout {
                // Calculate valid coordinate bounds
                val maxX = (screenWidth - finalWidth).coerceAtLeast(0)
                val maxY = (screenHeight - finalHeight).coerceAtLeast(0)

                if (savedX > 0 && savedY > 0) {
                    // Account for shadow and padding offsets
                    val shadowDx =
                        (binding.quickStart.cardElevation + binding.quickStart.contentPaddingLeft).toInt()
                    val shadowDy =
                        (binding.quickStart.cardElevation + binding.quickStart.contentPaddingTop).toInt()

                    // Compute adjusted position clamped to screen bounds
                    val newX = (savedX + shadowDx).toFloat()
                    val newY = (savedY + shadowDy).toFloat()

                    val finalX = newX.coerceInSafe(0f, maxX.toFloat())
                    val finalY = newY.coerceInSafe(0f, maxY.toFloat())

                    binding.quickStart.x = finalX
                    binding.quickStart.y = finalY

                    // Persist updated coordinates if clamped
                    if (finalX != newX || finalY != newY) {
                        saveLayoutState()
                    }
                } else {
                    // Center on screen
                    binding.quickStart.x =
                        ((screenWidth - finalWidth) / 2f).coerceInSafe(0f, maxX.toFloat())
                    binding.quickStart.y = ((screenHeight - finalHeight) / 2f)
                }
            }

            binding.keyboard.keyboardType = KeyboardType.valueOfSafe(keyboardType)
            applyKeyboardKeyGlassStyle()
        }
    }

    override fun onDestroy() {
        GlassEffectUtils.applyWindowBlur(window, false)
        super.onDestroy()
    }

    private fun initAll() {
        initView()
        initAppRecycler()
        initKeyboard()
        initTouchListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        binding.dragBarFrameLayout.setOnTouchListener(CombinedTouchListener())
    }

    private fun initAppRecycler() {
        binding.appRecycler.grid(
            spanCount = 1,
            reverseLayout = true,
            orientation = RecyclerView.HORIZONTAL
        ).setup {
            addType<APPModel>(R.layout.quick_start_item)
            onBind {
                val itemBinding = QuickStartItemBinding.bind(itemView)
                val model = getModel<APPModel>()
                itemBinding.name.text = model.name
                itemBinding.icon.setImagePadding(8.dp2px.toInt())
                itemBinding.icon.setImage(
                    IconHelper.getIcon(this@QuickStartActivity, model.pkgName)
                )
            }
            onClick(R.id.icon) {
                openAPP(getModel<APPModel>(), false)
            }
            onLongClick(R.id.icon) {
                openAPP(getModel<APPModel>(), true)
            }
        }
    }

    private fun initKeyboard() {
        binding.keyboard.onChangeListener = object : KeyboardOnChangeListener {
            override fun onInput(inputSequence: String) {
                super.onInput(inputSequence)
                CoroutineScope(Dispatchers.Main).launch {
                    binding.appRecycler.models = if (inputSequence.isNotEmpty()) {
                        withContext(Dispatchers.IO) { filterResult(inputSequence) }
                    } else {
                        listOf()
                    }
                }
            }

            override fun onEnter(longClick: Boolean) {
                binding.appRecycler.models?.firstOrNull()?.let {
                    openAPP(it as APPModel, longClick)
                }
            }

            override fun onSettings(longClick: Boolean) {
                isResizeMode = !isResizeMode
                lifecycleScope.launch {
                    binding.keyboard.setInputPrompt(
                        if (isResizeMode) getString(R.string.quick_start_resize_hint)
                        else getString(R.string.quick_start_move_hint)
                    )
                }

                binding.dragBar.animate()
                    .scaleX(if (isResizeMode) 1.5f else 1f)
                    .scaleY(if (isResizeMode) 1.5f else 1f)
                    .setDuration(200)
                    .start()
            }
        }
        binding.keyboard.resumeContent(inputKeys)
    }

    private fun requestAppDbSync() {
        lifecycleScope.launch(Dispatchers.Default) {
            syncAppCatalogIfNeeded(applicationContext)
            val inputSequence = binding.keyboard.getInputKeys()
            if (inputSequence.isNotEmpty()) {
                val results = withContext(Dispatchers.IO) { filterResult(inputSequence) }
                withContext(Dispatchers.Main) {
                    binding.appRecycler.models = results
                }
            }
        }
    }

    private fun initTouchListener() {
        binding.root.setOnClickListener {
            finishWithAnimation()
        }
    }

    private suspend fun filterResult(inputSequence: String): List<APPModel> {
        val resultSet = LinkedHashSet<APPModel>()
        when (binding.keyboard.keyboardType) {
            KeyboardType.T9 -> {
                resultSet.addAll(
                    getAPPDao().findByInitialsT9(inputSequence, APPModel.FLAG_LAUNCHER)
                )
                resultSet.addAll(
                    getAPPDao().findByPinYinT9(inputSequence, APPModel.FLAG_LAUNCHER)
                )
            }

            KeyboardType.DOUBLE_KEY, KeyboardType.FULL_KEY -> {
                resultSet.addAll(
                    getAPPDao().findByInitialsDK(inputSequence, APPModel.FLAG_LAUNCHER)
                )
                resultSet.addAll(
                    getAPPDao().findByPinYinDK(inputSequence, APPModel.FLAG_LAUNCHER)
                )
            }
        }

        return resultSet.toList().sortedWith(
            when (binding.keyboard.keyboardType) {
                KeyboardType.T9 -> compareByDescending<APPModel> { it.openCount }
                    .thenBy { -TextUtil.calculateSimilarity(inputSequence, it.initialsT9) }
                    .thenBy { -TextUtil.calculateSimilarity(inputSequence, it.pinyinT9) }

                else -> compareByDescending<APPModel> { it.openCount }
                    .thenBy { -TextUtil.calculateSimilarity(inputSequence, it.initialsDK) }
                    .thenBy { -TextUtil.calculateSimilarity(inputSequence, it.pinyinDK) }
            }
        )
    }

    private fun showWithAnimation() {
        if (isAnimating) return
        isAnimating = true

        AnimatorSet().apply {
            val animators = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(binding.quickStart, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.quickStart, View.SCALE_X, 0.8f, 1f),
                ObjectAnimator.ofFloat(binding.quickStart, View.SCALE_Y, 0.8f, 1f)
            )
            if (keyboardBackgroundBlurEnabled) {
                GlassEffectUtils.createWindowBlurAnimator(window, 0f, 1f)?.let(animators::add)
            }
            playTogether(animators)
            interpolator = EMPHASIZED
            duration = 300
            addListener(onEnd = { isAnimating = false })
            start()
        }
    }

    private fun hideWithAnimation(onEnd: () -> Unit = {}) {
        if (isAnimating) return
        isAnimating = true

        AnimatorSet().apply {
            val animators = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(binding.quickStart, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(binding.quickStart, View.SCALE_X, 1f, 0.8f),
                ObjectAnimator.ofFloat(binding.quickStart, View.SCALE_Y, 1f, 0.8f)
            )
            if (keyboardBackgroundBlurEnabled) {
                GlassEffectUtils.createWindowBlurAnimator(window, 1f, 0f)?.let(animators::add)
            }
            playTogether(animators)
            duration = 250
            interpolator = EMPHASIZED_ACCELERATE
            addListener(onEnd = {
                isAnimating = false
                onEnd()
            })
            start()
        }
    }

    private fun openAPP(model: APPModel, freeform: Boolean) {
        lifecycleScope.launch(Dispatchers.Default) {
            val link = uriBuilder(GestureActionType.OPEN_APP)
                .appendQueryParameter("pkg", model.pkgName)
                .appendQueryParameter("freeform", freeform.toString())
                .apply {
                    triggerSide?.let { appendQueryParameter("triggerSide", it) }
                }
                .build()
            ActionProxy(this@QuickStartActivity, link.toString()).execute(side = triggerSide)
            finishWithAnimation()
        }
    }

    private fun finishWithAnimation() = lifecycleScope.launch(Dispatchers.Main) {
        if (binding.root.isVisible) {
            hideWithAnimation { finish() }
        } else {
            finish()
        }
    }

    override fun finish() {
        super.finish()
        inputKeys = binding.keyboard.getInputKeys()
    }

    private fun saveLayoutState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val keys = getOrientationSpecificKeys()
            val (actualX, actualY) = getActualViewPosition()

            val shadowDx =
                (binding.quickStart.cardElevation + binding.quickStart.contentPaddingLeft).toInt()
            val shadowDy =
                (binding.quickStart.cardElevation + binding.quickStart.contentPaddingTop).toInt()

            settingsStore.apply {
                saveKey(keys.widthKey, binding.quickStart.width)
                saveKey(keys.heightKey, binding.quickStart.height)
                saveKey(keys.xKey, actualX - shadowDx)
                saveKey(keys.yKey, actualY - shadowDy)
            }
        }
    }

    private fun getActualViewPosition(): Pair<Int, Int> {
        val location = IntArray(2)
        binding.quickStart.getLocationOnScreen(location)
        return Pair(location[0], location[1])
    }

    inner class CombinedTouchListener : View.OnTouchListener {
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var initialWidth = 0
        private var initialHeight = 0
        private var initialX = 0f
        private var initialY = 0f
        private var isDragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWidth = binding.quickStart.width
                    initialHeight = binding.quickStart.height
                    initialX = binding.quickStart.x
                    initialY = binding.quickStart.y
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return true

                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (isResizeMode) {
                        updateSize(deltaX, deltaY)
                    } else {
                        updatePosition(deltaX, deltaY)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) return true
                    isDragging = false
                    saveLayoutState()
                }
            }
            return true
        }

        private fun updatePosition(deltaX: Float, deltaY: Float) {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val viewWidth = binding.quickStart.width
            val viewHeight = binding.quickStart.height

            val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
            val maxY = (screenHeight - viewHeight).coerceAtLeast(0)

            val newX = (initialX + deltaX).coerceInSafe(0f, maxX.toFloat())
            val newY = (initialY + deltaY).coerceInSafe(0f, maxY.toFloat())

            binding.quickStart.x = newX
            binding.quickStart.y = newY
        }

        private fun updateSize(deltaX: Float, deltaY: Float) {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val maxWidth = (screenWidth * MAX_SCREEN_RATIO).toInt()
            val maxHeight = (screenHeight * MAX_SCREEN_RATIO).toInt()

            val newWidth = (initialWidth + deltaX).toInt().coerceInSafe(minSize, maxWidth)
            val newHeight = (initialHeight + deltaY).toInt().coerceInSafe(minSize, maxHeight)

            binding.quickStart.layoutParams = binding.quickStart.layoutParams.apply {
                width = newWidth
                height = newHeight
            }

            // Ensure position remains within screen bounds after resizing
            updatePosition(0f, 0f)
        }
    }
}
