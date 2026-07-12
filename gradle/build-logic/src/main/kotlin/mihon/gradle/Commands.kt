package mihon.gradle

import org.gradle.api.Project
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Git is needed in your system PATH for these commands to work.
// Falls back to a placeholder when not building from a git checkout.
fun Project.getLatestCommitCount(): String {
    return execOrDefault("git rev-list --count HEAD", default = "1")
}

fun Project.getLatestCommitSha(): String {
    return execOrDefault("git rev-parse --short HEAD", default = "unknown")
}

private val BUILD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/**
 * @param useLatestCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, the current time is used. Both are in UTC.
 * @return A formatted string representing the build time. The format used is defined by [BUILD_TIME_FORMATTER].
 */
fun Project.getBuildTime(useLatestCommitTime: Boolean): String {
    val epoch = if (useLatestCommitTime) {
        execOrDefault("git log -1 --format=%ct", default = "").toLongOrNull()
    } else {
        null
    }
    return if (epoch != null) {
        Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    } else {
        LocalDateTime.now(ZoneOffset.UTC).format(BUILD_TIME_FORMATTER)
    }
}

fun Project.exec(command: String): String {
    return providers.exec {
        commandLine = command.split(" ")
    }
        .standardOutput
        .asText
        .get()
        .trim()
}

private fun Project.execOrDefault(command: String, default: String): String {
    val result = providers.exec {
        commandLine = command.split(" ")
        isIgnoreExitValue = true
    }
    return if (result.result.get().exitValue == 0) {
        result.standardOutput.asText.get().trim()
    } else {
        default
    }
}
