import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Versioning
//
// The release workflow passes -PversionName=1.0.1 so the version can be set from the GitHub UI
// instead of a committed file. versionCode is derived from it (1.0.1 -> 10001) because Play
// requires a monotonically increasing integer, and hand-maintaining a second number is a
// reliable way to get it wrong.
// ---------------------------------------------------------------------------
val releaseVersion: String = (project.findProperty("versionName") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "1.0"

val derivedVersionCode: Int = run {
    val parts = releaseVersion.substringBefore('-').split('.')
        .map { it.toIntOrNull() ?: 0 }
        .take(3)
    val padded = parts + List((3 - parts.size).coerceAtLeast(0)) { 0 }
    // 1.0.1 -> 1*10000 + 0*100 + 1 = 10001
    val weights = listOf(10_000, 100, 1)
    padded.mapIndexed { index, part -> part * weights[index] }.sum()
}

// ---------------------------------------------------------------------------
// Release signing
//
// Optional by design. CI passes a base64 keystore through repository secrets; local builds and
// secret-less CI fall back to the debug key so `assembleRelease` always yields something
// installable. The fallback is loud rather than silent: a debug-signed APK cannot be published to
// Play, and that should not be discovered at upload time.
// ---------------------------------------------------------------------------
val keystoreFile: File? = System.getenv("CODEDROID_KEYSTORE_BASE64")
    ?.takeIf { it.isNotBlank() }
    ?.let { encoded ->
        val file = File(project.layout.buildDirectory.get().asFile, "codedroid-release.jks")
        file.parentFile?.mkdirs()
        file.writeBytes(Base64.getDecoder().decode(encoded.trim()))
        logger.lifecycle("Using release keystore from CODEDROID_KEYSTORE_BASE64")
        file
    }

android {
    namespace = "io.github.nezjusz.codedroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.nezjusz.codedroid"
        minSdk = 26
        targetSdk = 37
        versionCode = derivedVersionCode
        versionName = releaseVersion
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("CODEDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CODEDROID_KEY_ALIAS")
                keyPassword = System.getenv("CODEDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release keystore configured (set CODEDROID_KEYSTORE_BASE64, " +
                        "CODEDROID_KEYSTORE_PASSWORD, CODEDROID_KEY_ALIAS, CODEDROID_KEY_PASSWORD). " +
                        "Falling back to the DEBUG signing key: this APK is installable but cannot " +
                        "be published to Google Play."
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.autolink)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    // Gradle does not forward -D flags into the test JVM, so pass them through explicitly.
    // Live tests skip themselves when these are absent.
    listOf("opencode.url", "opencode.token").forEach { key ->
        val value = System.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_'))
        if (value != null) systemProperty(key, value)
    }
    testLogging { events("passed", "skipped", "failed") }
}
