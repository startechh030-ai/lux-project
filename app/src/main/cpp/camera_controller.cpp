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
    float orbitBaseYaw=0, orbitBasePitch=0;
    float pendingX=0, pendingY=0, pendingZ=0;
    bool pendingPivot=false, orbitActive=false;
    double lastTime=0;
    std::mutex mutex;

    void reset() {
        yaw=dyaw=0; pitch=dpitch=0; distance=ddistance=3.8f;
        tx=dtx=ty=dty=tz=dtz=0;
        pendingPivot=false; orbitActive=false; lastTime=0;
    }
    void setPivot(float x,float y,float z) {
        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) x=y=z=0;
        x=std::clamp(x,-1.25f,1.25f); y=std::clamp(y,-1.25f,1.25f); z=std::clamp(z,-1.25f,1.25f);
        const float length=std::sqrt(x*x+y*y+z*z);
        if (length>1.9f) { const float s=1.9f/length; x*=s; y*=s; z*=s; }

        // Rebase both current and desired spherical coordinates around the new
        // pivot while preserving their world-space eye positions. Changing
        // focus therefore causes no visible translation, zoom, or camera jump.
        auto rebase=[](float ex,float ey,float ez,float px,float py,float pz,
                       float& outYaw,float& outPitch,float& outDistance) {
            const float vx=ex-px, vy=ey-py, vz=ez-pz;
            outDistance=std::clamp(std::sqrt(vx*vx+vy*vy+vz*vz),0.35f,16.0f);
            outYaw=std::atan2(vx,vz);
            outPitch=std::asin(std::clamp(vy/outDistance,-1.0f,1.0f));
        };
        const float ccp=std::cos(pitch), csp=std::sin(pitch);
        const float csy=std::sin(yaw), ccy=std::cos(yaw);
        const float currentEyeX=tx+distance*ccp*csy;
        const float currentEyeY=ty+distance*csp;
        const float currentEyeZ=tz+distance*ccp*ccy;
        const float dcp=std::cos(dpitch), dsp=std::sin(dpitch);
        const float dsy=std::sin(dyaw), dcy=std::cos(dyaw);
        const float desiredEyeX=dtx+ddistance*dcp*dsy;
        const float desiredEyeY=dty+ddistance*dsp;
        const float desiredEyeZ=dtz+ddistance*dcp*dcy;
        tx=x; ty=y; tz=z; dtx=x; dty=y; dtz=z;
        rebase(currentEyeX,currentEyeY,currentEyeZ,x,y,z,yaw,pitch,distance);
        rebase(desiredEyeX,desiredEyeY,desiredEyeZ,x,y,z,dyaw,dpitch,ddistance);
    }
    void queuePivot(float x,float y,float z) {
        pendingX=x; pendingY=y; pendingZ=z; pendingPivot=true;
    }
    void beginOrbit() {
        // A GPU pick from the previous stroke is committed only at this clean
        // gesture boundary. The active pivot never changes mid-stroke.
        if (pendingPivot) {
            setPivot(pendingX,pendingY,pendingZ);
            pendingPivot=false;
        }
        orbitBaseYaw=dyaw; orbitBasePitch=dpitch; orbitActive=true;
    }
    void orbitTo(float totalDx,float totalDy) {
        if (!orbitActive) return;
        // Gesture-relative orientation: final pose depends on total drag from
        // ACTION_DOWN, not the number or timing of MotionEvent samples.
        dyaw=orbitBaseYaw+totalDx/std::max(width,1.0f)*2.35f;
        dpitch=std::clamp(orbitBasePitch+totalDy/std::max(height,1.0f)*2.15f,-1.48f,1.48f);
    }
    void endOrbit() { orbitActive=false; }
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
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeBeginOrbit(JNIEnv*,jobject){LOCK;c.beginOrbit();}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeOrbitTo(JNIEnv*,jobject,jfloat x,jfloat y){LOCK;c.orbitTo(x,y);}
// Compatibility JNI for obsolete CameraSurfaceView files. The current app
// never calls this method; it uses beginOrbit + orbitTo gesture snapshots.
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeOrbit(JNIEnv*,jobject,jfloat x,jfloat y){
    LOCK; c.dyaw+=x/std::max(c.width,1.0f)*2.35f;
    c.dpitch=std::clamp(c.dpitch+y/std::max(c.height,1.0f)*2.15f,-1.48f,1.48f);
}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeEndOrbit(JNIEnv*,jobject){LOCK;c.endOrbit();}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeZoom(JNIEnv*,jobject,jfloat s){LOCK;c.zoom(s);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativePan(JNIEnv*,jobject,jfloat x,jfloat y){LOCK;c.pan(x,y);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeQueuePivot(JNIEnv*,jobject,jfloat x,jfloat y,jfloat z){LOCK;c.queuePivot(x,y,z);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeReset(JNIEnv*,jobject){LOCK;c.reset();}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeUpdate(JNIEnv* e,jobject,jdouble t,jfloatArray out){
    LOCK; c.update(t); auto p=c.pose();
    if (out && e->GetArrayLength(out)>=9) e->SetFloatArrayRegion(out,0,9,p.data());
}
