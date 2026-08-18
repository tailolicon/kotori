import mihon.gradle.Config
import mihon.gradle.getBuildTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask
import mihon.gradle.tasks.VerifyOnnxCompatibilityTask
import org.gradle.api.tasks.Sync
import java.io.FileInputStream
import java.util.Properties
import kotlin.io.encoding.Base64

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val kotoriUpdateUrl = providers.gradleProperty("kotori-update-url")
    .orElse(providers.environmentVariable("KOTORI_UPDATE_URL"))
    .orElse("https://github.com/tailolicon/kotori/releases/latest/download/update.json")
    .get()
val kotoriVersionCode = providers.gradleProperty("kotori-version-code")
    .orElse(providers.environmentVariable("KOTORI_VERSION_CODE"))
    .orNull
    ?.toIntOrNull()
    ?: (1_100_000_000 + getLatestCommitCount().toInt())

val onnxRuntimeNative = configurations.create("onnxRuntimeNative") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(onnxRuntimeNative.name, libs.onnxruntime.android)

val extractedOnnxRuntimeDirectory = layout.buildDirectory.dir("generated/onnxRuntimeNative")
val extractOnnxRuntimeNative = tasks.register<Sync>("extractOnnxRuntimeNative") {
    val runtimeAar = onnxRuntimeNative.elements.map { files -> files.single().asFile }
    from(runtimeAar.map(::zipTree)) {
        include("jni/**/libonnxruntime.so")
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
    }
    into(extractedOnnxRuntimeDirectory)
}

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.mihon"

        // Kotori uses a high, commit-monotonic range so official releases remain newer than legacy
        // and local test builds. tools/publish-kotori-update.ps1 may override this with an even newer
        // value when publishing multiple builds from the same commit.
        versionCode = kotoriVersionCode

        // The release name is written down; the commit count is not. Release builds carry a plain
        // semantic version, while debug/update/preview append `-${commitCount}` through their
        // versionNameSuffix — so a milestone reads as a milestone, and every other build still
        // says exactly which commit it came from.
        versionName = "1.0.17"

        buildConfigField("String", "COMMIT_COUNT", "\"${getLatestCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getLatestCommitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        // Kotori owns its update feed, so updater support is part of every normal build. The Gradle
        // flag remains accepted for compatibility with the upstream workflows.
        buildConfigField("boolean", "UPDATER_ENABLED", "true")
        buildConfigField(
            "String",
            "KOTORI_UPDATE_URL",
            "\"${kotoriUpdateUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Offline llama.cpp JNI: phone (arm64) + MuMu (x86_64).
        // Source is vendored at third_party/llama.cpp (pin b10240); see app/src/main/cpp/CMakeLists.txt.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        // No `ndk { abiFilters }` here: AGP refuses to configure when it is set alongside abi
        // splits, even when the two lists agree. The splits block below is what decides which ABIs
        // ship, and each split apk carries only its own.
    }

    if (System.getenv("MIHON_GITHUB_RELEASE").toBoolean()) {
        val tempStoreFile = file(System.getenv("RUNNER_TEMP")).resolve("antsy.keystore")

        val storeFileBytes = System.getenv("storeFileBase64").let(Base64::decode)
        tempStoreFile.outputStream().use { it.write(storeFileBytes) }

        signingConfigs {
            named("debug") {
                storeFile = tempStoreFile
                storePassword = System.getenv("storePassword")
                keyAlias = System.getenv("keyAlias")
                keyPassword = System.getenv("keyPassword")
            }
        }
    } else if (keystorePropertiesFile.exists()) {
        val keystoreProperties = FileInputStream(keystorePropertiesFile).use { Properties().apply { load(it) } }

        signingConfigs {
            named("debug") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val debug = getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-$kotoriVersionCode"
            isPseudoLocalesEnabled = true
        }
        val release = getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            // Kotori has always shipped as `app.mihon.dev` — every build a reader has ever
            // installed, from the first test APK onwards, carries that id. Leaving it off here made
            // `assembleRelease` produce `app.mihon` instead, which Android treats as an unrelated
            // app: it installs alongside the real one, starts with an empty library, and cannot be
            // updated by anything the reader already has. Two releases went out that way.
            applicationIdSuffix = ".dev"

            signingConfig = debug.signingConfig

            isProfileable = true

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = true)}\"")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("update") {
            initWith(release)

            applicationIdSuffix = ".dev"
            versionNameSuffix = debug.versionNameSuffix

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }

        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".debug"

            versionNameSuffix = debug.versionNameSuffix

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        }
        create("benchmark") {
            initWith(release)

            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
    }

    sourceSets {
        getByName("preview").res.directories.add("src/debug/res")
        getByName("benchmark").res.directories.add("src/debug/res")
    }

    androidResources {
        // The bundled bubble-detector weights are already compressed; letting aapt deflate them
        // again would break ONNX Runtime's memory-mapped load path.
        noCompress += listOf("onnx")
    }

    // Offline translation (llama.cpp). Built for phone (arm64) and MuMu emulator (x86_64).
    // Source is vendored/pinned via app/src/main/cpp/CMakeLists.txt — GGUF weights are NOT bundled.
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    splits {
        abi {
            isEnable = true
            // No universal apk: at ~480 MB it was the largest artifact of every release and nobody
            // installed it. 32-bit is gone for the same reason — Kotori's readers are on arm64, and
            // building four splits plus a universal turned every release into a 1.1 GB upload.
            // x86_64 stays only so the emulator can run what the phone will run.
            isUniversalApk = false
            reset()
            include("arm64-v8a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // Two copies of libonnxruntime.so reach the merge: one inside the moonshine-voice AAR
            // (used by the novel reader's text-to-speech) and one from onnxruntime-android, which the
            // translation detector needs for the Java API that moonshine does not expose.
            //
            // Only one can ship. The complete Microsoft runtime is injected as an app JNI source,
            // and verify<Variant>OnnxCompatibility pins its SHA-256 and checks every consumer's
            // versioned symbols after duplicate resolution. This makes dependency-ordering changes
            // fail the build instead of breaking OCR only on users' devices.
            pickFirsts += "**/libonnxruntime.so"

            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    implementation(projects.i18nAniyomi)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialIcons)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    // Anime player (mpv) + video tooling
    implementation(libs.androidx.constraintLayout.compose)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.mediasession)
    implementation(libs.aniyomi.mpv)
    implementation(libs.ffmpeg.kit)
    implementation(libs.arthenica.smartexceptions)
    implementation(libs.torrserver)
    implementation(libs.seeker)
    implementation(libs.truetypeparser)

    // On-device manga translation: YOLOv8-seg speech-bubble detector (ONNX Runtime) plus ML Kit
    // text recognition for CJK glyph geometry. Both run fully offline; the 12 MB ONNX model is
    // bundled in assets/translation/ and the ML Kit models are bundled by the AAR.
    //
    // OCR needs the complete Microsoft runtime rather than Moonshine's smaller embedded build.
    // extractOnnxRuntimeNative injects this artifact as an app JNI source, and the native
    // compatibility task pins the selected runtime's SHA-256 per ABI.
    implementation(libs.onnxruntime.android)

    // On-device neural text-to-speech for the novel reader's listening mode. The library ships
    // native ONNX runtimes; the voice models themselves are downloaded at runtime rather than
    // bundled, so this adds no model weight to the APK.
    implementation(libs.moonshine.voice)
    implementation(libs.mlkit.text.base)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.korean)

    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.sqldelight.async)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // YouTube extraction for the built-in Muse / Ani-One sources (playlist = series)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)
}

