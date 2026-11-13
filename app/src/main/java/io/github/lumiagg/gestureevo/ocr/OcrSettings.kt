package io.github.lumiagg.gestureevo.ocr

import android.content.Context
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore

/** OCR engine choice and the engine-independent result post-processing options. */
object OcrSettings {
    const val KEY_ENGINE = "ocr_engine"
    const val BUILTIN = "builtin"
    const val COLOROS = "coloros"
    const val KEY_STRIP_SPACES = "ocr_strip_spaces"
    const val KEY_FILTER_LOW_CONFIDENCE = "ocr_filter_low_confidence"

    /** Lines below this confidence are dropped when the low-confidence filter is enabled. */
    const val MIN_CONFIDENCE = 0.6f

    suspend fun readEngine(context: Context): String =
        context.settingsStore.readKey(KEY_ENGINE, BUILTIN)

    suspend fun saveEngine(context: Context, value: String) {
        context.settingsStore.saveKey(KEY_ENGINE, value)
    }

    suspend fun isColorOs(context: Context): Boolean = readEngine(context) == COLOROS

    suspend fun stripSpaces(context: Context): Boolean =
        context.settingsStore.readKey(KEY_STRIP_SPACES, false)

    suspend fun filterLowConfidence(context: Context): Boolean =
        context.settingsStore.readKey(KEY_FILTER_LOW_CONFIDENCE, false)

    /**
     * Removes whitespace except newlines, so joined line results keep their separators.
     * \p{Zs} covers Unicode space separators (regular, NBSP and full-width spaces);
     * Java's default \s class would miss them.
     */
    fun stripInline(text: String): String = text.replace(Regex("[\\p{Zs}\\t\\x0B\\f\\r]+"), "")
}
