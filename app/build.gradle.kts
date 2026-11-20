import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String, default: String = ""): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: default).replace("\"", "")

android {
    namespace = "com.usool.map"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.usool.map"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Phone and tablet processors only; emulator-only x86 libraries would double the download.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "GROK_API_KEY", "\"${secret("GROK_API_KEY")}\"")
        buildConfigField("String", "GROK_MODEL", "\"${secret("GROK_MODEL", "grok-4")}\"")
        buildConfigField("String", "AZURE_SPEECH_KEY", "\"${secret("AZURE_SPEECH_KEY")}\"")
        buildConfigField("String", "AZURE_SPEECH_REGION", "\"${secret("AZURE_SPEECH_REGION", "qatarcentral")}\"")
        buildConfigField("String", "TOMTOM_API_KEY", "\"${secret("TOMTOM_API_KEY")}\"")
        buildConfigField("String", "TERRAIN_TILES_URL", "\"${secret("TERRAIN_TILES_URL")}\"")
        buildConfigField("String", "MAPILLARY_TOKEN", "\"${secret("MAPILLARY_TOKEN")}\"")
        buildConfigField("String", "MAPTILER_API_KEY", "\"${secret("MAPTILER_API_KEY")}\"")
        buildConfigField("String", "SATELLITE_TILES_URL", "\"${secret("SATELLITE_TILES_URL")}\"")
        buildConfigField("String", "SATELLITE_NAME", "\"${secret("SATELLITE_NAME")}\"")
        buildConfigField("String", "SATELLITE_ATTRIBUTION", "\"${secret("SATELLITE_ATTRIBUTION")}\"")
        buildConfigField("int", "SATELLITE_MAX_ZOOM", (secret("SATELLITE_MAX_ZOOM", "19").toIntOrNull() ?: 19).toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// APK/AAB files are named UsoolMap-<version>-<buildType>.
base { archivesName.set("UsoolMap-${android.defaultConfig.versionName}") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
