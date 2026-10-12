package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityFreeTriggerSettingsBinding
import io.github.lumiagg.gestureevo.databinding.FreeTriggerListItemBinding
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceFreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.FreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.launch

class FreeTriggerSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFreeTriggerSettingsBinding
    private lateinit var triggerConfigLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreeTriggerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerTriggerConfigLauncher()
        registerSelectionLauncher()
        setupToolbar()
        setupViews()
        loadTriggers()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupViews() {
        // Setup RecyclerView
        binding.triggerList.linear().setup {
            addType<FreeTriggerConfig>(R.layout.free_trigger_list_item)

            onBind {
                val binding = getBinding<FreeTriggerListItemBinding>()
                val trigger = getModel<FreeTriggerConfig>()

                binding.triggerItem.setTitle(trigger.name)
                binding.triggerItem.setSummary(getTriggerEnableSummary(trigger.enable))
                binding.enableSwitch.setOnCheckedChangeListener(null)
                binding.enableSwitch.isChecked = trigger.enable
                binding.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (trigger.enable == isChecked) return@setOnCheckedChangeListener
                    trigger.enable = isChecked
                    binding.triggerItem.setSummary(getTriggerEnableSummary(isChecked))
                    lifecycleScope.launch {
                        FreeTriggerDataManager.updateTrigger(this@FreeTriggerSettingsActivity, trigger)
                        produceFreeTriggerEvent(
                            FreeTriggerEvent(isConfig = true)
                        )
                    }
                }
            }

            onClick(R.id.editTriggerButton) {
                showTriggerConfig(getModel())
            }
        }

        // Setup add button
        binding.addTriggerFab.setOnClickListener {
            addNewTrigger()
        }

        binding.triggerList.let { recyclerView ->
            // Remove existing ItemDecoration to avoid duplicate dividers
            for (i in 0 until recyclerView.itemDecorationCount) {
                recyclerView.removeItemDecorationAt(i)
            }

            recyclerView.addItemDecoration(BottomSpaceItemDecoration(60.dp2px.toInt()))
        }
    }

    private fun getTriggerEnableSummary(enabled: Boolean): String {
        return getString(
            if (enabled) {
                R.string.free_trigger_item_enabled_summary
            } else {
                R.string.free_trigger_item_disabled_summary
            }
        )
    }

    override fun onResume() {
        super.onResume()
        loadTriggers()
        lifecycleScope.launch {
            produceFreeTriggerEvent(FreeTriggerEvent(isConfig = true))
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false))
        }
    }

    private fun loadTriggers() {
        lifecycleScope.launch {
            val triggers = FreeTriggerDataManager.readTriggers(this@FreeTriggerSettingsActivity)
            binding.triggerList.models = triggers
        }
    }

    private fun addNewTrigger() {
        val trigger = FreeTriggerConfig().apply {
            name = getString(R.string.free_trigger_new_name)
            tag = FreeTriggerDataManager.buildTag()
            // Set default values
            setState(FreeTriggerConfig.FLAG_PORTRAIT, true)
            setState(FreeTriggerConfig.FLAG_LANDSCAPE, true)
            width = 50
            height = 50
        }

        lifecycleScope.launch {
            FreeTriggerDataManager.addTrigger(this@FreeTriggerSettingsActivity, trigger)
            loadTriggers()
            showTriggerConfig(trigger)
            produceFreeTriggerEvent(
                FreeTriggerEvent(isConfig = true)
            )
        }
    }

    private fun showTriggerConfig(trigger: FreeTriggerConfig) {
        triggerConfigLauncher.launch(FreeTriggerConfigActivity.createIntent(this, trigger))
    }

    private fun registerTriggerConfigLauncher() {
        triggerConfigLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            loadTriggers()
        }
    }

    lateinit var actionSelectionLauncher: ActivityResultLauncher<Intent>
    var selectionGestureActionCallback = object : SelectionGestureActionCallback {}

    private fun registerSelectionLauncher() {
        actionSelectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                selectionGestureActionCallback.onSelected()
            }
        }
    }

    private class BottomSpaceItemDecoration(private val space: Int) :
        RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            if (parent.getChildAdapterPosition(view) == parent.adapter?.itemCount?.minus(1)) {
                outRect.bottom = space
            }
        }
    }
}

interface SelectionGestureActionCallback {
    fun onSelected() {
    }
}
