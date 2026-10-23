import java.time.Instant

plugins {
    id("com.android.application")
}

// Dev builds stream logs to Supabase. The publishable key can only call the
// insert-only ingest function; it cannot read anything.
val logUrl = "https://ypxqrslfrooghucrndfr.supabase.co/rest/v1/rpc/curiobot_ingest"
val logKey = "sb_publishable_m1apUMR8DhPfOsshSQe3dw_ZennT-Ve"

android {
    namespace = "dev.curiobot"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.curiobot"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "LOG_URL", "\"$logUrl\"")
        buildConfigField("String", "LOG_KEY", "\"$logKey\"")
        buildConfigField("String", "BUILD_TIME", "\"${Instant.now()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A throwaway dev key kept with the code so every build installs over the
        // last one (and the creature survives updates). Never use it for a store.
        create("dev") {
            storeFile = rootProject.file("keys/dev.keystore")
            storePassword = "curiobot-dev"
            keyAlias = "curiobot"
            keyPassword = "curiobot-dev"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("dev")
            buildConfigField("boolean", "DEV_LOGGING", "true")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
            buildConfigField("boolean", "DEV_LOGGING", "false")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources { noCompress += listOf("onnx", "bin") }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
