pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mihonx") {
            from(files("gradle/mihon.versions.toml"))
        }
    }

    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Mihon"
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":data")
include(":domain")
include(":i18n")
include(":i18n-aniyomi")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
include(":hitomi-ext")
project(":hitomi-ext").projectDir = file("extensions/hitomi")
include(":animehay-ext")
project(":animehay-ext").projectDir = file("extensions/animehay")
include(":animevietsub-ext")
project(":animevietsub-ext").projectDir = file("extensions/animevietsub")
include(":wattpad-ext")
project(":wattpad-ext").projectDir = file("extensions/wattpad")
include(":novelfever-ext")
project(":novelfever-ext").projectDir = file("extensions/novelfever")
include(":docln-ext")
project(":docln-ext").projectDir = file("extensions/docln")
include(":ext-indexgen")
project(":ext-indexgen").projectDir = file("extensions/indexgen")
