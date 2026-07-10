#include <jni.h>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "NativeCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Camera state
static float targetX = 0.0f, targetY = 0.0f, targetZ = 0.0f;
static float distance = 3.0f;
static float yaw = 0.0f;
static float pitch = 0.35f; // ~20 degrees down
static float camX = 0.0f, camY = 2.0f, camZ = 3.0f;

// Smoothing
static float smoothYaw = 0.0f;
static float smoothPitch = 0.35f;
static float smoothDistance = 3.0f;
static float smoothTargetX = 0.0f;
static float smoothTargetY = 0.0f;
static float smoothTargetZ = 0.0f;

// Inertia
static float velocityYaw = 0.0f;
static float velocityPitch = 0.0f;
static bool isDragging = false;

// Settings
static float sensitivity = 0.006f;
static float damping = 0.92f; // Nomad-style heavy inertia
static float minDistance = 0.5f;
static float maxDistance = 50.0f;
static float minPitch = -1.4f;
static float maxPitch = 1.4f;

// Screen size for raycasting
static int screenW = 1080;
static int screenH = 1920;

// Mesh bounds for raycasting
static float meshMinX = -1, meshMinY = -1, meshMinZ = -1;
static float meshMaxX = 1, meshMaxY = 1, meshMaxZ = 1;
static bool hasMeshBounds = false;

// Auto-rotate
static bool autoRotate = false;
static float autoRotateSpeed = 0.5f;

// Focus animation
static bool isFocusing = false;
static float focusTimer = 0.0f;
static float focusDuration = 0.6f;
static float focusStartYaw, focusEndYaw;
static float focusStartPitch, focusEndPitch;
static float focusStartDist, focusEndDist;
static float focusStartTX, focusEndTX;
static float focusStartTY, focusEndTY;
static float focusStartTZ, focusEndTZ;

// Convert spherical to cartesian
static void recalculateCamera() {
    float cosP = cosf(smoothPitch);
    float sinP = sinf(smoothPitch);
    float cosY = cosf(smoothYaw);
    float sinY = sinf(smoothYaw);

    camX = smoothTargetX + smoothDistance * cosP * sinY;
    camY = smoothTargetY + smoothDistance * sinP;
    camZ = smoothTargetZ + smoothDistance * cosP * cosY;
}

static float easeOutCubic(float t) {
    float u = 1.0f - t;
    return 1.0f - u * u * u;
}

