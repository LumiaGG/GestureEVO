package io.github.lumiagg.gestureevo.settings.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

private const val RELEASES_URL = "https://github.com/LumiaGG/GestureEVO/releases"
private const val ISSUES_URL = "https://github.com/LumiaGG/GestureEVO/issues"
private const val PRIVACY_POLICY_URL = "https://github.com/LumiaGG/GestureEVO/blob/main/PRIVACY_POLICY.md"
private const val USER_AGREEMENT_URL = "https://github.com/LumiaGG/GestureEVO/blob/main/USER_AGREEMENT.md"
private const val PROJECT_LICENSE_ASSET = "licenses/project_license.txt"
private const val OPEN_SOURCE_LICENSES_ASSET = "licenses/open_source_notices.txt"

fun showAboutBottomSheet(activity: AppCompatActivity) {
    val bottomSheetDialog =
        BottomSheetDialog(activity, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    bottomSheetDialog.setContentView(R.layout.about_bottomsheet)

    val currentVersion =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""

    fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(intent)
        } else {
            Toast.makeText(activity, activity.getString(R.string.about_link_no_app), Toast.LENGTH_SHORT)
                .show()
        }
    }

    bottomSheetDialog.findViewById<TextView>(R.id.version_name)?.apply {
        text = activity.getString(R.string.about_version_label, currentVersion)
        isClickable = true
        isFocusable = true
        setOnClickListener { openLink(RELEASES_URL) }
    }

    bottomSheetDialog.findViewById<TextView>(R.id.project_feedback_entry)?.apply {
        setOnClickListener { openLink(ISSUES_URL) }
    }

    bottomSheetDialog.findViewById<View>(R.id.privacy_policy_entry)?.setOnClickListener {
        openLink(PRIVACY_POLICY_URL)
    }

    bottomSheetDialog.findViewById<View>(R.id.user_agreement_entry)?.setOnClickListener {
        openLink(USER_AGREEMENT_URL)
    }

    bottomSheetDialog.findViewById<View>(R.id.open_source_licenses_entry)?.setOnClickListener {
        showOpenSourceLicenses(activity)
    }

    bottomSheetDialog.setOnShowListener { dialog ->
        val bottomSheet = (dialog as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(dialog)
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.apply {
                sheet.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = true
                skipCollapsed = true
                isFitToContents = true
                isHideable = true
            }
        }
    }

    bottomSheetDialog.show()
}

private fun showOpenSourceLicenses(activity: AppCompatActivity) {
    val notice = runCatching {
        listOf(PROJECT_LICENSE_ASSET, OPEN_SOURCE_LICENSES_ASSET).joinToString("\n\n") { asset ->
            activity.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }.getOrElse {
        Toast.makeText(
            activity,
            activity.getString(R.string.about_open_source_licenses_load_failed),
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    val dialog = BottomSheetDialog(activity, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    dialog.setContentView(R.layout.open_source_licenses_bottomsheet)
    dialog.findViewById<TextView>(R.id.open_source_licenses_notice)?.text = notice

    dialog.setOnShowListener { shownDialog ->
        val bottomSheet = (shownDialog as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(shownDialog)
            sheet.layoutParams.height = (activity.window.decorView.height * 0.85f).toInt()
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = true
                skipCollapsed = true
                isFitToContents = true
                isHideable = true
            }
        }
    }
    dialog.show()
}
