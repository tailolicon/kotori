package mihon.feature.translation.offline

import mihon.feature.translation.provider.ProseContext
import mihon.feature.translation.provider.TranslationContext

/**
 * User-content prompts for the on-device HY-MT model.
 *
 * Chat templating is applied **once** in native code (GGUF metadata, else official HY-MT
 * special tokens). This layer must emit **raw user content only** — never wrap HY tokens.
 *
 * Bubble wording matches the PC benchmark that produced usable translations:
 * `Translate the following segment into {lang}, without additional explanation.\n\n{source}`
 */
internal object OfflinePrompts {

    /**
     * Pure helper for unit tests / documentation of the official HY-MT fallback tokens.
     * Production path must not pre-wrap prompts before calling native.
     */
    fun applyHyMtChatTemplate(userContent: String): String =
        buildString {
            append(OfflineModelSpec.HY_BOS)
            append(OfflineModelSpec.HY_USER)
            append(userContent)
            append(OfflineModelSpec.HY_ASSISTANT)
        }

    /** Raw user content for one bubble. Native applies chat template once. */
    fun singleLine(context: TranslationContext, source: String): String {
        val target = context.targetName
        val style = context.styleHint.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return buildString {
            append("Translate the following segment into ")
            append(target)
            append(", without additional explanation.")
            if (style.isNotEmpty()) {
                // Keep style extremely short — never replace the benchmarked instruction.
                append(style)
            }
            append("\n\n")
            append(source)
        }
    }

    /** Raw user content for one prose paragraph. */
    fun proseParagraph(
        context: TranslationContext,
        prose: ProseContext,
        paragraph: String,
    ): String {
        val target = context.targetName
        val glossary = prose.glossary.entries.take(8)
            .joinToString("; ") { "${it.key}=${it.value}" }
            .takeIf { it.isNotBlank() }
        return buildString {
            append("Translate the following segment into ")
            append(target)
            append(", without additional explanation.")
            if (glossary != null) {
                append(" Terms: ")
                append(glossary)
                append('.')
            }
            append("\n\n")
            append(paragraph)
        }
    }

    /**
     * @param keepMultiline when false (bubbles), prefer the first non-empty line;
     * when true (prose), preserve paragraph breaks.
     */
    fun cleanCompletion(raw: String, source: String, keepMultiline: Boolean = false): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = text
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        text = text
            .replace(OfflineModelSpec.HY_BOS, "")
            .replace(OfflineModelSpec.HY_USER, "")
            .replace(OfflineModelSpec.HY_ASSISTANT, "")
            .trim()

        val labelPrefixes = listOf(
            "Dịch:", "Bản dịch:", "Translation:", "Translated:", "Output:",
        )
        for (label in labelPrefixes) {
            if (text.startsWith(label, ignoreCase = true)) {
                text = text.substring(label.length).trim()
            }
        }

        val arrows = listOf("->", "=>", "→", "⇒")
        for (arrow in arrows) {
            val idx = text.lastIndexOf(arrow)
            if (idx > 0) {
                val left = text.substring(0, idx).trim().trim('"', '\'')
                val right = text.substring(idx + arrow.length).trim().trim('"', '\'')
                if (right.isNotBlank() &&
                    (left.equals(source, ignoreCase = true) || left.length < right.length * 2)
                ) {
                    text = right
                    break
                }
            }
        }

        text = text.trim().trim('"', '\'', '“', '”')
        if (keepMultiline) {
            return text.trim()
        }
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return firstLine.ifBlank { text }.trim()
    }

    fun cleanBubble(raw: String, source: String): String =
        cleanCompletion(raw, source, keepMultiline = false)

    fun cleanProse(raw: String, source: String): String =
        cleanCompletion(raw, source, keepMultiline = true)
}
