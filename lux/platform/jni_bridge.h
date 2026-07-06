#ifndef LUX_PLATFORM_JNI_BRIDGE_H
#define LUX_PLATFORM_JNI_BRIDGE_H

#include <jni.h>

/// JNI entry points for the Lux engine.
/// These are called from Kotlin/Java via System.loadLibrary("lux_shared").
///
/// Naming convention: Java_com_lux_engine_<Activity>_<method>
extern "C" {

/// ── Engine lifecycle ─────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_lux_engine_GameActivity_nativeCreateEngine(
    JNIEnv* env, jobject thiz,
    jobject androidAssetManager,
    jint screenWidth, jint screenHeight);

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeDestroyEngine(
    JNIEnv* env, jobject thiz, jlong enginePtr);

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnSurfaceCreated(
    JNIEnv* env, jobject thiz, jlong enginePtr, jobject surface);

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnSurfaceChanged(
    JNIEnv* env, jobject thiz, jlong enginePtr,
    jint width, jint height);

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnSurfaceDestroyed(
    JNIEnv* env, jobject thiz, jlong enginePtr);

/// ── Frame loop ───────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeTick(
    JNIEnv* env, jobject thiz, jlong enginePtr, jfloat dt);

/// ── Game management ──────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_lux_engine_GameActivity_nativeStartGame(
    JNIEnv* env, jobject thiz, jlong enginePtr, jstring gameId);

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeStopGame(
    JNIEnv* env, jobject thiz, jlong enginePtr);

/// ── Input ────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnTouch(
    JNIEnv* env, jobject thiz, jlong enginePtr,
    jint pointerId, jfloat x, jfloat y, jboolean pressed);

/// ── Game info ────────────────────────────────────────────────────────

JNIEXPORT jobjectArray JNICALL
Java_com_lux_engine_MainMenuActivity_nativeGetGameList(
    JNIEnv* env, jobject thiz);

} // extern "C"

#endif // LUX_PLATFORM_JNI_BRIDGE_H
