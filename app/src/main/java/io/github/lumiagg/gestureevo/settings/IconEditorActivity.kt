package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityIconEditorBinding
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.ui.applyWheelStyle
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.icon.EditableIconDrawable
import io.github.lumiagg.gestureevo.utils.icon.IconFileUtils
import io.github.lumiagg.gestureevo.utils.icon.IconImageIO
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class IconEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIconEditorBinding
    private var sourcePath: String? = null
    private var sourceUri: String? = null
    private var icon: EditableIconDrawable? = null
    private var saving = false
    private val textMode get() = intent.getStringExtra(EXTRA_TEXT_ICON) != null

    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            finish()
        } else {
            sourceUri = uri.toString()
            loadUri(uri)
        }
    }

    private fun loadUri(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val decoded = IconImageIO.read(this@IconEditorActivity, uri, 1024, 1024)
                    ?: return@withContext null
                try {
                    val file = File.createTempFile("icon-editor-", ".png", cacheDir)
                    if (IconImageIO.savePng(decoded, file.absolutePath)) {
                        sourcePath = file.absolutePath
                        decoded
                    } else {
                        decoded.recycle()
                        file.delete()
                        null
                    }
                } catch (e: IOException) {
                    Log.w("IconEditor", "Unable to cache selected image", e)
                    decoded.recycle()
                    null
                }
            }
            showSource(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIconEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        configurePreview()
        if (textMode) {
            configureTextEditor(savedInstanceState)
            return
        }
        bindSlider(binding.scaleSlider, binding.scaleValue, savedInstanceState?.getFloat(KEY_SCALE) ?: 100f)
        bindSlider(binding.roundSlider, binding.roundValue, savedInstanceState?.getFloat(KEY_ROUND) ?: 0f)
        bindSlider(binding.horizontalSlider, binding.horizontalValue, savedInstanceState?.getFloat(KEY_X) ?: 0f)
        bindSlider(binding.verticalSlider, binding.verticalValue, savedInstanceState?.getFloat(KEY_Y) ?: 0f)
        binding.resetButton.setOnClickListener {
            binding.scaleSlider.value = 100f
            binding.roundSlider.value = 0f
            binding.horizontalSlider.value = 0f
            binding.verticalSlider.value = 0f
        }
        binding.applyButton.setOnClickListener { applyIcon() }
        sourcePath = savedInstanceState?.getString(KEY_SOURCE)
        sourceUri = savedInstanceState?.getString(KEY_URI)
        val path = sourcePath
        if (path != null) {
            lifecycleScope.launch {
                showSource(withContext(Dispatchers.IO) { IconImageIO.read(path) })
            }
        } else if (sourceUri != null) {
            loadUri(Uri.parse(sourceUri))
        } else if (intent.getStringExtra(EXTRA_APP_PACKAGE) != null) {
            loadAppIcon(requireNotNull(intent.getStringExtra(EXTRA_APP_PACKAGE)))
        } else if (savedInstanceState == null) {
            gallery.launch("image/*")
        }
    }

    private fun loadAppIcon(packageName: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val drawable = IconHelper.getIcon(this@IconEditorActivity, packageName)
                    ?: return@withContext null
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
                val scale = (1024f / maxOf(width, height)).coerceAtMost(1f)
                drawable.toBitmap(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
                )
            }
            showSource(bitmap)
        }
    }

    private fun configurePreview() {
        val config = intent.getStringExtra(EXTRA_WHEEL_STYLE)?.let(ActionConfig::toModel)
        // Scale the complete view so text fitting and badge proportions match the actual icon.
        val size = config?.itemDiameter?.coerceAtLeast(1f) ?: 60f.dp2px
        binding.iconPreview.layoutParams = binding.iconPreview.layoutParams.apply {
            width = size.toInt().coerceAtLeast(1)
            height = size.toInt().coerceAtLeast(1)
        }
        binding.iconPreview.scaleX = 160f.dp2px / size
        binding.iconPreview.scaleY = 160f.dp2px / size
        if (config != null) {
            binding.iconPreview.applyWheelStyle(config)
            binding.previewHint.setText(R.string.icon_editor_wheel_preview)
        }
        if (textMode) binding.iconPreview.setText("")
        if (intent.getBooleanExtra(EXTRA_IMAGE_BADGE, false)) {
            binding.iconPreview.setBadge(R.drawable.baseline_image_24)
        }
    }

    private fun configureTextEditor(savedInstanceState: Bundle?) {
        binding.loading.visibility = View.GONE
        binding.imageControls.visibility = View.GONE
        binding.textInputLayout.visibility = View.VISIBLE
        binding.applyButton.setText(R.string.icon_editor_apply_text)
        binding.previewHint.setText(R.string.icon_editor_text_preview_hint)
        binding.textInput.doAfterTextChanged {
            val text = it?.toString().orEmpty().trim()
            binding.iconPreview.setText(text)
            binding.applyButton.isEnabled = text.isNotEmpty()
        }
        binding.textInput.setText(savedInstanceState?.getString(KEY_TEXT) ?: intent.getStringExtra(EXTRA_TEXT_ICON))
        binding.resetButton.setOnClickListener { binding.textInput.setText(intent.getStringExtra(EXTRA_TEXT_ICON)) }
        binding.applyButton.setOnClickListener {
            val text = binding.textInput.text?.toString().orEmpty().trim()
            if (text.isNotEmpty()) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT_ICON, text))
                finish()
            }
        }
    }

    private fun bindSlider(slider: Slider, label: TextView, initial: Float) {
        slider.value = initial.coerceIn(slider.valueFrom, slider.valueTo)
        label.text = getString(R.string.display_percent, slider.value.toInt())
        slider.addOnChangeListener { _, value, _ ->
            label.text = getString(R.string.display_percent, value.toInt())
            updatePreview()
        }
    }

    private fun showSource(bitmap: Bitmap?) {
        binding.loading.visibility = View.GONE
        if (bitmap == null) {
            Toast.makeText(this, R.string.icon_editor_load_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        icon = EditableIconDrawable(bitmap)
        binding.iconPreview.setImage(icon)
        binding.applyButton.isEnabled = true
        updatePreview()
    }

    private fun updatePreview() {
        icon?.apply {
            scale = binding.scaleSlider.value / 100f
            roundPercent = binding.roundSlider.value / 100f
            offsetX = binding.horizontalSlider.value / 100f
            offsetY = binding.verticalSlider.value / 100f
            invalidateSelf()
        }
    }

    private fun applyIcon() {
        val drawable = icon ?: return
        if (saving) return
        saving = true
        binding.applyButton.isEnabled = false
        binding.loading.visibility = View.VISIBLE
        // Snapshot on the UI thread, so subsequent slider changes cannot affect the export.
        val bitmap = drawable.renderBitmap()
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val files = IconFileUtils(this@IconEditorActivity).setImgBitmap(bitmap)
                    if (files.saveIcon()) files.imgPath else null
                } finally {
                    bitmap.recycle()
                }
            }
            if (path == null) {
                saving = false
                binding.loading.visibility = View.GONE
                binding.applyButton.isEnabled = true
                Toast.makeText(this@IconEditorActivity, R.string.icon_editor_save_failed, Toast.LENGTH_LONG).show()
            } else {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ICON_PATH, path))
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SOURCE, sourcePath)
        outState.putString(KEY_URI, sourceUri)
        outState.putString(KEY_TEXT, binding.textInput.text?.toString())
        outState.putFloat(KEY_SCALE, binding.scaleSlider.value)
        outState.putFloat(KEY_ROUND, binding.roundSlider.value)
        outState.putFloat(KEY_X, binding.horizontalSlider.value)
        outState.putFloat(KEY_Y, binding.verticalSlider.value)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) sourcePath?.let { File(it).delete() }
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_WHEEL_STYLE = "wheel_style"
        internal const val EXTRA_IMAGE_BADGE = "image_badge"
        internal const val EXTRA_ICON_PATH = "icon_path"
        internal const val EXTRA_APP_PACKAGE = "app_package"
        internal const val EXTRA_TEXT_ICON = "text_icon"
        private const val KEY_TEXT = "text"
        private const val KEY_SOURCE = "source"
        private const val KEY_URI = "source_uri"
        private const val KEY_SCALE = "scale"
        private const val KEY_ROUND = "round"
        private const val KEY_X = "offset_x"
        private const val KEY_Y = "offset_y"
    }
}
