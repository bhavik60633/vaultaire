// Phase-0 spike: THROWAWAY CODE. Not product code; see ../../README.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.vaultaire.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.vaultaire.spike"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-spike"
        // Only the two ABIs cargo-ndk builds (ADR-001 criterion 1).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            // Criterion 2: the UniFFI/JNA bindings must survive R8.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Spike only: signed with the local debug key so it installs. Never for a real release.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // UniFFI's Kotlin bindings call the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
