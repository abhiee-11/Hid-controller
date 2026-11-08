// Root project — plugin versions declared here, applied per-module.
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// AGP 9 built-in Kotlin consumes the Kotlin Gradle plugin at runtime.
// Declaring 2.4.21 here upgrades the default 2.2.10 (documented path in the
// AGP 9 release notes: "Upgrade to a higher KGP version").
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
