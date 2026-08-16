package mihon.feature.translation.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mihon.feature.translation.PageTranslator
import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.provider.TranslationProviders
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only entry point for the translation regression suite.
 *
 * The host runner (`regression/run.py`) pushes fixture pages to this app's external files dir,
 * fires this receiver, and polls for `DONE.txt`. Each fixture is translated with
 * [DeterministicTranslationProvider], so the rendered PNGs are reproducible and can be diffed
 * against blessed goldens byte for byte; alongside each PNG the receiver saves the pipeline's own
 * log lines for that page, which is where a diff explains *which decision* changed.
 *
 * Trigger:
 *   adb shell am broadcast -n app.mihon.dev/mihon.feature.translation.debug.RegressionReceiver
 *
 * The work runs in a process-scoped coroutine, not in onReceive: a receiver gets ~10 s before the
 * system kills its process for ANR, and a 30-page corpus takes minutes. The runner launches the app
 * first so the process stays alive for the whole run.
 */
class RegressionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        scope.launch { runSuite(app) }
    }

    private suspend fun runSuite(context: Context) {
        val base = context.getExternalFilesDir(null)?.resolve("regression") ?: return
        val input = File(base, "in")
        // Create it ourselves rather than relying on `adb push` to. A directory adb creates belongs
        // to the shell user with the shell's SELinux label, and after the app is reinstalled under a
        // new UID it can open those files but not stat them — `isFile()` returns false for every
        // one and the suite runs zero fixtures while reporting no error. A directory the app owns
        // accepts pushes into it and stats fine.
        input.mkdirs()
        val output = File(base, "out")
        output.deleteRecursively()
        output.mkdirs()

        val fixtures = input.listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()
        val summary = StringBuilder("fixtures=${fixtures.size}\n")

        val translator = PageTranslator(context, Injekt.get<TranslationPreferences>())
        TranslationProviders.overrideForTesting = DeterministicTranslationProvider()
        try {
            for (fixture in fixtures) {
                val since = LOG_STAMP.format(Date())
                val bitmap = BitmapFactory.decodeFile(fixture.path)
                if (bitmap == null) {
                    summary.append("${fixture.name} DECODE_FAIL\n")
                    continue
                }
                val verdict = try {
                    val rendered = translator.translate(bitmap, "regression:${fixture.name}")
                    File(output, "${fixture.nameWithoutExtension}.png").outputStream().use {
                        rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    if (rendered !== bitmap) rendered.recycle()
                    "OK"
                } catch (e: PageTranslator.NothingToTranslate) {
                    "NOTHING"
                } catch (e: Throwable) {
                    logcat { "Regression fixture ${fixture.name} failed: ${e.message}" }
                    "FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                } finally {
                    bitmap.recycle()
                }
                dumpTrace(since, File(output, "${fixture.nameWithoutExtension}.trace.txt"))
                summary.append("${fixture.name} $verdict\n")
            }
        } finally {
            TranslationProviders.overrideForTesting = null
        }
        File(output, "DONE.txt").writeText(summary.toString())
        logcat { "Regression run complete: ${fixtures.size} fixtures" }
    }

    /**
     * Saves this page's pipeline log lines — every guard already narrates its decisions there, so
     * the trace costs no changes to the pipeline itself. Timestamps and PIDs are stripped so two
     * identical runs produce identical files; volatile system noise is dropped for the same reason.
     */
    private fun dumpTrace(sinceStamp: String, target: File) {
        runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "tag", "-T", sinceStamp),
            )
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            // Match the *tag* at the start of the line, not anywhere in it. Runtime noise mentions
            // pipeline class names — "Compiler allocated 9382KB to compile ... PageTranslator$..."
            // — and a substring match let those through, where their timing-dependent interleaving
            // made two identical runs produce different traces.
            val kept = lines.asSequence()
                .map { it.trim() }
                .filter { line -> TRACE_TAGS.any { tag -> line.startsWith("$tag:", ignoreCase = false) || Regex("^[VDIWE]/$tag\\s*:").containsMatchIn(line) } }
                .filterNot { line -> NOISE.any { line.contains(it) } }
                .toList()
            target.writeText(kept.joinToString("\n"))
        }.onFailure { target.writeText("(trace unavailable: ${it.message})") }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val LOG_STAMP = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

        /** Tags the translation pipeline logs under; extend when new stages start narrating. */
        val TRACE_TAGS = listOf(
            "DispatchedCoroutine", "BubbleRenderer", "BubbleDetector", "TextBlockDetector",
            "PageTranslator", "TranslationManager", "RegressionReceiver",
        )
        // "Bubble detector ready" is logged once per process when the ONNX session opens, so it
        // lands in whichever fixture happened to trigger the load. That is not a decision about the
        // page, and letting it through made two runs of identical code report a trace change.
        val NOISE = listOf(
            "GC freed", "Davey", "Choreographer", "Regression run complete", "Bubble detector ready",
        )
    }
}
