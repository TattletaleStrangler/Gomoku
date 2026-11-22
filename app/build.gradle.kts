plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.oldmermaidman.gomoku"
    // compileSdk 37 = installed platform (android-37.0); targetSdk 36 is what
    // Google Play requires for new apps and updates in 2026.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.oldmermaidman.gomoku"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.13"

        // The native Rapfi engine is provided only for arm64-v8a (see TZ §7).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // 10.0.2.2 is the host machine as seen from the Android emulator. A phone
            // on USB reaches the host through `adb reverse tcp:8080 tcp:8080` and then
            // needs 127.0.0.1; override without touching the source:
            //   ./gradlew assembleDebug -Pgomoku.apiBaseUrl=http://127.0.0.1:8080
            val debugApi = (project.findProperty("gomoku.apiBaseUrl") as String?)
                ?: "http://10.0.2.2:8080"
            buildConfigField("String", "API_BASE_URL", "\"$debugApi\"")
        }
        release {
            // Placeholder until the deployment domain exists (see USER_ACTION.md and
            // README.md, "Online play"). Release builds only speak HTTPS: cleartext is
            // allowed in src/debug/AndroidManifest.xml and nowhere else.
            buildConfigField("String", "API_BASE_URL", "\"https://api.gomoku.invalid\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // Crash reports carry the version of the build they came from.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The network layer logs through android.util.Log. On the JVM that class is
            // a stub, so without this flag every logged failure would throw instead of
            // reaching the assertion the test is about.
            isReturnDefaultValues = true
        }
    }

    lint {
        // "A newer version is available" is not a defect: the pinned versions are the
        // ones this project was built and tested against (see README.md, §16).
        disable += "NewerVersionAvailable"
    }

    // Force native libs (incl. the shipped Rapfi binary, renamed to librapfi.so)
    // to be extracted to nativeLibraryDir, the only place Android 10+ allows exec().
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Kotlin compiler options. The old `android { kotlinOptions { … } }` DSL is
// deprecated and warns on every build, so the compilerOptions DSL is used.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Network layer (TZ §11.2): one OkHttp client for REST and the WebSocket, and
    // kotlinx.serialization for the DTOs — no reflection, so the DTOs stay testable.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // JVM unit tests for the built-in engine (it has no Android dependencies).
    testImplementation("junit:junit:4.13.2")
    // MockWebServer drives the REST client and the socket against a local server.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
