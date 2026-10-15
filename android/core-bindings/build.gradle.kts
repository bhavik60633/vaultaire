// The Rust core (vault-core, through the vault-ffi crate) and its Kotlin face, VaultEngine.
// uniffi/vault_ffi/vault_ffi.kt is generated (see core/uniffi-bindgen); CI checks it is current.
plugins {
    id("com.android.library")
}

val coreDir = rootDir.resolve("../core")

android {
    namespace = "dev.vaultaire.core"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            // The JVM unit tests call the real Rust core, built for this computer.
            it.systemProperty("jna.library.path", coreDir.resolve("target/release").canonicalPath)
            it.dependsOn("buildHostCore")
        }
    }
}

// Builds libvault_ffi for the computer running the unit tests (not for the phone).
tasks.register<Exec>("buildHostCore") {
    workingDir = coreDir
    commandLine("cargo", "build", "-p", "vault-ffi", "--release", "--locked")
}

dependencies {
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    testImplementation("junit:junit:4.13.2")
    // The desktop JNA jar carries JNA's own native part for the computer running the tests.
    testImplementation("net.java.dev.jna:jna:5.19.1")
}
