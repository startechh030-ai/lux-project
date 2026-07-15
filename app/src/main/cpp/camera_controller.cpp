#include <jni.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <mutex>

namespace {
struct CameraController {
    float yaw=0, pitch=0, distance=3.8f;
    float dyaw=0, dpitch=0, ddistance=3.8f;
    float tx=0, ty=0, tz=0, dtx=0, dty=0, dtz=0;
    float width=1, height=1;
    double lastTime=0;
    std::mutex mutex;

    void reset() {
        yaw=dyaw=0; pitch=dpitch=0; distance=ddistance=3.8f;
        tx=dtx=ty=dty=tz=dtz=0; lastTime=0;
    }
    void setPivot(float x,float y,float z) { dtx=x; dty=y; dtz=z; }
    void orbit(float dx,float dy) {
        dyaw -= dx/std::max(width,1.0f)*2.35f;
        dpitch=std::clamp(dpitch-dy/std::max(height,1.0f)*2.15f,-1.48f,1.48f);
    }
    void zoom(float scale) {
        if (std::isfinite(scale)&&scale>0.01f) ddistance=std::clamp(ddistance/scale,0.35f,16.0f);
    }
    void pan(float dx,float dy) {
        float u=ddistance*1.45f/std::max(height,1.0f);
        float sy=std::sin(dyaw), cy=std::cos(dyaw), sp=std::sin(dpitch), cp=std::cos(dpitch);
        float rx=cy, rz=-sy, ux=-sp*sy, uy=cp, uz=-sp*cy;
        dtx += (-dx*rx+dy*ux)*u; dty += dy*uy*u; dtz += (-dx*rz+dy*uz)*u;
    }
    void update(double now) {
        float dt=lastTime==0?1.f/60.f:(float)std::clamp(now-lastTime,0.0,0.05); lastTime=now;
        float a=1-std::exp(-11.f*dt), pa=1-std::exp(-8.f*dt);
        yaw+=(dyaw-yaw)*a; pitch+=(dpitch-pitch)*a; distance+=(ddistance-distance)*a;
        tx+=(dtx-tx)*pa; ty+=(dty-ty)*pa; tz+=(dtz-tz)*pa;
    }
    std::array<float,9> pose() const {
        float cp=std::cos(pitch),sp=std::sin(pitch),sy=std::sin(yaw),cy=std::cos(yaw);
        return {tx+distance*cp*sy,ty+distance*sp,tz+distance*cp*cy,tx,ty,tz,0,1,0};
    }
} c;
}
#define LOCK std::lock_guard<std::mutex> l(c.mutex)
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeSetViewport(JNIEnv*,jobject,jint w,jint h){LOCK;c.width=w;c.height=h;}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeOrbit(JNIEnv*,jobject,jfloat x,jfloat y){LOCK;c.orbit(x,y);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeZoom(JNIEnv*,jobject,jfloat s){LOCK;c.zoom(s);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativePan(JNIEnv*,jobject,jfloat x,jfloat y){LOCK;c.pan(x,y);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeSetPivot(JNIEnv*,jobject,jfloat x,jfloat y,jfloat z){LOCK;c.setPivot(x,y,z);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeReset(JNIEnv*,jobject){LOCK;c.reset();}
extern "C" JNIEXPORT jfloatArray JNICALL Java_luxe_texture3d_app_NativeCamera_nativeUpdate(JNIEnv* e,jobject,jdouble t){LOCK;c.update(t);auto p=c.pose();auto a=e->NewFloatArray(9);e->SetFloatArrayRegion(a,0,9,p.data());return a;}
