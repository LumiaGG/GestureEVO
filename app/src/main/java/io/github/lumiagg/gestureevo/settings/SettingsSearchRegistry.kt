package io.github.lumiagg.gestureevo.settings

import android.content.Context
import android.view.View
import io.github.lumiagg.gestureevo.R
import java.util.Locale

object SettingsSearchRegistry {
    private val entries = listOf(
        entry(
            id = "root_settings_guide",
            titleResId = R.string.guide_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = SettingsGuideActivity::class.java,
            keywords = listOf("引导", "入门", "权限", "guide", "setup", "onboarding")
        ),
        entry(
            id = "root_service",
            titleResId = R.string.settings_accessibility_off,
            pageTitleResId = R.string.settings_title,
            targetActivity = SettingsActivity::class.java,
            targetPreferenceKey = "preferenceService",
            keywords = listOf("无障碍", "accessibility", "服务", "service")
        ),
        entry(
            id = "root_gesture",
            titleResId = R.string.settings_gesture_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = FreeTriggerSettingsActivity::class.java,
            keywords = listOf("手势", "触发器", "gesture", "trigger")
        ),
        entry(
            id = "root_action_view",
            titleResId = R.string.action_view_settings_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            keywords = listOf("轮盘", "pie", "action view")
        ),
        entry(
            id = "root_appearance",
            titleResId = R.string.appearance_settings_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            keywords = listOf("外观", "主题", "颜色", "appearance")
        ),
        entry(
            id = "root_inspire",
            titleResId = R.string.inspire_settings_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            keywords = listOf("功能", "屏幕内容", "inspire")
        ),
        entry(
            id = "root_search",
            titleResId = R.string.search_engine_settings_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            keywords = listOf("搜索", "引擎", "search")
        ),
        entry(
            id = "root_freeform",
            titleResId = R.string.freeform_settings_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = FreeFormSettingsActivity::class.java,
            keywords = listOf("小窗", "freeform", "窗口")
        ),
        entry(
            id = "root_data",
            titleResId = R.string.data_backup_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = DataBackActivity::class.java,
            keywords = listOf("备份", "恢复", "数据", "backup", "restore")
        ),
        entry(
            id = "root_external_action",
            titleResId = R.string.external_action_settings_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = ExternalActionSettingsActivity::class.java,
            keywords = listOf("外部", "link", "调用", "external")
        ),
        entry(
            id = "root_background",
            titleResId = R.string.background_stability_title,
            pageTitleResId = R.string.settings_title,
            targetActivity = BackgroundStabilityActivity::class.java,
            keywords = listOf("后台", "保活", "稳定性", "battery")
        ),

        entry(
            id = "action_groups",
            titleResId = R.string.action_view_settings_groups_title,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.actionItemListTagsCard,
            keywords = listOf("轮盘组", "分组", "group")
        ),
        entry(
            id = "action_primary",
            titleResId = R.string.action_view_settings_primary_title,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.primaryPieCard,
            keywords = listOf("轮盘", "一级轮盘", "主轮盘", "二级轮盘", "子轮盘", "submenu", "sub", "main")
        ),
        entry(
            id = "action_blacklist",
            titleResId = R.string.action_view_settings_blacklist_title,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.pieRecentBlackListButton,
            keywords = listOf("黑名单", "最近应用", "blacklist", "recent")
        ),
        entry(
            id = "action_selected_hint",
            titleResId = R.string.action_view_settings_selected_hint,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.miscCard,
            keywords = listOf("提示图标", "选中图标", "hint")
        ),
        entry(
            id = "action_show_anim",
            titleResId = R.string.action_view_settings_show_anim,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.miscCard,
            keywords = listOf("动画", "出现动画", "animation")
        ),
        entry(
            id = "action_show_vibrate",
            titleResId = R.string.action_view_settings_show_vibrate,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.miscCard,
            keywords = listOf("震动", "振动", "vibrate")
        ),
        entry(
            id = "action_disable_all_vibrate",
            titleResId = R.string.action_view_settings_disable_all_wheel_vibrate,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.miscCard,
            keywords = listOf("禁用震动", "全部震动", "disable vibrate")
        ),
        entry(
            id = "action_external_call",
            titleResId = R.string.external_call_title,
            pageTitleResId = R.string.action_view_settings_title,
            targetActivity = ActionViewSettingsActivity::class.java,
            targetViewId = R.id.externalCallCard,
            keywords = listOf("外部调用", "link", "external call")
        ),

        entry(
            id = "appearance_language",
            titleResId = R.string.appearance_settings_language_title,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.languageCard,
            keywords = listOf("语言", "language")
        ),
        entry(
            id = "appearance_color_scheme",
            titleResId = R.string.appearance_settings_color_scheme_hint,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.globalAppearanceCard,
            keywords = listOf("配色", "色彩方案", "主题色", "dynamic color")
        ),
        entry(
            id = "appearance_icon_pack",
            titleResId = R.string.appearance_settings_icon_pack_hint,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.globalAppearanceCard,
            keywords = listOf("图标包", "icon pack")
        ),
        entry(
            id = "appearance_icon_override",
            titleResId = R.string.appearance_settings_icon_override_button,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.globalAppearanceCard,
            keywords = listOf("应用图标", "单独图标", "override")
        ),
        entry(
            id = "appearance_icon_corner",
            titleResId = R.string.appearance_settings_global_icon_corner,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.globalAppearanceCard,
            keywords = listOf("圆角", "corner", "图标圆角")
        ),
        entry(
            id = "appearance_pointer_color",
            titleResId = R.string.appearance_settings_pointer_color,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.pointerAppearanceCard,
            keywords = listOf("指针颜色", "pointer")
        ),
        entry(
            id = "appearance_wheel_color",
            titleResId = R.string.appearance_settings_wheel_color,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.wheelAppearanceCard,
            keywords = listOf("轮盘背景", "轮盘颜色", "wheel color")
        ),
        entry(
            id = "appearance_wheel_blur",
            titleResId = R.string.appearance_settings_wheel_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.wheelAppearanceCard,
            keywords = listOf("轮盘背景模糊", "轮盘模糊", "wheel blur")
        ),
        entry(
            id = "appearance_search_color",
            titleResId = R.string.appearance_settings_search_color,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.searchAppearanceCard,
            keywords = listOf("搜索背景", "搜索颜色", "search color")
        ),
        entry(
            id = "appearance_search_blur",
            titleResId = R.string.appearance_settings_search_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.searchAppearanceCard,
            keywords = listOf("搜索背景模糊", "搜索模糊", "search blur")
        ),
        entry(
            id = "appearance_search_card_blur",
            titleResId = R.string.appearance_settings_search_card_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.searchAppearanceCard,
            keywords = listOf("搜索卡片背景模糊", "搜索卡片模糊", "card blur")
        ),
        entry(
            id = "appearance_keyboard_blur",
            titleResId = R.string.appearance_settings_keyboard_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.keyboardAppearanceCard,
            keywords = listOf("键盘背景模糊", "键盘模糊", "keyboard blur")
        ),
        entry(
            id = "appearance_keyboard_card_blur",
            titleResId = R.string.appearance_settings_keyboard_card_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.keyboardAppearanceCard,
            keywords = listOf("键盘卡片背景模糊", "键盘卡片模糊", "keyboard card blur")
        ),
        entry(
            id = "appearance_pin_pool_blur",
            titleResId = R.string.appearance_settings_pin_pool_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.pinPoolAppearanceCard,
            keywords = listOf("Pin池背景模糊", "Pin池模糊", "pin pool blur")
        ),
        entry(
            id = "appearance_pin_pool_card_blur",
            titleResId = R.string.appearance_settings_pin_pool_card_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.pinPoolAppearanceCard,
            keywords = listOf("Pin池卡片背景模糊", "Pin池卡片模糊", "pin pool card blur")
        ),
        entry(
            id = "appearance_screen_content_blur",
            titleResId = R.string.appearance_settings_screen_content_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.screenContentAppearanceCard,
            keywords = listOf("屏幕内容背景模糊", "取词背景模糊", "screen content blur")
        ),
        entry(
            id = "appearance_screen_content_card_blur",
            titleResId = R.string.appearance_settings_screen_content_card_background_blur,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.screenContentAppearanceCard,
            keywords = listOf("屏幕内容卡片背景模糊", "取词卡片模糊", "screen content card blur")
        ),
        entry(
            id = "appearance_fluid_feedback",
            titleResId = R.string.appearance_settings_fluid_title,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.fluidFeedbackAppearanceCard,
            keywords = listOf("手势反馈", "流体", "描边", "fluid", "gesture feedback")
        ),
        entry(
            id = "appearance_fluid_color",
            titleResId = R.string.appearance_settings_fluid_color,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.fluidFeedbackAppearanceCard,
            keywords = listOf("流体颜色", "手势颜色", "fluid color")
        ),
        entry(
            id = "appearance_fluid_style",
            titleResId = R.string.appearance_settings_fluid_style,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.fluidFeedbackAppearanceCard,
            keywords = listOf("流体样式", "经典", "胶囊", "弧光", "fluid style", "capsule", "arc")
        ),
        entry(
            id = "appearance_fluid_stroke_color",
            titleResId = R.string.appearance_settings_fluid_stroke_color,
            pageTitleResId = R.string.appearance_settings_title,
            targetActivity = AppearanceSettingsActivity::class.java,
            targetViewId = R.id.fluidFeedbackAppearanceCard,
            keywords = listOf("描边颜色", "流体描边", "stroke color")
        ),

        entry(
            id = "search_row_count",
            titleResId = R.string.search_engine_settings_row_count_title,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.displaySettingsCard,
            keywords = listOf("行数", "row count")
        ),
        entry(
            id = "search_show_labels",
            titleResId = R.string.search_engine_settings_show_labels,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.displaySettingsCard,
            keywords = listOf("标签", "名称", "label")
        ),
        entry(
            id = "search_preview_mode",
            titleResId = R.string.search_engine_settings_preview_mode,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.previewSortCard,
            keywords = listOf("预览排序", "preview")
        ),
        entry(
            id = "search_bottom_layout",
            titleResId = R.string.search_engine_settings_bottom_layout,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.displaySettingsCard,
            keywords = listOf("底部输入", "bottom input")
        ),
        entry(
            id = "search_result_double_column",
            titleResId = R.string.search_engine_settings_result_double_column,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.displaySettingsCard,
            keywords = listOf("双列", "结果布局", "two column", "grid")
        ),
        entry(
            id = "search_keyboard_follow_animation",
            titleResId = R.string.search_engine_settings_keyboard_follow_animation,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.displaySettingsCard,
            keywords = listOf("键盘跟随", "键盘动画", "keyboard follow")
        ),
        entry(
            id = "search_image_badge",
            titleResId = R.string.search_engine_settings_image_badge,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.displaySettingsCard,
            keywords = listOf("图片搜索", "徽标", "badge")
        ),
        entry(
            id = "search_clipboard_content",
            titleResId = R.string.search_engine_settings_show_clipboard_content,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.functionSettingsCard,
            keywords = listOf("剪切板", "剪贴板", "clipboard")
        ),
        entry(
            id = "search_history",
            titleResId = R.string.search_engine_settings_search_history,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.functionSettingsCard,
            keywords = listOf("历史记录", "搜索历史", "history")
        ),
        entry(
            id = "search_contacts",
            titleResId = R.string.search_engine_settings_search_contacts,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.functionSettingsCard,
            keywords = listOf("联系人", "contacts")
        ),
        entry(
            id = "search_settings_shortcuts",
            titleResId = R.string.search_engine_settings_search_settings,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.functionSettingsCard,
            keywords = listOf("系统设置", "快捷方式", "settings shortcut")
        ),
        entry(
            id = "search_enter_first",
            titleResId = R.string.search_engine_settings_enter_first_result,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.functionSettingsCard,
            keywords = listOf("回车", "首条结果", "enter")
        ),
        entry(
            id = "search_keep_last_result",
            titleResId = R.string.search_engine_settings_keep_last_result,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.functionSettingsCard,
            keywords = listOf("保留", "上次搜索", "搜索结果", "搜索关键词", "last search")
        ),
        entry(
            id = "search_default_page",
            titleResId = R.string.search_engine_settings_default_search_page,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.defaultSearchPageCard,
            keywords = listOf("默认搜索页", "打开应用", "启动页面", "default search", "startup")
        ),
        entry(
            id = "search_import",
            titleResId = R.string.search_engine_settings_import,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.importExportCard,
            keywords = listOf("导入", "import")
        ),
        entry(
            id = "search_export",
            titleResId = R.string.search_engine_settings_export,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.importExportCard,
            keywords = listOf("导出", "export")
        ),
        entry(
            id = "search_hidden_results",
            titleResId = R.string.hidden_search_results_title,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.hiddenResultsCard,
            keywords = listOf("隐藏", "恢复", "搜索结果", "hidden results", "restore")
        ),
        entry(
            id = "search_manage_engines",
            titleResId = R.string.search_engine_manage_title,
            pageTitleResId = R.string.search_engine_settings_title,
            targetActivity = SearchEngineSettingsActivity::class.java,
            targetViewId = R.id.engineListCard,
            keywords = listOf("搜索引擎", "engine list", "管理")
        ),

        entry(
            id = "freeform_direction",
            titleResId = R.string.freeform_settings_direction_title,
            pageTitleResId = R.string.freeform_settings_title,
            targetActivity = FreeFormSettingsActivity::class.java,
            targetViewId = R.id.directionCard,
            keywords = listOf("方向", "横屏", "竖屏", "orientation")
        ),
        entry(
            id = "freeform_mode",
            titleResId = R.string.freeform_settings_mode_title,
            pageTitleResId = R.string.freeform_settings_title,
            targetActivity = FreeFormSettingsActivity::class.java,
            targetViewId = R.id.dropdownCard,
            keywords = listOf("模式", "freeform mode", "sunshine", "米窗", "com.sunshine.freeform")
        ),
        entry(
            id = "freeform_size",
            titleResId = R.string.freeform_settings_size_title,
            pageTitleResId = R.string.freeform_settings_title,
            targetActivity = FreeFormSettingsActivity::class.java,
            targetViewId = R.id.sizeCard,
            keywords = listOf("尺寸", "大小", "位置", "size")
        ),
        entry(
            id = "freeform_follow_trigger_side",
            titleResId = R.string.freeform_settings_follow_trigger_side,
            pageTitleResId = R.string.freeform_settings_title,
            targetActivity = FreeFormSettingsActivity::class.java,
            targetViewId = R.id.followTriggerSideRow,
            summaryResId = R.string.freeform_settings_follow_trigger_side_desc,
            keywords = listOf("触发区", "左右", "横向", "触发位置", "follow trigger", "side", "location", "position")
        ),
        entry(
            id = "external_create",
            titleResId = R.string.external_action_open_create_button,
            pageTitleResId = R.string.external_action_settings_title,
            targetActivity = ExternalActionSettingsActivity::class.java,
            targetViewId = R.id.externalActionEditorCard,
            keywords = listOf("新建", "复制", "link")
        ),
        entry(
            id = "external_open_wheel",
            titleResId = R.string.external_action_open_wheel_button,
            pageTitleResId = R.string.external_action_settings_title,
            targetActivity = ExternalActionSettingsActivity::class.java,
            targetViewId = R.id.externalActionEditorCard,
            keywords = listOf("外部调用轮盘", "wheel", "外部轮盘")
        ),
        entry(
            id = "external_history",
            titleResId = R.string.external_action_history_title,
            pageTitleResId = R.string.external_action_settings_title,
            targetActivity = ExternalActionSettingsActivity::class.java,
            targetViewId = R.id.externalActionHistoryCard,
            keywords = listOf("授权记录", "历史", "permission history")
        ),

        entry(
            id = "data_webdav",
            titleResId = R.string.data_backup_webdav_title,
            pageTitleResId = R.string.data_backup_title,
            targetActivity = DataBackActivity::class.java,
            targetViewId = R.id.webdavCard,
            keywords = listOf("webdav", "云备份", "cloud")
        ),
        entry(
            id = "data_backup",
            titleResId = R.string.data_backup_section_title,
            pageTitleResId = R.string.data_backup_title,
            targetActivity = DataBackActivity::class.java,
            targetViewId = R.id.backupCard,
            keywords = listOf("本地备份", "backup")
        ),
        entry(
            id = "data_restore",
            titleResId = R.string.data_restore_section_title,
            pageTitleResId = R.string.data_backup_title,
            targetActivity = DataBackActivity::class.java,
            targetViewId = R.id.restoreCard,
            keywords = listOf("本地恢复", "restore")
        ),
        entry(
            id = "data_stats",
            titleResId = R.string.data_stats_entry_title,
            pageTitleResId = R.string.data_backup_title,
            targetActivity = DataBackActivity::class.java,
            targetViewId = R.id.dataStatsCard,
            keywords = listOf("统计", "数据统计", "stats")
        ),
        entry(
            id = "data_logs",
            titleResId = R.string.log_manager_title,
            pageTitleResId = R.string.data_backup_title,
            targetActivity = DataBackActivity::class.java,
            targetViewId = R.id.logsCard,
            keywords = listOf("日志", "诊断", "崩溃", "log", "diagnostics", "crash")
        ),
        entry(
            id = "data_alias",
            titleResId = R.string.data_app_alias_title,
            pageTitleResId = R.string.data_backup_title,
            targetActivity = DataBackActivity::class.java,
            targetViewId = R.id.appAliasCard,
            keywords = listOf("应用别名", "alias")
        ),
        entry(
            id = "data_rebuild",
            titleResId = R.string.data_rebuild_index_title,
            pageTitleResId = R.string.data_backup_title,
            targetActivity = DataBackActivity::class.java,
            targetViewId = R.id.rebuildDBCard,
            keywords = listOf("索引", "重建", "rebuild")
        ),

        entry(
            id = "background_power",
            titleResId = R.string.background_stability_power_title,
            pageTitleResId = R.string.background_stability_title,
            targetActivity = BackgroundStabilityActivity::class.java,
            targetViewId = R.id.powerSettingsCard,
            keywords = listOf("电池优化", "电源", "battery")
        ),
        entry(
            id = "background_task",
            titleResId = R.string.background_stability_task_title,
            pageTitleResId = R.string.background_stability_title,
            targetActivity = BackgroundStabilityActivity::class.java,
            targetViewId = R.id.lockTaskCard,
            keywords = listOf("后台卡片", "隐藏后台", "task")
        ),
        entry(
            id = "background_autostart",
            titleResId = R.string.background_stability_autostart_title,
            pageTitleResId = R.string.background_stability_title,
            targetActivity = BackgroundStabilityActivity::class.java,
            targetViewId = R.id.autoStartCard,
            keywords = listOf("自启动", "auto start")
        ),
        entry(
            id = "background_adb",
            titleResId = R.string.background_stability_adb_title,
            pageTitleResId = R.string.background_stability_title,
            targetActivity = BackgroundStabilityActivity::class.java,
            targetViewId = R.id.adbCommandCard,
            keywords = listOf("adb", "secure settings", "权限")
        ),

        entry(
            id = "inspire_ocr",
            titleResId = R.string.inspire_settings_ocr_priority,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.screenContentSwitchCard,
            keywords = listOf("ocr", "取词", "识别")
        ),
        entry(
            id = "inspire_double_check",
            titleResId = R.string.inspire_settings_double_check,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.screenContentSwitchCard,
            keywords = listOf("二次确认", "确认")
        ),
        entry(
            id = "inspire_selection_vibrate",
            titleResId = R.string.inspire_settings_selection_vibrate,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.screenContentSwitchCard,
            keywords = listOf("震动", "振动", "框选", "进入框选", "vibrate")
        ),
        entry(
            id = "inspire_delay_remove",
            titleResId = R.string.inspire_settings_delay_remove_image,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.screenContentSwitchCard,
            keywords = listOf("延迟删除", "移除图片")
        ),
        entry(
            id = "inspire_show_engine",
            titleResId = R.string.inspire_settings_show_engine,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.screenContentSwitchCard,
            keywords = listOf("显示引擎", "搜索引擎")
        ),
        entry(
            id = "inspire_strong_copy",
            titleResId = R.string.inspire_settings_strong_copy,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.screenContentSwitchCard,
            keywords = listOf("强复制", "自动复制", "剪切板", "clipboard", "copy")
        ),
        entry(
            id = "inspire_share_behavior",
            titleResId = R.string.inspire_settings_default_share_behavior_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.defaultShareBehaviorCard,
            keywords = listOf("默认分享行为", "share behavior")
        ),
        entry(
            id = "inspire_translation_app",
            titleResId = R.string.inspire_settings_translation_app_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.appSelectionCard,
            keywords = listOf("翻译应用", "translation")
        ),
        entry(
            id = "inspire_search_app",
            titleResId = R.string.inspire_settings_search_app_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.appSelectionCard,
            keywords = listOf("搜索方式", "搜索应用", "search method")
        ),
        entry(
            id = "inspire_image_app",
            titleResId = R.string.inspire_settings_image_app_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.appSelectionCard,
            keywords = listOf("图片应用", "图像编辑")
        ),
        entry(
            id = "inspire_share_image_app",
            titleResId = R.string.inspire_settings_default_share_image_app_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.appSelectionCard,
            keywords = listOf("默认分享图片", "share image")
        ),
        entry(
            id = "inspire_link_open",
            titleResId = R.string.inspire_settings_default_link_open_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.appSelectionCard,
            keywords = listOf("默认链接打开", "link open", "浏览器")
        ),
        entry(
            id = "inspire_pointer_trail",
            titleResId = R.string.inspire_settings_show_pointer_trail,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.PointerCard,
            keywords = listOf("指针轨迹", "pointer trail")
        ),
        entry(
            id = "inspire_pointer_touch_area",
            titleResId = R.string.inspire_settings_always_show_pointer_touch_area,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.PointerCard,
            keywords = listOf("触摸区域", "指针触摸区域", "touch area", "pointer touch")
        ),
        entry(
            id = "inspire_pointer_touch_area_offset",
            titleResId = R.string.inspire_settings_pointer_touch_area_offset,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.pointer_touch_area_offset_row,
            summaryResId = R.string.inspire_settings_pointer_touch_area_offset_desc,
            keywords = listOf("触摸区域偏移", "指针偏移", "上下偏移", "touch area offset")
        ),
        entry(
            id = "inspire_pointer_drag",
            titleResId = R.string.inspire_settings_pointer_drag_mode,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.PointerCard,
            keywords = listOf("拖拽模式", "pointer drag")
        ),
        entry(
            id = "inspire_pin_pool_mode",
            titleResId = R.string.inspire_settings_pin_pool_mode_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.pin_pool_mode_row,
            summaryResId = R.string.inspire_settings_pin_pool_mode_desc,
            keywords = listOf("Pin池", "全屏", "左侧边栏", "右侧边栏", "pin pool", "fullscreen", "sidebar")
        ),
        entry(
            id = "inspire_pin_pool_local_rule_extraction",
            titleResId = R.string.inspire_settings_pin_pool_local_rule_extraction_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.pin_pool_local_rule_extraction_row,
            summaryResId = R.string.inspire_settings_pin_pool_local_rule_extraction_desc,
            keywords = listOf("Pin池", "时间猜测", "通知内容", "本地规则", "OCR", "取件码", "取餐码", "pin pool", "local rule")
        ),
        entry(
            id = "inspire_pin_pool_save_screen_content",
            titleResId = R.string.inspire_settings_pin_pool_save_screen_content_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.pin_pool_save_screen_content_row,
            summaryResId = R.string.inspire_settings_pin_pool_save_screen_content_desc,
            keywords = listOf("Pin池", "屏幕内容", "自动保存", "保存图片", "复制图片", "分类", "pin pool", "screen content", "auto-save")
        ),
        entry(
            id = "inspire_pin_pool_auto_copy_clipboard",
            titleResId = R.string.inspire_settings_pin_pool_auto_copy_clipboard_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.pin_pool_auto_copy_clipboard_row,
            summaryResId = R.string.inspire_settings_pin_pool_auto_copy_clipboard_desc,
            keywords = listOf("Pin池", "剪贴板", "复制", "自动写入", "粘贴", "pin pool", "clipboard", "auto copy")
        ),
        entry(
            id = "inspire_pin_pool_auto_delete",
            titleResId = R.string.inspire_settings_pin_pool_auto_delete_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.pin_pool_auto_delete_row,
            summaryResId = R.string.inspire_settings_pin_pool_auto_delete_desc,
            keywords = listOf("定期删除", "自动删除", "过期", "auto delete", "pin pool")
        ),
        entry(
            id = "inspire_notification_icon",
            titleResId = R.string.inspire_settings_notification_icon,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.notificationCard,
            keywords = listOf("通知图标", "notification icon")
        ),
        entry(
            id = "inspire_notification_whitelist",
            titleResId = R.string.inspire_settings_notification_whitelist,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.notificationCard,
            keywords = listOf("通知白名单", "whitelist")
        ),
        entry(
            id = "inspire_notification_blacklist",
            titleResId = R.string.inspire_settings_notification_blacklist,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.notificationCard,
            keywords = listOf("通知黑名单", "blacklist")
        ),
        entry(
            id = "inspire_notification_display_time",
            titleResId = R.string.inspire_settings_notification_display_time,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.notification_display_time_row,
            summaryResId = R.string.inspire_settings_notification_display_time_summary,
            keywords = listOf("图标显示时间", "通知显示时间", "自动消失", "常驻")
        ),
        entry(
            id = "inspire_notification_permission",
            titleResId = R.string.inspire_settings_notification_permission,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.notificationCard,
            keywords = listOf("通知权限", "permission")
        ),
        entry(
            id = "inspire_keyboard",
            titleResId = R.string.inspire_settings_keyboard_type_title,
            pageTitleResId = R.string.inspire_settings_title,
            targetActivity = InspireSettingsActivity::class.java,
            targetViewId = R.id.keyboardTypeCard,
            keywords = listOf("键盘", "keyboard")
        )
    )

