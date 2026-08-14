package mihon.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * Verifies the native libraries after Android's duplicate resolution has selected the files that
 * will actually ship. ONNX Runtime versions its exported C API symbols, so a consumer requiring a
 * different `VERS_x.y.z` fails only at runtime with an unhelpful [UnsatisfiedLinkError].
 */
abstract class VerifyOnnxCompatibilityTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibrariesDirectory: DirectoryProperty

    @get:Input
    abstract val expectedAbis: ListProperty<String>

    @get:Input
    abstract val expectedRuntimeSha256: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val root = nativeLibrariesDirectory.get().asFile
        val failures = buildList {
            expectedAbis.get().forEach { abi ->
                val abiDirectory = root.resolve(abi)
                val runtime = abiDirectory.resolve(ONNX_RUNTIME)
                val bridge = abiDirectory.resolve(ONNX_JAVA_BRIDGE)

                if (!runtime.isFile) {
                    add("$abi: missing $ONNX_RUNTIME")
                    return@forEach
                }
                if (!bridge.isFile) {
                    add("$abi: missing $ONNX_JAVA_BRIDGE")
                    return@forEach
                }

                val expectedHash = expectedRuntimeSha256.get()[abi]
                val actualHash = sha256(runtime)
                if (expectedHash == null) {
                    add("$abi: no approved $ONNX_RUNTIME SHA-256 is configured")
                    return@forEach
                }
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    add(
                        "$abi: $ONNX_RUNTIME is not the approved complete onnxruntime-android binary " +
                            "(expected $expectedHash, got $actualHash)",
                    )
                    return@forEach
                }

                val provided = symbolVersions(runtime)
                if (provided.isEmpty()) {
                    add("$abi: $ONNX_RUNTIME exports no versioned ONNX API")
                    return@forEach
                }

                listOf(bridge, abiDirectory.resolve(MOONSHINE_RUNTIME))
                    .filter(File::isFile)
                    .forEach { consumer ->
                        val required = symbolVersions(consumer)
                        val unavailable = required - provided
                        if (unavailable.isNotEmpty()) {
                            add(
                                "$abi: ${consumer.name} requires ${unavailable.sorted()} but " +
                                    "$ONNX_RUNTIME provides ${provided.sorted()}",
                            )
                        }
                    }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Incompatible ONNX native libraries would be packaged:")
                    failures.forEach { appendLine("- $it") }
                    append(
                        "Package the approved onnxruntime-android binary and keep moonshine-voice " +
                            "native symbol versions aligned for every ABI.",
                    )
                },
            )
        }

        logger.lifecycle("Verified ONNX native compatibility for ${expectedAbis.get().joinToString()}.")
    }

    private fun symbolVersions(file: File): Set<String> =
        VERSION_PATTERN.findAll(file.readBytes().toString(Charsets.ISO_8859_1))
            .map(MatchResult::value)
            .toSet()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val ONNX_RUNTIME = "libonnxruntime.so"
        const val ONNX_JAVA_BRIDGE = "libonnxruntime4j_jni.so"
        const val MOONSHINE_RUNTIME = "libmoonshine.so"
        val VERSION_PATTERN = Regex("VERS_[0-9]+\\.[0-9]+\\.[0-9]+")
    }
}
