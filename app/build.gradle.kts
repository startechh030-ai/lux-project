plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "luxe.texture3d.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "luxe.texture3d.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 57
        versionName = "0.27.0"
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-O2", "-fexceptions", "-frtti") }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    val releaseKeystorePath = System.getenv("LUXE_KEYSTORE_FILE")
    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("LUXE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LUXE_KEY_ALIAS")
                keyPassword = System.getenv("LUXE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.filament:filament-android:1.69.4")
    implementation("com.google.android.filament:gltfio-android:1.69.4")
    implementation("com.google.android.filament:filament-utils-android:1.69.4")
}
