pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
        id("org.jetbrains.compose") version "1.8.2"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "VpnRunner"
include("core", "platform")
