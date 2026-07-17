#include <jni.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <mutex>

namespace {
struct V { float x,y,z; };
V add(V a,V b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
V sub(V a,V b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
V mul(V a,float s){return {a.x*s,a.y*s,a.z*s};}
float dot(V a,V b){return a.x*b.x+a.y*b.y+a.z*b.z;}
V cross(V a,V b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
float len(V a){return std::sqrt(dot(a,a));}
V norm(V a){float l=len(a);return l>1e-6f?mul(a,1.f/l):V{0,0,0};}
V rotate(V v,V axis,float angle){
    axis=norm(axis); float c=std::cos(angle),s=std::sin(angle);
    return add(add(mul(v,c),mul(cross(axis,v),s)),mul(axis,dot(axis,v)*(1-c)));
}

struct CameraController {
    V eye{0,0,3.8f}, forward{0,0,-1}, up{0,1,0};
    V dEye{0,0,3.8f}, dForward{0,0,-1}, dUp{0,1,0};
    V pivot{0,0,0}, pending{0,0,0};
    V startEye{},startForward{},startUp{},startPivot{};
    float width=1,height=1;
    bool pendingPivot=false,orbitActive=false;
    double lastTime=0;
    std::mutex mutex;

    void reset(){
        eye=dEye={0,0,3.8f}; forward=dForward={0,0,-1}; up=dUp={0,1,0};
        pivot=pending={0,0,0}; pendingPivot=false;orbitActive=false;lastTime=0;
    }
    V safePivot(float x,float y,float z){
        if(!std::isfinite(x)||!std::isfinite(y)||!std::isfinite(z)) return {0,0,0};
        V p{std::clamp(x,-1.25f,1.25f),std::clamp(y,-1.25f,1.25f),std::clamp(z,-1.25f,1.25f)};
        float l=len(p); return l>1.9f?mul(p,1.9f/l):p;
    }
    void queuePivot(float x,float y,float z){pending=safePivot(x,y,z);pendingPivot=true;}
    void beginOrbit(){
        // Pivot and camera orientation are separate. Committing a picked point
        // changes no eye/forward/up value, therefore the image cannot snap.
        if(pendingPivot){pivot=pending;pendingPivot=false;}
        startEye=dEye;startForward=dForward;startUp=dUp;startPivot=pivot;orbitActive=true;
    }
    void orbitTo(float dx,float dy){
        if(!orbitActive)return;
        const float yaw=-dx/std::max(width,1.f)*2.35f;
        const float pitch=dy/std::max(height,1.f)*2.15f;
        const V worldUp{0,1,0};
        V offset=rotate(sub(startEye,startPivot),worldUp,yaw);
        V f=rotate(startForward,worldUp,yaw);
        V u=rotate(startUp,worldUp,yaw);
        V right=norm(cross(f,u));
        offset=rotate(offset,right,pitch);f=rotate(f,right,pitch);u=rotate(u,right,pitch);
        dEye=add(startPivot,offset);dForward=norm(f);dUp=norm(u);
    }
    void endOrbit(){orbitActive=false;}
    void zoom(float scale){
        if(!std::isfinite(scale)||scale<=0.01f)return;
        // Raw pinch ratio is bounded per event, not delayed by a low-pass filter.
        scale=std::clamp(scale,0.88f,1.12f);
        V offset=sub(dEye,pivot);float old=len(offset);
        float next=std::clamp(old/scale,0.35f,16.f);
        if(old>1e-6f)dEye=add(pivot,mul(offset,next/old));
        eye=dEye; // no rubber-band catch-up after fingers stop
    }
    void pan(float dx,float dy){
        float distance=len(sub(dEye,pivot));float units=distance*1.45f/std::max(height,1.f);
        V right=norm(cross(dForward,dUp));
        V delta=add(mul(right,-dx*units),mul(dUp,dy*units));
        pivot=add(pivot,delta); dEye=add(dEye,delta); eye=add(eye,delta);
    }
    void update(double now){
        float dt=lastTime==0?1.f/60.f:(float)std::clamp(now-lastTime,0.0,0.05);lastTime=now;
        float a=1-std::exp(-15.f*dt);
        eye=add(eye,mul(sub(dEye,eye),a));
        forward=norm(add(forward,mul(sub(dForward,forward),a)));
        up=norm(add(up,mul(sub(dUp,up),a)));
        V right=norm(cross(forward,up));up=norm(cross(right,forward));
    }
    std::array<float,9> pose()const{
        V target=add(eye,forward);
        return {eye.x,eye.y,eye.z,target.x,target.y,target.z,up.x,up.y,up.z};
    }
} c;
}
#define LOCK std::lock_guard<std::mutex> l(c.mutex)
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeSetViewport(JNIEnv*,jobject,jint w,jint h){LOCK;c.width=w;c.height=h;}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeBeginOrbit(JNIEnv*,jobject){LOCK;c.beginOrbit();}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeOrbitTo(JNIEnv*,jobject,jfloat x,jfloat y){LOCK;c.orbitTo(x,y);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeOrbit(JNIEnv*,jobject,jfloat x,jfloat y){LOCK;c.beginOrbit();c.orbitTo(x,y);c.endOrbit();}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeEndOrbit(JNIEnv*,jobject){LOCK;c.endOrbit();}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeZoom(JNIEnv*,jobject,jfloat s){LOCK;c.zoom(s);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativePan(JNIEnv*,jobject,jfloat x,jfloat y){LOCK;c.pan(x,y);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeQueuePivot(JNIEnv*,jobject,jfloat x,jfloat y,jfloat z){LOCK;c.queuePivot(x,y,z);}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeReset(JNIEnv*,jobject){LOCK;c.reset();}
extern "C" JNIEXPORT void JNICALL Java_luxe_texture3d_app_NativeCamera_nativeUpdate(JNIEnv* e,jobject,jdouble t,jfloatArray out){LOCK;c.update(t);auto p=c.pose();if(out&&e->GetArrayLength(out)>=9)e->SetFloatArrayRegion(out,0,9,p.data());}
