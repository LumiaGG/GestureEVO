package io.github.lumiagg.gestureevo.ocr.coloros

import io.github.lumiagg.gestureevo.ocr.OcrSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorOcrJsonTest {
    @Test
    fun parsesTheVerifiedResultShape() {
        // Shape mirrors the on-device ocr_ar result (angle + items with text and 4-point rects).
        val json = """
            {"angle":0,"items":[
              {"text":"ColorOS OCR 12345","rect":[
                 {"x":68.38,"y":97.06},{"x":547.06,"y":97.06},
                 {"x":547.06,"y":143.38},{"x":68.38,"y":143.38}],
               "probabilities":[0.5,0.5]},
              {"text":"手势进化 文字识别","rect":[
                 {"x":399.26,"y":313.24},{"x":829.41,"y":313.24},
                 {"x":829.41,"y":370.59},{"x":399.26,"y":370.59}]}
            ]}
        """.trimIndent()
        val lines = ColorOcrLine.parseJson(json)
        assertEquals(2, lines.size)
        assertEquals("ColorOS OCR 12345", lines[0].text)
        assertEquals(68.38, lines[0].xs[0], 1e-9)
        assertEquals(370.59, lines[1].ys[3], 1e-9)
        assertEquals(0.5, lines[0].confidence, 1e-9)
        assertEquals(1.0, lines[1].confidence, 1e-9)
    }

    @Test
    fun stripInlineRemovesWhitespaceButKeepsNewlines() {
        assertEquals("ColorOSOCR12345", OcrSettings.stripInline("ColorOS OCR 12345"))
        assertEquals("手势", OcrSettings.stripInline("手\t 势"))
        assertEquals("全角空格", OcrSettings.stripInline("全角\u3000空格"))
        assertEquals("first\nsecond", OcrSettings.stripInline("first \n second"))
    }

}
