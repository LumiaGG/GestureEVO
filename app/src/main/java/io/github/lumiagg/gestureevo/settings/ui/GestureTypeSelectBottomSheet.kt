package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import com.drake.brv.utils.grid
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.ChipGroup
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Enhanced model to include phase information
data class GestureTypeHeader(val title: String)

data class GestureTypeModel(
    val gestureType: GestureType,
    val displayName: String,
    val description: String = "",
    val phaseActions: Map<GesturePhase, String> = emptyMap()
)

// Updated callback to include phase information
interface GestureTypeSelectCallback {
    fun onGestureEventSelected(gestureTypeEvent: GestureTypeEvent)

    fun onBatchGestureEventsSelected(events: List<GestureTypeEvent>) {}
}

class GestureTypeSelectBottomSheet(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope,
    private val triggerConfig: FreeTriggerConfig,
    private val callback: GestureTypeSelectCallback
) {
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var recyclerView: RecyclerView
    private var bindingAdapter: BindingAdapter? = null
    private var isBatchMode: Boolean = false
    private val selectedGestureTypes = mutableSetOf<GestureType>()
    private val selectedPhases = mutableSetOf(GesturePhase.TD)
    private var gestureModels: List<GestureTypeModel> = emptyList()
    private var batchSelectedCountView: TextView? = null
    private var batchApplyButton: MaterialButton? = null
    private var batchModeButton: MaterialButton? = null
    private var titleNoteView: TextView? = null

    // Helper functions to get action names
    private fun getActionKey(
        gestureType: GestureType,
        phase: GesturePhase,
    ): String {
        return "trigger_${gestureType.name}_${phase.name}_action"
    }

    private fun getActionNameFromLink(actionLink: String): String {
        return if (actionLink.isNotEmpty()) {
            GestureActionLink().apply {
                parseLink(actionLink)
            }.actionType.showName(activity)
        } else {
            GestureActionType.SHOW_ACTION_VIEW.showName(activity)
        }
    }

    fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        setupRecyclerView()
        showDialog()
    }

    private fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(R.layout.bottom_sheet_select_gesture_type)
        bottomSheetDialog.setCanceledOnTouchOutside(false)

        setupBatchControls()

        // Update title to show which corner we're configuring
        bottomSheetDialog.findViewById<TextView>(R.id.title_text)?.apply {
            val cornerName = triggerConfig.name
            text = activity.getString(R.string.gesture_type_select_for_corner, cornerName)
        }
    }

    private fun setupBottomSheetBehavior() {
        bottomSheetDialog.setOnShowListener { dialog ->
            val bottomSheet = (dialog as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

            bottomSheet?.let { sheet ->
                DynamicColorUtils.applyBottomSheetSurface(dialog)
                val windowHeight = activity.window.decorView.height
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.apply {
                    sheet.layoutParams.height = windowHeight
                    state = BottomSheetBehavior.STATE_EXPANDED
                    isDraggable = true
                    skipCollapsed = true
                    isFitToContents = true
                    isHideable = true
                }
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView = bottomSheetDialog.findViewById(R.id.gestureTypeList)!!
        recyclerView.grid(spanCount = 1)

        bindingAdapter = recyclerView.setup {
            addType<GestureTypeHeader>(R.layout.gesture_type_list_header)
            addType<GestureTypeModel>(R.layout.gesture_type_list_item)

            onBind {
                when (val model = getModel<Any>()) {
                    is GestureTypeHeader -> {
                        findView<TextView>(R.id.gesture_group_title).text = model.title
                    }

                    is GestureTypeModel -> bindGestureItem(model)
                }
            }
        }

        // Populate RecyclerView with gesture types
        coroutineScope.launch(Dispatchers.Default) {
            val gestureList = getAvailableGestureTypes()
            withContext(Dispatchers.Main) {
                gestureModels = gestureList.filterIsInstance<GestureTypeModel>()
                recyclerView.models = gestureList
                updateBatchSelectedCount()
            }
        }
    }

    private fun BindingAdapter.BindingViewHolder.bindGestureItem(model: GestureTypeModel) {
        val itemView = findView<View>(R.id.gesture_item)
        findView<TextView>(R.id.gesture_name).text = model.displayName

        val checkbox = findView<MaterialCheckBox>(R.id.gesture_select_checkbox)
        val phaseContainer = findView<View>(R.id.phase_buttons_container)
        val lockHint = findView<TextView>(R.id.phase_lock_hint)

        checkbox.visibility = if (isBatchMode) View.VISIBLE else View.GONE
        phaseContainer.visibility = if (isBatchMode) View.GONE else View.VISIBLE
        itemView.isEnabled = true
        checkbox.isEnabled = true

        if (isBatchMode) {
            lockHint.visibility = View.GONE
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = selectedGestureTypes.contains(model.gestureType)
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                updateGestureSelection(model.gestureType, isChecked)
            }
            itemView.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
            return
        }

        itemView.setOnClickListener(null)
        checkbox.setOnCheckedChangeListener(null)

        val rowTd = findView<SettingsNavRow>(R.id.btn_phase_td)
        val rowSp = findView<SettingsNavRow>(R.id.btn_phase_sp)
        val rowRd = findView<SettingsNavRow>(R.id.btn_phase_rd)
        val rowBack = findView<SettingsNavRow>(R.id.btn_phase_back)

        bindPhaseRow(rowTd, R.id.btn_phase_td_value, model, GesturePhase.TD, enabled = true)
        val allowFollowUp =
            model.phaseActions[GesturePhase.TD] == GestureActionType.NONE.showName(activity)
        bindPhaseRow(rowSp, R.id.btn_phase_sp_value, model, GesturePhase.SP, allowFollowUp)
        bindPhaseRow(rowRd, R.id.btn_phase_rd_value, model, GesturePhase.RD, allowFollowUp)
        bindPhaseRow(rowBack, R.id.btn_phase_back_value, model, GesturePhase.BACK, allowFollowUp)

        when (model.gestureType) {
            GestureType.SINGLE_CLICK, GestureType.DOUBLE_CLICK -> {
                rowSp.visibility = View.GONE
                rowRd.visibility = View.GONE
                rowBack.visibility = View.GONE
                lockHint.visibility = View.GONE
            }

            GestureType.LONG_PRESS -> {
                rowSp.visibility = View.GONE
                rowBack.visibility = View.GONE
                rowRd.visibility = View.VISIBLE
                lockHint.visibility = if (allowFollowUp) View.GONE else View.VISIBLE
            }

            else -> {
                rowSp.visibility = View.VISIBLE
                rowRd.visibility = View.VISIBLE
                rowBack.visibility = View.VISIBLE
                lockHint.visibility = if (allowFollowUp) View.GONE else View.VISIBLE
            }
        }
    }

    private fun BindingAdapter.BindingViewHolder.bindPhaseRow(
        row: SettingsNavRow,
        valueId: Int,
        model: GestureTypeModel,
        phase: GesturePhase,
        enabled: Boolean,
    ) {
        val actionName =
            model.phaseActions[phase] ?: activity.getString(R.string.gesture_phase_unset)
        findView<TextView>(valueId).text = actionName
        row.isEnabled = enabled
        row.isClickable = enabled
        row.isFocusable = enabled
        row.alpha = if (enabled) 1f else 0.45f
        row.setOnClickListener(
            if (enabled) {
                View.OnClickListener {
                    callback.onGestureEventSelected(GestureTypeEvent(model.gestureType, phase))
                    bottomSheetDialog.dismiss()
                }
            } else {
                null
            }
        )
    }

    private suspend fun getAvailableGestureTypes(): List<Any> {
        val tap = listOf(
            createGestureModel(
                GestureType.SINGLE_CLICK,
                activity.getString(R.string.gesture_type_single_click)
            ),
            createGestureModel(
                GestureType.DOUBLE_CLICK,
                activity.getString(R.string.gesture_type_double_click)
            ),
            createGestureModel(
                GestureType.LONG_PRESS,
                activity.getString(R.string.gesture_type_long_press)
            ),
        )
        val swipe = listOf(
            createGestureModel(
                GestureType.SWIPE_LEFT,
                activity.getString(R.string.gesture_type_swipe_left)
            ),
            createGestureModel(
                GestureType.SWIPE_RIGHT,
                activity.getString(R.string.gesture_type_swipe_right)
            ),
            createGestureModel(
                GestureType.SWIPE_UP,
                activity.getString(R.string.gesture_type_swipe_up)
            ),
            createGestureModel(
                GestureType.SWIPE_DOWN,
                activity.getString(R.string.gesture_type_swipe_down)
            ),
            createGestureModel(
                GestureType.SWIPE_OBLIQUE_UP,
                activity.getString(R.string.gesture_type_swipe_oblique_up)
            ),
            createGestureModel(
                GestureType.SWIPE_OBLIQUE_DOWN,
                activity.getString(R.string.gesture_type_swipe_oblique_down)
            ),
        )
        return buildList {
            add(GestureTypeHeader(activity.getString(R.string.gesture_type_group_tap)))
            addAll(tap)
            add(GestureTypeHeader(activity.getString(R.string.gesture_type_group_swipe)))
            addAll(swipe)
        }
    }

    private suspend fun createGestureModel(
        gestureType: GestureType,
        displayName: String
    ): GestureTypeModel {
        // Build a map of phase actions
        val phaseActions = mutableMapOf<GesturePhase, String>()

        // Get action names for each phase
        for (phase in GesturePhase.entries) {
            val key = getActionKey(gestureType, phase)
            val actionLink = triggerConfig.getGestureAction(key)?.firstStageLink ?: ""
            val actionName = getActionNameFromLink(actionLink)
            phaseActions[phase] = actionName
        }

        return GestureTypeModel(
            gestureType = gestureType,
            displayName = displayName,
            description = activity.getString(R.string.gesture_type_select_description),
            phaseActions = phaseActions
        )
    }

    private fun showDialog() {
        bottomSheetDialog.show()
        bottomSheetDialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun setupBatchControls() {
        val batchSelectAll = bottomSheetDialog.findViewById<MaterialButton>(R.id.batch_select_all)
        val batchClear = bottomSheetDialog.findViewById<MaterialButton>(R.id.batch_clear)
        val phaseChipGroup = bottomSheetDialog.findViewById<ChipGroup>(R.id.phaseChipGroup)
        batchSelectedCountView = bottomSheetDialog.findViewById(R.id.batch_selected_count)
        batchApplyButton = bottomSheetDialog.findViewById(R.id.batch_apply)
        batchModeButton = bottomSheetDialog.findViewById(R.id.mode_batch)
        titleNoteView = bottomSheetDialog.findViewById(R.id.title_note)

        batchModeButton?.setOnClickListener {
            setBatchMode(!isBatchMode)
        }

        phaseChipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedPhases.clear()
            if (checkedIds.contains(R.id.chip_phase_td)) selectedPhases.add(GesturePhase.TD)
            if (checkedIds.contains(R.id.chip_phase_sp)) selectedPhases.add(GesturePhase.SP)
            if (checkedIds.contains(R.id.chip_phase_rd)) selectedPhases.add(GesturePhase.RD)
            if (checkedIds.contains(R.id.chip_phase_back)) selectedPhases.add(GesturePhase.BACK)
            updateBatchSelectedCount()
        }

        phaseChipGroup?.check(R.id.chip_phase_td)

        batchSelectAll?.setOnClickListener {
            selectedGestureTypes.clear()
            gestureModels.forEach { model ->
                selectedGestureTypes.add(model.gestureType)
            }
            bindingAdapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
            updateBatchSelectedCount()
        }

        batchClear?.setOnClickListener {
            selectedGestureTypes.clear()
            bindingAdapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
            updateBatchSelectedCount()
        }

        batchApplyButton?.setOnClickListener {
            val events = buildBatchEvents()
            if (events.isEmpty()) {
                return@setOnClickListener
            }
            callback.onBatchGestureEventsSelected(events)
            bottomSheetDialog.dismiss()
        }
    }

    private fun setBatchMode(enabled: Boolean) {
        isBatchMode = enabled
        bottomSheetDialog.findViewById<View>(R.id.batch_controls)?.visibility =
            if (enabled) View.VISIBLE else View.GONE
        titleNoteView?.visibility = if (enabled) View.GONE else View.VISIBLE
        batchModeButton?.setText(
            if (enabled) R.string.gesture_type_batch_done else R.string.gesture_type_select_mode_batch
        )
        if (!enabled) {
            selectedGestureTypes.clear()
        }
        bindingAdapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
        updateBatchSelectedCount()
    }

    private fun updateGestureSelection(gestureType: GestureType, isChecked: Boolean) {
        if (isChecked) {
            selectedGestureTypes.add(gestureType)
        } else {
            selectedGestureTypes.remove(gestureType)
        }
        updateBatchSelectedCount()
    }

    private fun updateBatchSelectedCount() {
        val gestureCount = selectedGestureTypes.size
        val phaseCount = selectedPhases.size
        batchSelectedCountView?.text = activity.getString(
            R.string.gesture_type_batch_selected_count,
            gestureCount,
            phaseCount
        )
        batchApplyButton?.isEnabled = gestureCount > 0 && phaseCount > 0
    }

    private fun buildBatchEvents(): List<GestureTypeEvent> {
        if (selectedGestureTypes.isEmpty() || selectedPhases.isEmpty()) return emptyList()
        val events = mutableListOf<GestureTypeEvent>()
        val phaseOrder = listOf(
            GesturePhase.TD,
            GesturePhase.SP,
            GesturePhase.RD,
            GesturePhase.BACK
        )
        val selectedTypesInUiOrder = gestureModels
            .map { it.gestureType }
            .filter { selectedGestureTypes.contains(it) }

        selectedTypesInUiOrder.forEach { type ->
            phaseOrder.filter { selectedPhases.contains(it) }.forEach { phase ->
                events.add(GestureTypeEvent(type, phase))
            }
        }
        return events
    }
}
