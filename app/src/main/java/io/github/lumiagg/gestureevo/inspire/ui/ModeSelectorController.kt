package io.github.lumiagg.gestureevo.inspire.ui

import android.widget.TextView
import io.github.lumiagg.gestureevo.databinding.InspireContentViewBinding
import io.github.lumiagg.gestureevo.inspire.InspireTokenContentMode

internal class ModeSelectorController(
    private val binding: InspireContentViewBinding,
    private val canUseMode: (InspireTokenContentMode) -> Boolean,
    private val currentModeProvider: () -> InspireTokenContentMode,
) {
    fun update() {
        val hasOcr = canUseMode(InspireTokenContentMode.OCR)
        val hasAccessibility = canUseMode(InspireTokenContentMode.ACCESSIBILITY)
        binding.btnModeOcr.post {
            binding.btnModeOcr.isEnabled = hasOcr
            binding.btnModeAccessibility.isEnabled = hasAccessibility
            applyButtonState(
                binding.btnModeOcr,
                isSelected = currentModeProvider().isOCR,
                isEnabled = hasOcr,
            )
            applyButtonState(
                binding.btnModeAccessibility,
                isSelected = !currentModeProvider().isOCR,
                isEnabled = hasAccessibility,
            )
        }
    }

    private fun applyButtonState(view: TextView, isSelected: Boolean, isEnabled: Boolean) {
        view.isSelected = isSelected
        view.isEnabled = isEnabled
        view.alpha = if (isEnabled) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val DISABLED_ALPHA = 0.45f
    }
}
