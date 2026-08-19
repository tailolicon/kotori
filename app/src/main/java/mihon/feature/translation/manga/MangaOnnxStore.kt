package mihon.feature.translation.manga

import android.content.Context
import java.io.File

/** Copies an ONNX asset to filesDir the same way [mihon.feature.translation.detect.BubbleDetector] does. */
internal object MangaOnnxStore {

    fun extract(context: Context, assetPath: String, fileName: String): File {
        val modelFile = File(context.filesDir, fileName)
        if (!isCurrent(context, assetPath, modelFile)) {
            write(context, assetPath, modelFile)
        }
        return modelFile
    }

    private fun isCurrent(context: Context, assetPath: String, modelFile: File): Boolean {
        if (!modelFile.isFile || modelFile.length() == 0L) return false
        val assetLength = runCatching {
            context.assets.openFd(assetPath).use { it.length }
        }.getOrElse {
            runCatching {
                context.assets.open(assetPath).use { input ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                    total
                }
            }.getOrNull()
        } ?: return true
        return modelFile.length() == assetLength
    }

    private fun write(context: Context, assetPath: String, modelFile: File) {
        val temp = File(modelFile.parentFile, "${modelFile.name}.tmp")
        temp.delete()
        context.assets.open(assetPath).use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        modelFile.delete()
        check(temp.renameTo(modelFile)) { "Could not install $assetPath" }
    }
}
