#include <jni.h>
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "NativeCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct Vec3 {
    float x, y, z;
};

static Vec3 add(Vec3 a, Vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
static Vec3 sub(Vec3 a, Vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
static Vec3 mul(Vec3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
static float dot(Vec3 a, Vec3 b) { return a.x*b.x + a.y*b.y + a.z*b.z; }
static Vec3 cross(Vec3 a, Vec3 b) {
    return {a.y*b.z - a.z*b.y, a.z*b.x - a.x*b.z, a.x*b.y - a.y*b.x};
}
static Vec3 normalize(Vec3 v) {
    float len = std::sqrt(std::max(0.0000001f, dot(v, v)));
    return {v.x / len, v.y / len, v.z / len};
}
static float lerp(float a, float b, float t) { return a + (b - a) * t; }
static Vec3 lerp(Vec3 a, Vec3 b, float t) { return {lerp(a.x,b.x,t), lerp(a.y,b.y,t), lerp(a.z,b.z,t)}; }

struct CameraState {
    Vec3 target {0, 0, 0};
    float yaw = 0.0f;
    float pitch = 0.25f;
    float distance = 3.0f;
};

static CameraState g_current;
static CameraState g_target;
static float g_minDistance = 0.45f;
static float g_maxDistance = 80.0f;
static float g_minPitch = -1.50f;
static float g_maxPitch = 1.50f;
static float g_sensitivity = 0.0065f;
static float g_panSpeed = 0.0022f;
static float g_smoothness = 18.0f;
static float g_lastX = 0.0f;
static float g_lastY = 0.0f;
static int g_lastPointers = 0;
static int g_screenW = 1;
static int g_screenH = 1;
static bool g_autoRotate = false;
static int g_mode = 0; // 0 orbit, 1 turntable

static Vec3 eyeFromState(const CameraState& s) {
    float cp = std::cos(s.pitch);
    return {
        s.target.x + s.distance * cp * std::sin(s.yaw),
        s.target.y + s.distance * std::sin(s.pitch),
        s.target.z + s.distance * cp * std::cos(s.yaw)
    };
}

static void resetInternal(float cx, float cy, float cz, float radius) {
    float r = std::max(0.25f, radius);
    g_target.target = {cx, cy, cz};
    g_target.yaw = 0.0f;
    g_target.pitch = 0.25f;
    g_target.distance = std::clamp(r * 3.2f, g_minDistance, g_maxDistance);
    g_current = g_target;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_init(JNIEnv*, jobject) {
    resetInternal(0, 0, 0, 1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_destroy(JNIEnv*, jobject) {
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_setScreenSize(JNIEnv*, jobject, jint w, jint h) {
    g_screenW = std::max(1, (int) w);
    g_screenH = std::max(1, (int) h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_reset(JNIEnv*, jobject, jfloat cx, jfloat cy, jfloat cz, jfloat radius) {
    resetInternal(cx, cy, cz, radius);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_resetToDefault(JNIEnv*, jobject) {
    resetInternal(0, 0, 0, 1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_onTouchStart(JNIEnv*, jobject, jfloat x, jfloat y, jint pointers) {
    g_lastX = x;
    g_lastY = y;
    g_lastPointers = pointers;
    g_autoRotate = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_onTouchMove(JNIEnv*, jobject, jfloat x, jfloat y, jint pointers) {
    if (g_lastPointers != pointers) {
        g_lastX = x;
        g_lastY = y;
        g_lastPointers = pointers;
        return;
    }

    float dx = x - g_lastX;
    float dy = y - g_lastY;
    g_lastX = x;
    g_lastY = y;

    if (pointers <= 1) {
        g_target.yaw -= dx * g_sensitivity;
        if (g_mode == 0) {
            g_target.pitch += dy * g_sensitivity;
            g_target.pitch = std::clamp(g_target.pitch, g_minPitch, g_maxPitch);
        }
    } else {
        Vec3 eye = eyeFromState(g_current);
        Vec3 forward = normalize(sub(g_current.target, eye));
        Vec3 worldUp {0, 1, 0};
        Vec3 right = normalize(cross(forward, worldUp));
        Vec3 up = normalize(cross(right, forward));

        float panFactor = g_current.distance * g_panSpeed;
        Vec3 pan = add(mul(right, -dx * panFactor), mul(up, dy * panFactor));
        g_target.target = add(g_target.target, pan);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_onTouchEnd(JNIEnv*, jobject) {
    g_lastPointers = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_onPinch(JNIEnv*, jobject, jfloat scale) {
    if (scale <= 0.0001f) return;
    g_target.distance = std::clamp(g_target.distance / scale, g_minDistance, g_maxDistance);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_update(JNIEnv*, jobject, jfloat dt) {
    float delta = std::clamp((float) dt, 0.0f, 0.1f);
    if (g_autoRotate) {
        g_target.yaw += delta * 0.35f;
    }
    float t = 1.0f - std::exp(-g_smoothness * delta);
    g_current.target = lerp(g_current.target, g_target.target, t);
    g_current.yaw = lerp(g_current.yaw, g_target.yaw, t);
    g_current.pitch = lerp(g_current.pitch, g_target.pitch, t);
    g_current.distance = lerp(g_current.distance, g_target.distance, t);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_getCameraState(JNIEnv* env, jobject) {
    Vec3 eye = eyeFromState(g_current);
    Vec3 forward = normalize(sub(g_current.target, eye));
    Vec3 worldUp {0, 1, 0};
    Vec3 right = normalize(cross(forward, worldUp));
    Vec3 up = normalize(cross(right, forward));

    float values[12] = {
        eye.x, eye.y, eye.z,
        g_current.target.x, g_current.target.y, g_current.target.z,
        up.x, up.y, up.z,
        g_current.yaw, g_current.pitch, g_current.distance
    };
    jfloatArray arr = env->NewFloatArray(12);
    env->SetFloatArrayRegion(arr, 0, 12, values);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_setMode(JNIEnv*, jobject, jint mode) {
    g_mode = mode;
    if (g_mode == 1) g_target.pitch = 0.25f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_setAutoRotate(JNIEnv*, jobject, jboolean enabled) {
    g_autoRotate = enabled;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_toggleAutoRotate(JNIEnv*, jobject) {
    g_autoRotate = !g_autoRotate;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_isAutoRotating(JNIEnv*, jobject) {
    return g_autoRotate ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_setSensitivity(JNIEnv*, jobject, jfloat sensitivity) {
    g_sensitivity = std::clamp((float) sensitivity, 0.001f, 0.05f);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_setDamping(JNIEnv*, jobject, jfloat damping) {
    // Public value is easier as 1..30, higher = snappier.
    g_smoothness = std::clamp((float) damping, 1.0f, 30.0f);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_getYaw(JNIEnv*, jobject) { return g_current.yaw; }

extern "C" JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_getPitch(JNIEnv*, jobject) { return g_current.pitch; }

extern "C" JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_getDistance(JNIEnv*, jobject) { return g_current.distance; }
