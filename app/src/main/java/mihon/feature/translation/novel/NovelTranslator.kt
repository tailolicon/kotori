package mihon.feature.translation.novel

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.provider.ProseContext
import mihon.feature.translation.provider.TranslationContext
import mihon.feature.translation.provider.TranslationProviders
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Translates light-novel chapters as prose.
 *
 * Two things make the output read like a person wrote it rather than a machine:
 *
 * 1. A persistent per-series glossary. Names, techniques and organisations are looked up and pinned
 *    into every later prompt, so chapter 40 does not quietly rename a character introduced in
 *    chapter 3.
 * 2. Carrying the tail of the previous chapter into the prompt, which gives the model the register and
 *    unresolved references it needs to open a chapter in the same voice it closed the last one.
 *
 * Long chapters are split into batches; each batch still sees the glossary and the running tail, so
 * the seams are not visible.
 */
class NovelTranslator(
    private val context: Application,
    private val manager: TranslationManager,
) {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private val glossaryRoot = File(context.filesDir, "translation/glossary").apply { mkdirs() }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    data class Progress(val chapterName: String, val completedBatches: Int, val totalBatches: Int)

    fun isEnabled(mangaId: Long): Boolean = manager.isNovelEnabled(mangaId)

    fun setEnabled(mangaId: Long, enabled: Boolean) = manager.setNovelEnabled(mangaId, enabled)

    /**
     * Returns the translated chapter body, using the cache when possible.
     *
     * On failure the original text is returned rather than an error: a reader mid-series would rather
     * see the untranslated chapter than an empty screen.
     */
    suspend fun translateChapter(
        mangaId: Long,
        chapterId: Long,
        chapterName: String,
        seriesTitle: String,
        text: String,
    ): String = withIOContext {
        if (text.isBlank()) return@withIOContext text

        val stamp = manager.preferences.outputStamp()
        val cacheFile = manager.textCacheFile(mangaId, chapterId, stamp)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return@withIOContext runCatching { cacheFile.readText() }.getOrDefault(text)
        }

        val paragraphs = splitParagraphs(text)
        if (paragraphs.isEmpty()) return@withIOContext text

        val provider = TranslationProviders.current(manager.preferences)
        val translationContext = TranslationContext(
            sourceLanguage = manager.preferences.sourceLanguage.get(),
            targetLanguage = manager.preferences.targetLanguage.get(),
            styleHint = manager.preferences.styleHint.get(),
        )

        val glossary = loadGlossary(mangaId).toMutableMap()
        val batches = batch(paragraphs)
        val translatedParts = mutableListOf<String>()
        var tail = loadTail(mangaId)

        try {
            batches.forEachIndexed { index, chunk ->
                _progress.value = Progress(chapterName, index, batches.size)
                val result = provider.translateProse(
                    paragraphs = chunk,
                    context = translationContext,
                    prose = ProseContext(
                        seriesTitle = seriesTitle,
                        glossary = glossary,
                        previousChapterTail = tail,
                    ),
                )
                result.glossary.forEach { entry -> glossary.putIfAbsent(entry.source, entry.target) }
                translatedParts += result.text
                tail = result.text.takeLast(TAIL_CHARS)
            }
        } catch (e: Throwable) {
            logcat { "Novel translation failed for chapter $chapterId: ${e.message}" }
            _progress.value = null
            return@withIOContext text
        }

        _progress.value = null

        val translated = translatedParts.joinToString("\n\n").trim()
        if (translated.isBlank()) return@withIOContext text

        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(translated)
        }
        saveGlossary(mangaId, glossary)
        saveTail(mangaId, translated.takeLast(TAIL_CHARS))

        translated
    }

    /**
     * Splits on blank lines, which is how every novel source we support delimits paragraphs. Runs of
     * blank lines collapse to one break so scene separators do not multiply.
     */
    private fun splitParagraphs(text: String): List<String> = text
        .split(PARAGRAPH_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /**
     * Groups paragraphs so each request stays comfortably inside the model's context and its output
     * limit. Batching by character count rather than paragraph count keeps a chapter of long
     * descriptive paragraphs from overflowing.
     */
    private fun batch(paragraphs: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var length = 0

        for (paragraph in paragraphs) {
            if (current.isNotEmpty() &&
                (length + paragraph.length > MAX_BATCH_CHARS || current.size >= MAX_BATCH_PARAGRAPHS)
            ) {
                batches += current
                current = mutableListOf()
                length = 0
            }
            current += paragraph
            length += paragraph.length
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    // ── Series continuity state ─────────────────────────────────────────────────────────────────

    private fun glossaryFile(mangaId: Long) = File(glossaryRoot, "$mangaId.json")

    private fun tailFile(mangaId: Long) = File(glossaryRoot, "$mangaId.tail.txt")

    fun loadGlossary(mangaId: Long): Map<String, String> {
        val file = glossaryFile(mangaId)
        if (!file.exists()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }
            .getOrDefault(emptyMap())
    }

    private fun saveGlossary(mangaId: Long, glossary: Map<String, String>) {
        // Cap the stored glossary: past a few hundred entries the prompt cost outweighs the
        // consistency benefit, and the earliest entries are the ones already firmly established.
        val trimmed = if (glossary.size > MAX_GLOSSARY_ENTRIES) {
            glossary.entries.take(MAX_GLOSSARY_ENTRIES).associate { it.key to it.value }
        } else {
            glossary
        }
        runCatching { glossaryFile(mangaId).writeText(json.encodeToString(trimmed)) }
    }

    fun clearGlossary(mangaId: Long) {
        glossaryFile(mangaId).delete()
        tailFile(mangaId).delete()
    }

    private fun loadTail(mangaId: Long): String =
        runCatching { tailFile(mangaId).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    private fun saveTail(mangaId: Long, tail: String) {
        runCatching { tailFile(mangaId).writeText(tail) }
    }

    private companion object {
        val PARAGRAPH_BREAK = Regex("\\n\\s*\\n+")
        const val MAX_BATCH_CHARS = 5000
        const val MAX_BATCH_PARAGRAPHS = 40
        const val TAIL_CHARS = 900
        const val MAX_GLOSSARY_ENTRIES = 300
    }
}