extern "C" {

// ============================================================================
// Lifecycle
// ============================================================================
JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeInit(JNIEnv*, jclass) {
    targetX = targetY = targetZ = 0.0f;
    distance = 3.0f;
    yaw = 0.0f;
    pitch = 0.35f;
    smoothYaw = 0.0f;
    smoothPitch = 0.35f;
    smoothDistance = 3.0f;
    smoothTargetX = smoothTargetY = smoothTargetZ = 0.0f;
    velocityYaw = velocityPitch = 0.0f;
    isDragging = false;
    autoRotate = false;
    isFocusing = false;
    recalculateCamera();
    LOGI("Camera initialized");
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeSetScreenSize(JNIEnv*, jclass, jint w, jint h) {
    screenW = w;
    screenH = h;
}

// ============================================================================
// Reset & Focus
// ============================================================================
JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeResetToDefault(JNIEnv*, jclass) {
    targetX = targetY = targetZ = 0.0f;
    distance = 3.0f;
    yaw = 0.0f;
    pitch = 0.35f;
    smoothYaw = yaw;
    smoothPitch = pitch;
    smoothDistance = distance;
    smoothTargetX = targetX;
    smoothTargetY = targetY;
    smoothTargetZ = targetZ;
    velocityYaw = velocityPitch = 0.0f;
    autoRotate = false;
    isFocusing = false;
    recalculateCamera();
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeSetTarget(JNIEnv*, jclass,
    jfloat x, jfloat y, jfloat z) {
    targetX = x;
    targetY = y;
    targetZ = z;
    smoothTargetX = x;
    smoothTargetY = y;
    smoothTargetZ = z;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeSetDistance(JNIEnv*, jclass, jfloat dist) {
    distance = dist;
    smoothDistance = dist;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeFocusOn(JNIEnv*, jclass,
    jfloat cx, jfloat cy, jfloat cz, jfloat radius) {
    isFocusing = true;
    focusTimer = 0.0f;
    focusDuration = 0.6f;

    focusStartYaw = yaw;
    focusEndYaw = yaw;
    focusStartPitch = pitch;
    focusEndPitch = 0.35f;
    focusStartDist = distance;
    focusEndDist = radius * 3.0f;
    focusStartTX = targetX;
    focusEndTX = cx;
    focusStartTY = targetY;
    focusEndTY = cy;
    focusStartTZ = targetZ;
    focusEndTZ = cz;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeFrameModel(JNIEnv*, jclass,
    jfloat minX, jfloat minY, jfloat minZ,
    jfloat maxX, jfloat maxY, jfloat maxZ) {
    float cx = (minX + maxX) * 0.5f;
    float cy = (minY + maxY) * 0.5f;
    float cz = (minZ + maxZ) * 0.5f;
    float dx = maxX - minX;
    float dy = maxY - minY;
    float dz = maxZ - minZ;
    float radius = sqrtf(dx*dx + dy*dy + dz*dz) * 0.5f;

    targetX = cx;
    targetY = cy;
    targetZ = cz;
    distance = radius * 3.5f;
    yaw = 0.0f;
    pitch = 0.35f;

    smoothYaw = yaw;
    smoothPitch = pitch;
    smoothDistance = distance;
    smoothTargetX = targetX;
    smoothTargetY = targetY;
    smoothTargetZ = targetZ;
    velocityYaw = velocityPitch = 0.0f;

    meshMinX = minX; meshMinY = minY; meshMinZ = minZ;
    meshMaxX = maxX; meshMaxY = maxY; meshMaxZ = maxZ;
    hasMeshBounds = true;

    recalculateCamera();
    LOGI("Framed model: center=(%.2f,%.2f,%.2f) radius=%.2f", cx, cy, cz, radius);
}

// ============================================================================
// Touch Input
// ============================================================================
JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeOnTouchStart(JNIEnv*, jclass, jfloat x, jfloat y) {
    isDragging = true;
    velocityYaw = 0.0f;
    velocityPitch = 0.0f;
    autoRotate = false; // Stop auto-rotate on touch
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeOnTouchMove(JNIEnv*, jclass,
    jfloat dx, jfloat dy, jint pointerCount) {
    if (pointerCount == 1) {
        // ORBIT: rotate camera around target
        yaw -= dx * sensitivity;
        pitch += dy * sensitivity;

        // Clamp pitch
        if (pitch < minPitch) pitch = minPitch;
        if (pitch > maxPitch) pitch = maxPitch;

        // Store velocity for inertia
        velocityYaw = -dx * sensitivity * 60.0f;
        velocityPitch = dy * sensitivity * 60.0f;

    } else if (pointerCount >= 2) {
        // PAN: move target and camera together
        // Calculate camera right and up vectors
        float cosP = cosf(pitch);
        float sinP = sinf(pitch);
        float cosY = cosf(yaw);
        float sinY = sinf(yaw);

        // Forward vector (from target to camera)
        float fwdX = cosP * sinY;
        float fwdY = sinP;
        float fwdZ = cosP * cosY;

        // Right vector = cross(up, forward)
        float rightX = fwdZ; // cross((0,1,0), fwd) = (fwdZ, 0, -fwdX)
        float rightY = 0.0f;
        float rightZ = -fwdX;
        float rightLen = sqrtf(rightX*rightX + rightZ*rightZ);
        if (rightLen > 0.001f) {
            rightX /= rightLen;
            rightZ /= rightLen;
        }

        // Up vector = cross(forward, right)
        float upX = -fwdY * rightZ;
        float upY = fwdX * rightZ - fwdZ * rightX;
        float upZ = fwdY * rightX;
        float upLen = sqrtf(upX*upX + upY*upY + upZ*upZ);
        if (upLen > 0.001f) {
            upX /= upLen;
            upY /= upLen;
            upZ /= upLen;
        }

        float panSpeed = distance * 0.002f;
        float panX = (-dx * rightX + dy * upX) * panSpeed;
        float panY = (-dx * rightY + dy * upY) * panSpeed;
        float panZ = (-dx * rightZ + dy * upZ) * panSpeed;

        targetX += panX;
        targetY += panY;
        targetZ += panZ;

        velocityYaw = 0.0f;
        velocityPitch = 0.0f;
    }
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeOnTouchEnd(JNIEnv*, jclass) {
    isDragging = false;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeOnPinch(JNIEnv*, jclass, jfloat scale) {
    distance /= scale;
    if (distance < minDistance) distance = minDistance;
    if (distance > maxDistance) distance = maxDistance;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeOnDoubleTap(JNIEnv*, jclass, jfloat x, jfloat y) {
    // Simple raycast against AABB to set pivot
    if (!hasMeshBounds) {
        // No mesh data, just reset
        nativeResetToDefault(nullptr, nullptr);
        return;
    }

    // For now, set pivot to center of mesh at tap depth
    // Full triangle raycast would need mesh vertex data
    float ndcX = (2.0f * x / screenW) - 1.0f;
    float ndcY = 1.0f - (2.0f * y / screenH);

    // Approximate: project ray to mesh bounds center plane
    float meshCX = (meshMinX + meshMaxX) * 0.5f;
    float meshCY = (meshMinY + meshMaxY) * 0.5f;
    float meshCZ = (meshMinZ + meshMaxZ) * 0.5f;

    targetX = meshCX;
    targetY = meshCY;
    targetZ = meshCZ;

    LOGI("Double tap: pivot set to (%.2f,%.2f,%.2f)", targetX, targetY, targetZ);
}

// ============================================================================
// Update
// ============================================================================
JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeUpdate(JNIEnv*, jclass, jfloat deltaTime) {
    // Handle focus animation
    if (isFocusing) {
        focusTimer += deltaTime;
        float t = focusTimer / focusDuration;
        if (t >= 1.0f) {
            t = 1.0f;
            isFocusing = false;
        }
        float eased = easeOutCubic(t);

        yaw = focusStartYaw + (focusEndYaw - focusStartYaw) * eased;
        pitch = focusStartPitch + (focusEndPitch - focusStartPitch) * eased;
        distance = focusStartDist + (focusEndDist - focusStartDist) * eased;
        targetX = focusStartTX + (focusEndTX - focusStartTX) * eased;
        targetY = focusStartTY + (focusEndTY - focusStartTY) * eased;
        targetZ = focusStartTZ + (focusEndTZ - focusStartTZ) * eased;
    }

    // Auto-rotate
    if (autoRotate && !isDragging && !isFocusing) {
        yaw += autoRotateSpeed * deltaTime;
    }

    // Inertia
    if (!isDragging && !isFocusing) {
        if (fabsf(velocityYaw) > 0.0001f || fabsf(velocityPitch) > 0.0001f) {
            yaw += velocityYaw * deltaTime;
            pitch += velocityPitch * deltaTime;

            if (pitch < minPitch) pitch = minPitch;
            if (pitch > maxPitch) pitch = maxPitch;

            velocityYaw *= damping;
            velocityPitch *= damping;
        }
    }

    // Smooth interpolation (exponential decay toward target)
    float t = 1.0f - powf(0.001f, deltaTime * 12.0f);

    smoothYaw += (yaw - smoothYaw) * t;
    smoothPitch += (pitch - smoothPitch) * t;
    smoothDistance += (distance - smoothDistance) * t;
    smoothTargetX += (targetX - smoothTargetX) * t;
    smoothTargetY += (targetY - smoothTargetY) * t;
    smoothTargetZ += (targetZ - smoothTargetZ) * t;

    recalculateCamera();
}

// ============================================================================
// Getters
// ============================================================================
JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetCamX(JNIEnv*, jclass) {
    return camX;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetCamY(JNIEnv*, jclass) {
    return camY;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetCamZ(JNIEnv*, jclass) {
    return camZ;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetTargetX(JNIEnv*, jclass) {
    return smoothTargetX;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetTargetY(JNIEnv*, jclass) {
    return smoothTargetY;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetTargetZ(JNIEnv*, jclass) {
    return smoothTargetZ;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetYaw(JNIEnv*, jclass) {
    return smoothYaw;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetPitch(JNIEnv*, jclass) {
    return smoothPitch;
}

JNIEXPORT jfloat JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeGetDistance(JNIEnv*, jclass) {
    return smoothDistance;
}

// ============================================================================
// Settings
// ============================================================================
JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeSetDamping(JNIEnv*, jclass, jfloat d) {
    damping = d;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeSetSensitivity(JNIEnv*, jclass, jfloat s) {
    sensitivity = s;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeSetAutoRotate(JNIEnv*, jclass, jboolean enabled) {
    autoRotate = enabled;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeToggleAutoRotate(JNIEnv*, jclass) {
    autoRotate = !autoRotate;
}

JNIEXPORT jboolean JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeIsAutoRotating(JNIEnv*, jclass) {
    return autoRotate ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_arena_simpleglbviewer_NativeCamera_nativeSetDistanceRange(JNIEnv*, jclass,
    jfloat minD, jfloat maxD) {
    minDistance = minD;
    maxDistance = maxD;
}

} // extern "C"
