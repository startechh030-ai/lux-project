# Filament Java wrappers call native methods through JNI and must retain names.
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.gltfio.** { *; }
-keep class com.google.android.filament.utils.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Activity classes referenced by AndroidManifest.xml.
-keep class luxe.texture3d.app.MainActivity { *; }
-keep class luxe.texture3d.app.EditorActivity { *; }

-dontwarn com.google.android.filament.**
-dontwarn com.google.android.filament.gltfio.**
