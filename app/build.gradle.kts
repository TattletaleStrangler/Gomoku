plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 9
        versionName = "1.8"

        // The native Rapfi engine is provided only for arm64-v8a (see TZ §7).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
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

    // JVM unit tests for the built-in engine (it has no Android dependencies).
    testImplementation("junit:junit:4.13.2")
}
