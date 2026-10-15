plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.vaultaire"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.vaultaire"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-beta"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric reaches into FileDescriptor internals on JDK 21.
                // ./gradlew :app:testDebugUnitTest -Pscreenshots renders every screen (tools/Screenshots.kt).
                if (project.hasProperty("screenshots")) {
                    it.systemProperty("vaultaire.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.path)
                }
                // The screen tests drive a real vault: the Rust core built for this computer.
                it.systemProperty("jna.library.path", rootDir.resolve("../core/target/release").canonicalPath)
                it.dependsOn(":core-bindings:buildHostCore")
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {
    implementation(project(":core-bindings"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // The card scanner (ADR-009): CameraX for the frames, ML Kit's text recognition with the model
    // inside the app. Only the preview and frame analysis are used; nothing is captured or saved.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.fragment:fragment:1.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.java.dev.jna:jna:5.19.1")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
