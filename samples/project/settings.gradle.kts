pluginManagement {
    includeBuild("../..")
    repositories { gradlePluginPortal() }
}

plugins {
    id("me.monori.gradle-build-diagnostics")
}

rootProject.name = "kotlin-jvm-sample"

