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
    description = "Write index.pb / index.min.json / repo.json from the built Hitomi APK"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.kotori.extension.IndexGenKt")
    workingDir = rootProject.projectDir
    dependsOn(":hitomi-ext:assembleRelease")
}
