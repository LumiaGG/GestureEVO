package io.github.lumiagg.gestureevo.link

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

    /** Puts the current configuration on the clipboard. */
    suspend fun exportToClipboard(context: Context) {
        val snapshot = LinkRuleRepository.snapshot(context)
        val json = LinkConfigCodec.export(snapshot.rules, snapshot.exempts, snapshot.extracts)
        ClipboardUtils.copyText(context, json)
    }

    /** Reads the clipboard and merges whatever it finds into the current configuration. */
    suspend fun importFromClipboard(context: Context): LinkImportResult {
        val raw = ClipboardUtils.getText(context).trim()
        if (raw.isEmpty()) {
            return LinkImportResult(0, 0, 0, error = "clipboard_empty")
        }
        val backup = LinkConfigCodec.decode(raw)
            ?: return LinkImportResult(0, 0, 0, error = "invalid_json")

        return LinkRuleRepository.importConfig(context, backup)
    }
}
