plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.omninode.hub"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.omninode.hub"
        minSdk = 31           // Android 12+ — required for BLE Mesh, Wi-Fi Direct APIs, and Matter
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native ABI filters — arm64-v8a is mandatory for Hexagon NPU / QNN delegate
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Inject build-time constants for runtime feature flags
        // NOTE: homeassistant.local (mDNS) removed — causes UnknownHostException on
        // networks without a running HA instance. Use a numeric IP or configure via
        // Settings → Home Assistant Host field at runtime.
        buildConfigField("String", "HA_HOST",   "\"192.168.1.100\"")
        buildConfigField("String", "HA_PORT",   "\"8123\"")
        buildConfigField("String", "HA_WS_URL", "\"ws://192.168.1.100:8123/api/websocket\"")
        buildConfigField("String", "APP_VERSION", "\"1.0.0\"")
        buildConfigField("Boolean", "ENABLE_NPU", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "ENABLE_NPU", "true")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            buildConfigField("Boolean", "ENABLE_NPU", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ── Packaging ────────────────────────────────────────────────────────────
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        // Keep the Qualcomm QNN & LiteRT shared libraries for arm64-v8a
        jniLibs {
            keepDebugSymbols += "**/*.so"
            useLegacyPackaging = false
        }
    }

    // ── Lint ─────────────────────────────────────────────────────────────────
    lint {
        abortOnError = false
        warningsAsErrors = false
        htmlReport = true
    }
}

dependencies {
    // ── AndroidX Core ────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    // ── Jetpack Compose BOM + UI ──────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    // ── Hilt DI ──────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // ──────────────────────────────────────────────────────────────────────────
    //  NETWORKING
    //  OkHttp 4.12.0: HTTP/2 client + native WebSocket support
    //  Used for: Home Assistant WebSocket API (bidirectional real-time telemetry)
    // ──────────────────────────────────────────────────────────────────────────
    implementation(libs.bundles.networking)

    // ── Coroutines ───────────────────────────────────────────────────────────
    implementation(libs.bundles.coroutines)

    // ── DataStore (persistent key-value settings) ─────────────────────────────
    implementation(libs.androidx.datastore.preferences)

    // ── Room (local IoT state cache) ──────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ──────────────────────────────────────────────────────────────────────────
    //  MATTER / GOOGLE HOME APIS
    //  com.google.android.gms:play-services-home:16.0.0-beta01
    //  Provides: Matter device commissioning, structure/room/device/trait APIs
    //  Used for: Local Matter device discovery and real-time trait control
    // ──────────────────────────────────────────────────────────────────────────
    implementation(libs.play.services.home)

    // ──────────────────────────────────────────────────────────────────────────
    //  AI / ML — LiteRT + QNN Delegate (Qualcomm Hexagon NPU)
    //
    //  Execution priority chain:
    //    1. QNN delegate → Hexagon NPU (INT8/INT16 quantized AOT .litertlm)
    //    2. GPU delegate → Adreno 840 via OpenCL (FP16 fallback)
    //    3. XNNPack      → Oryon CPU (last resort)
    //
    //  IMPORTANT: The Qualcomm QNN delegate .aar (litert-qnn-<version>.aar) must
    //  be downloaded from the Qualcomm AI Engine Direct SDK:
    //    https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk
    //  and placed in app/libs/ before building. The dependency below uses a local
    //  file dependency until Qualcomm publishes to Maven Central.
    // ──────────────────────────────────────────────────────────────────────────
    // LiteRT core runtime
    implementation(libs.litert.core)
    // LiteRT GPU delegate (Adreno OpenCL fallback path)
    implementation(libs.litert.gpu.delegate)
    // QNN delegate — Qualcomm Hexagon NPU (primary inference provider)
    // The .aar ships with libQnnHtp.so and libQnnHtpV68Stub.so for SM8850
    // Place the downloaded .aar in app/libs/ from the QAIRT SDK:
    //   https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // ── CameraX (FastVLM visual input) ────────────────────────────────────────
    implementation(libs.bundles.camerax)

    // ── Permissions helper ────────────────────────────────────────────────────
    implementation(libs.accompanist.permissions)

    // ── Image loading ─────────────────────────────────────────────────────────
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ── Logging ───────────────────────────────────────────────────────────────
    implementation(libs.timber)

    // ── Picovoice Porcupine (Wake word) ───────────────────────────────────────
    implementation(libs.picovoice.porcupine)

    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
