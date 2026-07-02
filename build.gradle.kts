// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.0+ is required for the standalone `org.jetbrains.kotlin.plugin.compose` Gradle
    // plugin (the Compose Compiler moved into the Kotlin repo as of 2.0; on 1.9.x you'd instead
    // configure `composeOptions.kotlinCompilerExtensionVersion` directly).
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
