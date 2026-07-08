plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arena.simpleglbviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arena.simpleglbviewer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    val filamentVersion = "1.72.0"
    implementation("com.google.android.filament:filament-android:$filamentVersion")
    implementation("com.google.android.filament:gltfio-android:$filamentVersion")
    implementation("com.google.android.filament:filament-utils-android:$filamentVersion")
}
