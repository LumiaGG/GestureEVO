package io.github.lumiagg.gestureevo.link.radar

import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.icon.setAppIcon
import io.github.lumiagg.gestureevo.R
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.databinding.ActivityLinkSelectionBinding
import io.github.lumiagg.gestureevo.databinding.ItemLinkSelectionBinding
import io.github.lumiagg.gestureevo.link.previewLinkTarget
import io.github.lumiagg.gestureevo.link.LinkLauncher
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 多链接选择页（LinkGo LinkSelectionActivity 的 View/XML 移植版）：
 *
 * 雷达一次命中多条链接时弹出，逐条列出命中的 URL 与预测的打开目标；
 * 单击条目直接打开；勾选后可批量「打开选中」；长按复制链接。
 */
class LinkSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkSelectionBinding
    private lateinit var adapter: LinkSelectionAdapter

    private var urls: List<String> = emptyList()
    private val selected = mutableSetOf<String>()
    private var sourcePackage = ""
    private var sourcePackages: List<String> = emptyList()
    private var sourceTexts: List<String> = emptyList()
    private var triggerSide: String? = null

    /** url → (预测目标名, 目标图标) */
    private val predictions = mutableMapOf<String, Pair<String, Drawable?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: emptyList()
        sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE) ?: ""
        sourcePackages = intent.getStringArrayListExtra(EXTRA_SOURCE_PACKAGES).orEmpty()
        sourceTexts = intent.getStringArrayListExtra(EXTRA_SOURCE_TEXTS).orEmpty()
        triggerSide = intent.getStringExtra(TriggerSide.EXTRA_TRIGGER_SIDE)
        if (urls.isEmpty()) {
            finish()
            return
        }

        adapter = LinkSelectionAdapter(
            onOpen = { url ->
                openUrls(listOf(url))
            },
            onToggle = { url ->
                if (selected.contains(url)) selected.remove(url) else selected.add(url)
                adapter.notifyDataSetChanged()
                updateConfirmButton()
                updateSelectAllLabel()
            },
            onCopy = { url ->
                ClipboardUtils.copyText(this, url)
                ToastHelper.showInUIThread(this, R.string.link_radar_selection_copied)
            },
        )
        binding.linkList.layoutManager = LinearLayoutManager(this)
        binding.linkList.adapter = adapter

        binding.countBadge.text = getString(R.string.link_radar_selection_count, urls.size)
        binding.selectAllText.setOnClickListener {
            if (selected.size == urls.size) selected.clear() else selected.addAll(urls)
            adapter.notifyDataSetChanged()
            updateConfirmButton()
            updateSelectAllLabel()
        }
        binding.confirmButton.setOnClickListener {
            openUrls(if (selected.isNotEmpty()) selected.toList() else urls)
        }
        binding.scrim.setOnClickListener { finish() }

        updateConfirmButton()
        updateSelectAllLabel()
        applyGlassStyle()
        loadPredictions()
    }

    /** 与取词/识别面板同源的玻璃样式：窗口背景模糊 + 卡片材质（跟随外观设置）。 */
    private fun applyGlassStyle() {
        lifecycleScope.launch {
            val backgroundBlurEnabled = settingsStore.readKey(
                AppearanceSettingsActivity.KEY_SCREEN_CONTENT_BACKGROUND_BLUR, false
            )
            val cardBackgroundBlurEnabled = settingsStore.readKey(
                AppearanceSettingsActivity.KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR, false
            )
            GlassEffectUtils.applyWindowBlurProgress(
                window, if (backgroundBlurEnabled) 1f else 0f
            )
            GlassEffectUtils.applyCardStyle(
                card = binding.sheetCard,
                color = DynamicColorUtils.surfaceContainerColor,
                glassEnabled = cardBackgroundBlurEnabled,
                backgroundBlurEnabled = backgroundBlurEnabled,
                blurRegion = GlassEffectUtils.usesCardRegionBlur(
                    backgroundBlurEnabled, cardBackgroundBlurEnabled
                ),
            )
            binding.sheetCard.strokeWidth = 0
            // scrim 提供对比度：背景模糊开启时减淡
            val scrimAlpha = if (backgroundBlurEnabled) 0x66000000 else 0x99000000
            binding.scrim.setBackgroundColor(scrimAlpha.toInt())
        }
    }

    private fun updateSelectAllLabel() {
        binding.selectAllText.setText(
            if (selected.size == urls.size) {
                R.string.link_radar_selection_deselect_all
            } else {
                R.string.link_radar_selection_select_all
            }
        )
    }

    /** Predict locally without resolving short links until the user chooses to open one. */
    private fun loadPredictions() {
        lifecycleScope.launch {
            for ((index, url) in urls.withIndex()) {
                val source = sourcePackages.getOrNull(index) ?: sourcePackage
                val target = try {
                    previewLinkTarget(this@LinkSelectionActivity, url, source, sourceTexts.getOrNull(index))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                predictions[url] = (target?.label ?: getString(R.string.link_radar_choose_app)) to target?.icon
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateConfirmButton() {
        binding.confirmButton.text = if (selected.isNotEmpty()) {
            getString(R.string.link_radar_selection_open_selected, selected.size)
        } else {
            getString(R.string.link_radar_selection_open_all)
        }
    }

    /** 打开（LinkGo 同款：逐条分发，间隔 100ms 防止目标应用打架）。 */
    private fun openUrls(list: List<String>) {
        lifecycleScope.launch {
            list.forEach { url ->
                val index = urls.indexOf(url)
                LinkLauncher.openUrl(this@LinkSelectionActivity, url,
                    sourcePackages.getOrNull(index) ?: sourcePackage, sourceTexts.getOrNull(index), side = triggerSide)
                delay(100)
            }
            finish()
        }
    }

    private inner class LinkSelectionAdapter(
        private val onOpen: (String) -> Unit,
        private val onToggle: (String) -> Unit,
        private val onCopy: (String) -> Unit,
    ) : RecyclerView.Adapter<LinkSelectionAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemLinkSelectionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(itemBinding)
        }

        override fun getItemCount(): Int = urls.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val url = urls[position]
            val prediction = predictions[url]
            holder.binding.urlText.text = url

            val icon = prediction?.second
            holder.binding.targetIcon.setAppIcon(icon, R.drawable.ic_baseline_insert_link_24)

            holder.binding.targetLabel.text = prediction?.first
                ?: getString(R.string.link_radar_open_link)

            holder.binding.radio.isChecked = selected.contains(url)
            holder.binding.itemCard.strokeWidth =
                if (selected.contains(url)) (1.5f * resources.displayMetrics.density).toInt() else 0
            holder.binding.itemCard.setCardBackgroundColor(
                MaterialColors.getColor(
                    holder.binding.itemCard,
                    if (selected.contains(url)) {
                        com.google.android.material.R.attr.colorSecondaryContainer
                    } else {
                        com.google.android.material.R.attr.colorSurfaceVariant
                    }
                )
            )

            holder.binding.itemCard.setOnClickListener { onOpen(url) }
            holder.binding.itemCard.setOnLongClickListener {
                onCopy(url)
                true
            }
            holder.binding.radio.setOnClickListener { onToggle(url) }
        }

        inner class Holder(val binding: ItemLinkSelectionBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val EXTRA_URLS = "URLS"
        const val EXTRA_SOURCE_PACKAGE = "SOURCE_PACKAGE"
        const val EXTRA_SOURCE_PACKAGES = "SOURCE_PACKAGES"
        const val EXTRA_SOURCE_TEXTS = "SOURCE_TEXTS"
    }
}