    fun search(context: Context, query: String): List<SettingsSearchEntry> {
        if (query.isBlank()) return entries

        val normalizedQuery = normalize(query)
        return entries.mapNotNull { entry ->
            val score = score(context, entry, normalizedQuery)
            if (score > 0) entry to score else null
        }.sortedWith(
            compareByDescending<Pair<SettingsSearchEntry, Int>> { it.second }
                .thenBy { context.getString(it.first.titleResId) }
        ).map { it.first }
    }

    private fun score(context: Context, entry: SettingsSearchEntry, normalizedQuery: String): Int {
        val title = normalize(context.getString(entry.titleResId))
        val page = normalize(context.getString(entry.pageTitleResId))
        val summary = normalize(entry.summaryResId?.let(context::getString).orEmpty())
        val keywords = entry.keywords.map(::normalize)

        var score = 0
        if (title.contains(normalizedQuery)) score += 100
        if (summary.contains(normalizedQuery)) score += 70
        if (page.contains(normalizedQuery)) score += 35
        keywords.forEachIndexed { index, keyword ->
            if (keyword.contains(normalizedQuery)) {
                score += 55 - index.coerceAtMost(20)
            }
        }
        return score
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\n\\r\\t·•()（）:：,，/\\\\_\\-]+"), "")
    }

    private fun entry(
        id: String,
        titleResId: Int,
        pageTitleResId: Int,
        targetActivity: Class<out android.app.Activity>,
        targetViewId: Int = View.NO_ID,
        targetPreferenceKey: String? = null,
        summaryResId: Int? = null,
        keywords: List<String> = emptyList()
    ): SettingsSearchEntry {
        return SettingsSearchEntry(
            id = id,
            titleResId = titleResId,
            pageTitleResId = pageTitleResId,
            targetActivity = targetActivity,
            targetViewId = targetViewId,
            targetPreferenceKey = targetPreferenceKey,
            summaryResId = summaryResId,
            keywords = keywords
        )
    }
}
