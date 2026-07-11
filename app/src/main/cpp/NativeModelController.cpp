#include <jni.h>
#include <cmath>
#include <algorithm>

struct ModelState {
    float offsetPxX = 0.0f;
    float offsetPxY = 0.0f;
    float targetOffsetPxX = 0.0f;
    float targetOffsetPxY = 0.0f;
    float scale = 1.25f;
    int viewportW = 1;
    int viewportH = 1;
    float lastX = 0.0f;
    float lastY = 0.0f;
    bool dragging = false;
};

static ModelState g;

static float clampf(float v, float lo, float hi) {
    return std::max(lo, std::min(hi, v));
}

static void buildMatrix(float* out) {
    // Convert screen-pixel offset into world offset for fixed camera at z=2.
    // This gives stable left/right/up/down model motion without moving camera.
    const float worldPerPixel = 2.0f / static_cast<float>(std::max(1, g.viewportH));
    const float tx = g.offsetPxX * worldPerPixel;
    const float ty = -g.offsetPxY * worldPerPixel;
    const float s = g.scale;

    // Column-major: scale + translation only. No rotation, no camera movement.
    out[0] = s;    out[1] = 0.0f; out[2] = 0.0f; out[3] = 0.0f;
    out[4] = 0.0f; out[5] = s;    out[6] = 0.0f; out[7] = 0.0f;
    out[8] = 0.0f; out[9] = 0.0f; out[10] = s;   out[11] = 0.0f;
    out[12] = tx;  out[13] = ty;   out[14] = 0.0f; out[15] = 1.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeReset(JNIEnv*, jobject) {
    g.offsetPxX = 0.0f;
    g.offsetPxY = 0.0f;
    g.targetOffsetPxX = 0.0f;
    g.targetOffsetPxY = 0.0f;
    g.scale = 1.25f;
    g.dragging = false;
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeSetViewport(JNIEnv*, jobject, jint w, jint h) {
    g.viewportW = std::max(1, static_cast<int>(w));
    g.viewportH = std::max(1, static_cast<int>(h));
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeTouchDown(JNIEnv*, jobject, jfloat x, jfloat y) {
    g.lastX = x;
    g.lastY = y;
    g.dragging = true;
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeTouchMove(JNIEnv*, jobject, jfloat x, jfloat y) {
    if (!g.dragging) {
        g.lastX = x;
        g.lastY = y;
        g.dragging = true;
        return;
    }

    const float dx = x - g.lastX;
    const float dy = y - g.lastY;
    g.lastX = x;
    g.lastY = y;

    // Move model exactly in swipe direction. Clamp so it cannot disappear fully.
    const float maxX = g.viewportW * 0.38f;
    const float maxY = g.viewportH * 0.34f;
    g.targetOffsetPxX = clampf(g.targetOffsetPxX + dx, -maxX, maxX);
    g.targetOffsetPxY = clampf(g.targetOffsetPxY + dy, -maxY, maxY);
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeTouchUp(JNIEnv*, jobject) {
    g.dragging = false;
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeDoubleTap(JNIEnv*, jobject) {
    g.targetOffsetPxX = 0.0f;
    g.targetOffsetPxY = 0.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeSetScale(JNIEnv*, jobject, jfloat scale) {
    g.scale = clampf(scale, 0.25f, 5.0f);
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_NativeModel_nativeUpdate(JNIEnv*, jobject, jfloat dt) {
    const float delta = clampf(dt, 0.0f, 0.1f);
    const float t = 1.0f - std::exp(-18.0f * delta);
    g.offsetPxX += (g.targetOffsetPxX - g.offsetPxX) * t;
    g.offsetPxY += (g.targetOffsetPxY - g.offsetPxY) * t;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_luxe_texture3d_app_NativeModel_nativeGetMatrix(JNIEnv* env, jobject) {
    float m[16];
    buildMatrix(m);
    jfloatArray arr = env->NewFloatArray(16);
    env->SetFloatArrayRegion(arr, 0, 16, m);
    return arr;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_luxe_texture3d_app_NativeModel_nativeGetPivotScreenOffset(JNIEnv* env, jobject) {
    float values[2] = { g.offsetPxX, g.offsetPxY };
    jfloatArray arr = env->NewFloatArray(2);
    env->SetFloatArrayRegion(arr, 0, 2, values);
    return arr;
}