androidComponents {
    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants
        variant.sources.jniLibs?.addStaticSourceDirectory(
            extractedOnnxRuntimeDirectory.get().asFile.absolutePath,
        )

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(projectDir.resolve("src/main/shortcuts.xml"))
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }

        val verifyOnnxCompatibilityTask = tasks.register<VerifyOnnxCompatibilityTask>(
            "verify${variantName}OnnxCompatibility",
        ) {
            nativeLibrariesDirectory.set(
                layout.buildDirectory.dir(
                    "intermediates/merged_native_libs/${variant.name}/merge${variantName}NativeLibs/out/lib",
                ),
            )
            expectedAbis.set(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
            expectedRuntimeSha256.set(
                mapOf(
                    "armeabi-v7a" to "57048b8d54896d16355ee367bfc129c5925468ae503b681b8d0cd49ceefa468e",
                    "arm64-v8a" to "e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302",
                    "x86" to "213d91ebb0cfd511c18c0057c69145de0abc6bdc9c63429bf04dcdeaf3fd861a",
                    "x86_64" to "972c17c056eaae946a415d9efdd8018b729639974df075e495f0092441478fb7",
                ),
            )
            dependsOn("merge${variantName}NativeLibs")
        }
        tasks.matching { it.name == "merge${variantName}JniLibFolders" }.configureEach {
            dependsOn(extractOnnxRuntimeNative)
        }
        tasks.matching { it.name == "merge${variantName}NativeLibs" }.configureEach {
            dependsOn(extractOnnxRuntimeNative)
            finalizedBy(verifyOnnxCompatibilityTask)
        }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
