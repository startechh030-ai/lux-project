#include "platform/jni_bridge.h"
#include "game/game_framework.h"
#include "game/mini_game.h"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>

#define LOG_TAG "LuxJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace lux;

// ──────────────────────────────────────────────────────────────────────────
//  Helper: extract GameFramework* from jlong
// ──────────────────────────────────────────────────────────────────────────
static GameFramework* getFramework(jlong ptr) {
    return reinterpret_cast<GameFramework*>(static_cast<intptr_t>(ptr));
}

// ──────────────────────────────────────────────────────────────────────────
//  Engine lifecycle
// ──────────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_lux_engine_GameActivity_nativeCreateEngine(
    JNIEnv* env, jobject /*thiz*/,
    jobject androidAssetManager,
    jint screenWidth, jint screenHeight) {

    // Get AAssetManager from Java object
    AAssetManager* aam = AAssetManager_fromJava(env, androidAssetManager);
    if (!aam) {
        LOGE("Failed to get AAssetManager");
        return 0;
    }

    auto* framework = new GameFramework();
    bool ok = framework->initialize(
        nullptr,        // native window — will be set via onSurfaceCreated
        aam,
        screenWidth,
        screenHeight
    );

    if (!ok) {
        LOGE("Engine initialization failed");
        delete framework;
        return 0;
    }

    jlong ptr = static_cast<jlong>(reinterpret_cast<intptr_t>(framework));
    LOGI("Engine created: ptr=%lld", (long long)ptr);
    return ptr;
}

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeDestroyEngine(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {

    auto* framework = getFramework(enginePtr);
    if (framework) {
        delete framework;
        LOGI("Engine destroyed");
    }
}

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnSurfaceCreated(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jobject surface) {

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("Failed to get native window from surface");
        return;
    }

    auto* framework = getFramework(enginePtr);
    if (framework && framework->renderer()) {
        RendererConfig config;
        config.nativeWindow = window;
        config.width = ANativeWindow_getWidth(window);
        config.height = ANativeWindow_getHeight(window);
        framework->renderer()->initialize(config);
        LOGI("Surface created: %dx%d",
             config.width, config.height);
    }
}

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnSurfaceChanged(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint width, jint height) {

    auto* framework = getFramework(enginePtr);
    if (framework && framework->renderer()) {
        framework->renderer()->resize(width, height);
    }
}

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnSurfaceDestroyed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {

    auto* framework = getFramework(enginePtr);
    if (framework && framework->renderer()) {
        framework->renderer()->shutdown();
        LOGI("Surface destroyed");
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Frame loop
// ──────────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeTick(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jfloat dt) {

    auto* framework = getFramework(enginePtr);
    if (framework) {
        framework->tick(dt);
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Game management
// ──────────────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_lux_engine_GameActivity_nativeStartGame(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jstring gameId) {

    auto* framework = getFramework(enginePtr);
    if (!framework) return JNI_FALSE;

    const char* idChars = env->GetStringUTFChars(gameId, nullptr);
    std::string id(idChars);
    env->ReleaseStringUTFChars(gameId, idChars);

    bool result = framework->loadAndStartGame(id);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeStopGame(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {

    auto* framework = getFramework(enginePtr);
    if (framework) {
        framework->stopCurrentGame();
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Input
// ──────────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_lux_engine_GameActivity_nativeOnTouch(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint pointerId, jfloat x, jfloat y, jboolean pressed) {

    auto* framework = getFramework(enginePtr);
    if (framework) {
        framework->onTouchEvent(pointerId, x, y, pressed == JNI_TRUE);
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Game list (for MainMenuActivity)
// ──────────────────────────────────────────────────────────────────────────

JNIEXPORT jobjectArray JNICALL
Java_com_lux_engine_MainMenuActivity_nativeGetGameList(
    JNIEnv* env, jobject /*thiz*/) {

    auto gameIds = MiniGameRegistry::instance().availableGames();

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(gameIds.size()), stringClass, nullptr);

    for (size_t i = 0; i < gameIds.size(); ++i) {
        jstring id = env->NewStringUTF(gameIds[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), id);
        env->DeleteLocalRef(id);
    }

    return result;
}
