plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "luxe.texture3d.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "luxe.texture3d.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.4.2"
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-O2", "-ffast-math") }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildFeatures { viewBinding = false }
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.filament:filament-android:1.69.4")
    implementation("com.google.android.filament:gltfio-android:1.69.4")
    implementation("com.google.android.filament:filament-utils-android:1.69.4")
}
