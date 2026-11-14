package io.github.lumiagg.gestureevo.link.radar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.R
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.databinding.ActivityLinkSelectionBinding
import io.github.lumiagg.gestureevo.databinding.ItemLinkSelectionBinding
import io.github.lumiagg.gestureevo.link.LinkDispatcher
import io.github.lumiagg.gestureevo.link.LinkLauncher
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.delay
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

    /** url → (预测目标名, 目标图标) */
    private val predictions = mutableMapOf<String, Pair<String, Drawable?>>()

    /** 与取词面板同源的玻璃模糊设置 */
    private var backgroundBlurEnabled = false
    private var cardBackgroundBlurEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: emptyList()
        sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE) ?: ""
        if (urls.isEmpty()) {
            finish()
            return
        }

        applyGlassStyle()

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
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                Toast.makeText(this, getString(R.string.link_radar_selection_copied), Toast.LENGTH_SHORT).show()
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
            backgroundBlurEnabled = settingsStore.readKey(
                AppearanceSettingsActivity.KEY_SCREEN_CONTENT_BACKGROUND_BLUR, false
            )
            cardBackgroundBlurEnabled = settingsStore.readKey(
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

    /** 逐条预测打开目标（LinkGo predictTargetFlow 的同步简化版：本地规则匹配 + 系统默认解析）。 */
    private fun loadPredictions() {
        lifecycleScope.launch {
            val rules = runCatching { LinkRuleRepository.loadRules(this@LinkSelectionActivity) }
                .getOrDefault(emptyList())
            for (url in urls) {
                val normalized = UrlDetector.normalizeForOpening(url) ?: url
                val rule = LinkDispatcher.findRule(normalized, rules, sourcePackage)
                val targetPackage = rule?.targetPackage?.takeIf { it.isNotEmpty() }
                if (targetPackage != null) {
                    val label = runCatching {
                        val pm = packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(targetPackage, 0)).toString()
                    }.getOrDefault(targetPackage)
                    val icon = runCatching { IconHelper.getIcon(this@LinkSelectionActivity, targetPackage) }.getOrNull()
                    predictions[url] = label to icon
                } else {
                    // 无规则命中：展示系统默认解析目标
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
                    val resolveInfo = runCatching { packageManager.resolveActivity(intent, 0) }.getOrNull()
                    val pkg = resolveInfo?.activityInfo?.packageName
                    if (pkg != null && pkg != "android") {
                        val label = runCatching {
                            val pm = packageManager
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        }.getOrDefault(pkg)
                        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
                        predictions[url] = label to icon
                    } else {
                        predictions[url] = getString(R.string.link_radar_selection_system_default) to null
                    }
                }
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
                LinkLauncher.openUrl(this@LinkSelectionActivity, url, sourcePackage)
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
                ?: runCatching { packageManager.getApplicationIcon(applicationInfo) }.getOrNull()
            holder.binding.targetIcon.setImageDrawable(icon)

            holder.binding.targetLabel.text = prediction?.first
                ?: getString(R.string.link_radar_selection_system_default)

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
    }
}
