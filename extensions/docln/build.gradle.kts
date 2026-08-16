import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Extensions load through a child-first classloader. A kotlin-stdlib inside the apk therefore
// shadows the host's, and every Kotlin runtime type that crosses the boundary — Continuation on a
// suspend override, Function2 on a lambda handed to runBlocking — becomes a *different* class:
// LinkageError on load, ClassCastException on use.
extra["kotlin.stdlib.default.dependency"] = "false"

val extVersionCode = 1
val extLib = "1.4"

android {
    namespace = "app.kotori.extension.vi.docln"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kotori.extension.vi.docln"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "$extLib.$extVersionCode"
    }

    signingConfigs {
        create("kotori") {
            val propsFile = rootProject.file("extensions/keystore/keystore.properties")
            if (propsFile.isFile) {
                val props = Properties().apply { propsFile.inputStream().use(::load) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("kotori") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.findByName("kotori") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(projects.sourceApi)
    compileOnly(projects.core.common)
    compileOnly(libs.okhttp.core)
    compileOnly(libs.rxJava)
    compileOnly(libs.jsoup)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.injekt)
    compileOnly(libs.androidx.preference)
}
