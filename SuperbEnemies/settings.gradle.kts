pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "NeoForged"
            url = uri("https://maven.neoforged.net/releases")
            content {
                includeGroup("net.neoforged")
            }
        }
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.0"
    }
}

plugins {
    // Lets Gradle download the Java 17 toolchain automatically if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "superbenemies"
