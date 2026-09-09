import java.io.FileInputStream
import java.util.Properties
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// ── Keystore helpers (top-level so they can be used by signingConfigs) ─────
// Release signing is configured only when the gitignored keystore.properties exists.
fun loadProperties(path: String): Properties = Properties().apply {
    val source = rootProject.file(path)
    require(source.exists()) { "Missing $path" }
    FileInputStream(source).use(::load)
}

val appVersion = loadProperties("version.properties")
val keystoreFile = rootProject.file("keystore.properties")
val keystore = if (keystoreFile.exists()) loadProperties("keystore.properties") else null
val streamingFile = rootProject.file("streaming.properties")
val streaming = if (streamingFile.exists()) loadProperties("streaming.properties") else null
val twitchClientId = providers.environmentVariable("TWITCH_CLIENT_ID").orNull
    ?: streaming?.getProperty("TWITCH_CLIENT_ID").orEmpty()
// OAuth client IDs are public identifiers. Google still authenticates Android builds using the
// package name and signing certificate registered against each ID in Google Cloud.
val googleLocalClientId =
    "333115759527-9i5hsmubo1up8d7qvqjbgfm2ur9inkvl.apps.googleusercontent.com"
val googlePublicClientId =
    "333115759527-o8poec8lbsa8c98mkpb2k52g7mist9o6.apps.googleusercontent.com"

android {
    namespace = "com.audiopro.djmrec"
    compileSdk = 35
    // Pinned so CI (and every dev machine) builds native code against the exact same
    // NDK — avoids "works locally, fails/behaves differently in CI" native-build drift.
    ndkVersion = "26.1.10909125"

    val releaseSigning = keystore?.let { properties ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.audiopro.djmrec"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersion.getProperty("VERSION_CODE").toInt()
        versionName = appVersion.getProperty("VERSION_NAME")
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"${twitchClientId.replace("\"", "\\\"")}\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googlePublicClientId\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", "true")
        buildConfigField("boolean", "PROTOCOL_RESEARCH", "false")

        // Only ship arm64-v8a: all modern DJ-capable Android hardware (USB-C host + UAC2)
        // is 64-bit ARM. Keeping a single ABI keeps the native audio path easy to validate.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = releaseSigning
            configure<CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
            }
        }
        debug {
            isDebuggable = true
            // So debug and release can be installed side-by-side
            applicationIdSuffix = ".debug"
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleLocalClientId\"")
            buildConfigField("boolean", "FIREBASE_CONFIGURED", "false")
        }
        create("local") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleLocalClientId\"")
            buildConfigField("boolean", "FIREBASE_CONFIGURED", "false")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
        }
        create("experimental") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".experimental"
            versionNameSuffix = "-experimental"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "PROTOCOL_RESEARCH", "true")
            buildConfigField("boolean", "FIREBASE_CONFIGURED", "false")
            // This package/signing identity has no registered Google OAuth client yet.
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"\"")
            externalNativeBuild { cmake { arguments += "-DDJMREC_PROTOCOL_RESEARCH=ON" } }
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
        }
    }

    // ── APK output naming ──────────────────────────────────────────────────
    // Produces versioned APK names from version.properties.
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val buildSuffix = if (variant.buildType.name == "experimental") "" else "-${variant.buildType.name}"
            output.outputFileName = "DJM-Rec-for-Android-v${variant.versionName}$buildSuffix.apk"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // We statically link Oboe/FLAC into libdjmrec_audio.so, so only
            // the shared C++ runtime and our own library need to ship.
            useLegacyPackaging = false
        }
    }
}

// Firebase is production-only. Keep debug/local builds isolated from production reports and make
// ordinary contributor builds work without the gitignored Firebase configuration file.
tasks.matching { it.name in setOf("processDebugGoogleServices", "processLocalGoogleServices", "processExperimentalGoogleServices") }
    .configureEach {
        enabled = false
    }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    testImplementation(kotlin("test"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.github.pedroSG94.RootEncoder:library:2.7.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
