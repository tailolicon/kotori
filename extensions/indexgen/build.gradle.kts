plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
}

tasks.register<JavaExec>("generateRepo") {
    group = "kotori"
    description = "Write index.pb / index.min.json / repo.json from the built extension APKs"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.kotori.extension.IndexGenKt")
    workingDir = rootProject.projectDir
    // Every module IndexGen publishes, not just Hitomi. With only Hitomi here, a publish on a tree
    // holding stale outputs copied last release's apk over the store one and read its versionCode
    // out of the stale output-metadata.json beside it — exit 0, "Wrote manga store", and none of
    // the changes actually shipped. Keep this list in step with MODULES in IndexGen.kt.
    listOf("hitomi", "wattpad", "novelfever", "docln", "animehay", "animevietsub")
        .forEach { dependsOn(":$it-ext:assembleRelease") }
}
