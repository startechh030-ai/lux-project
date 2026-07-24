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
        versionCode = 38
        versionName = "0.15.0"
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.filament:filament-android:1.69.4")
    implementation("com.google.android.filament:gltfio-android:1.69.4")
    implementation("com.google.android.filament:filament-utils-android:1.69.4")
}
