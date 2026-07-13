import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(mihonx.plugins.kotlin.multiplatform)
    alias(mihonx.plugins.spotless)
}

kotlin {
    android {
        namespace = "tachiyomi.source.local"

        // TODO(antsy): Remove when https://youtrack.jetbrains.com/issue/KT-83319 is resolved
        withHostTest { }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        implementation(projects.sourceApi)
        api(projects.i18n)
        api(projects.i18nAniyomi)

        implementation(libs.unifile)
    }

    sourceSets {
        androidMain {
            dependencies {
                implementation(projects.core.archive)
                implementation(projects.core.common)
                implementation(projects.coreMetadata)

                // FFmpeg-kit (episode duration probing for local anime)
                implementation(libs.ffmpeg.kit)
                implementation(libs.arthenica.smartexceptions)

                // Move ChapterRecognition to separate module?
                implementation(projects.domain)

                implementation(libs.bundles.serialization)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}
