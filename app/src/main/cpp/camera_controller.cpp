#include <jni.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <mutex>

namespace {
struct CameraController {
    float yaw = 0.0f, pitch = 0.12f, distance = 3.2f;
    float targetYaw = 0.0f, targetPitch = 0.12f, targetDistance = 3.2f;
    float panX = 0.0f, panY = 0.0f, targetPanX = 0.0f, targetPanY = 0.0f;
    float width = 1.0f, height = 1.0f;
    double lastTime = 0.0;
    std::mutex mutex;

    void reset() {
        yaw = targetYaw = 0.0f; pitch = targetPitch = 0.12f;
        distance = targetDistance = 3.2f;
        panX = targetPanX = panY = targetPanY = 0.0f;
    }
    void orbit(float dx, float dy) {
        targetYaw -= dx / std::max(width, 1.0f) * 4.2f;
        targetPitch = std::clamp(targetPitch - dy / std::max(height, 1.0f) * 3.2f, -1.48f, 1.48f);
    }
    void zoom(float scale) {
        if (std::isfinite(scale) && scale > 0.01f)
            targetDistance = std::clamp(targetDistance / scale, 1.15f, 12.0f);
    }
    void pan(float dx, float dy) {
        const float units = targetDistance * 1.45f / std::max(height, 1.0f);
        targetPanX -= dx * units; targetPanY += dy * units;
        targetPanX = std::clamp(targetPanX, -4.0f, 4.0f);
        targetPanY = std::clamp(targetPanY, -4.0f, 4.0f);
    }
    void update(double now) {
        float dt = lastTime == 0.0 ? 1.0f / 60.0f : (float)std::clamp(now-lastTime, 0.0, 0.05);
        lastTime = now;
        const float a = 1.0f - std::exp(-14.0f * dt);
        yaw += (targetYaw-yaw)*a; pitch += (targetPitch-pitch)*a;
        distance += (targetDistance-distance)*a;
        panX += (targetPanX-panX)*a; panY += (targetPanY-panY)*a;
    }
    std::array<float, 6> pose() const {
        const float cp=std::cos(pitch), sp=std::sin(pitch), sy=std::sin(yaw), cy=std::cos(yaw);
        float tx=panX, ty=panY, tz=0.0f;
        return {tx + distance*cp*sy, ty + distance*sp, tz + distance*cp*cy, tx, ty, tz};
    }
} camera;
}

extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeSetViewport(JNIEnv*, jobject, jint w, jint h) { std::lock_guard<std::mutex> l(camera.mutex); camera.width=(float)w; camera.height=(float)h; }
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeOrbit(JNIEnv*, jobject, jfloat dx, jfloat dy) { std::lock_guard<std::mutex> l(camera.mutex); camera.orbit(dx,dy); }
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeZoom(JNIEnv*, jobject, jfloat s) { std::lock_guard<std::mutex> l(camera.mutex); camera.zoom(s); }
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativePan(JNIEnv*, jobject, jfloat dx, jfloat dy) { std::lock_guard<std::mutex> l(camera.mutex); camera.pan(dx,dy); }
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeReset(JNIEnv*, jobject) { std::lock_guard<std::mutex> l(camera.mutex); camera.reset(); camera.lastTime=0; }
extern "C" JNIEXPORT jfloatArray JNICALL Java_luxe_texture3d_app_NativeCamera_nativeUpdate(JNIEnv* env, jobject, jdouble t) { std::lock_guard<std::mutex> l(camera.mutex); camera.update(t); auto p=camera.pose(); jfloatArray out=env->NewFloatArray(6); env->SetFloatArrayRegion(out,0,6,p.data()); return out; }
