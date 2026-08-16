import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

val extVersionCode = 2
val extLib = "1.4"

android {
    namespace = "app.kotori.extension.all.hitomi"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kotori.extension.all.hitomi"
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
    compileOnly(projects.sourceApi)
    compileOnly(projects.core.common)
    compileOnly(libs.okhttp.core)
    compileOnly(libs.rxJava)
    compileOnly(libs.jsoup)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.injekt)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

android.testOptions.unitTests.all {
    it.useJUnitPlatform()
}
