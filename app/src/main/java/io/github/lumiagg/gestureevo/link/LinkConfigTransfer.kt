package io.github.lumiagg.gestureevo.link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.lumiagg.gestureevo.utils.ClipboardUtils

/**
 * Moves the link configuration in and out of the app through the clipboard.
 *
 * The clipboard avoids a file picker, which a bottom sheet cannot host without plumbing an
 * ActivityResultLauncher down from the hosting activity. The payload is plain JSON, so it can
 * also be pasted into a note app and kept as a manual backup.
 */
object LinkConfigTransfer {

    /** Puts the current configuration on the clipboard and returns the JSON that was copied. */
    suspend fun exportToClipboard(context: Context): String {
        val rules = LinkRuleRepository.loadRules(context)
        val exempts = LinkRuleRepository.loadExemptDomains(context)
        val extracts = LinkRuleRepository.loadExtractPatterns(context)
        val json = LinkConfigCodec.export(rules, exempts, extracts)
        ClipboardUtils.copyText(context, json)
        return json
    }

    /** Reads the clipboard and merges whatever it finds into the current configuration. */
    suspend fun importFromClipboard(context: Context): LinkImportResult {
        val raw = readClipboard(context).trim()
        if (raw.isEmpty()) {
            return LinkImportResult(0, 0, 0, error = "clipboard_empty")
        }
        val backup = LinkConfigCodec.decode(raw)
            ?: return LinkImportResult(0, 0, 0, error = "invalid_json")

        val existingRules = LinkRuleRepository.loadRules(context)
        val existingExempts = LinkRuleRepository.loadExemptDomains(context)
        val existingExtracts = LinkRuleRepository.loadExtractPatterns(context)

        val (merged, result) = LinkConfigCodec.merge(
            existingRules,
            existingExempts,
            existingExtracts,
            backup
        )
        if (!result.isSuccess) return result

        LinkRuleRepository.saveRules(context, merged.rules)
        LinkRuleRepository.saveExemptDomains(context, merged.exempts)
        LinkRuleRepository.saveExtractPatterns(context, merged.extracts)
        return result
    }

    private fun readClipboard(context: Context): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ""
        val clip: ClipData = clipboard.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).text?.toString().orEmpty()
    }
}
