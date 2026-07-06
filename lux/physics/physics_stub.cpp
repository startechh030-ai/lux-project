#include "physics/physics_stub.h"
#include <android/log.h>

#define LOG_TAG "LuxPhysics"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Physics engine is currently a stub. All functionality is inline in the header.
// This file exists only for build system completeness.

namespace lux {
// (no additional implementation needed — PhysicsEngine is inline-only)
} // namespace lux
