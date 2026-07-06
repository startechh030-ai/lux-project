#include "animation/animation_system.h"
#include <android/log.h>

#define LOG_TAG "LuxAnim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lux {

AnimationSystem::AnimationSystem() {
    LOGI("AnimationSystem created");
}

AnimationSystem::~AnimationSystem() {
    shutdown();
}

bool AnimationSystem::initialize() {
    LOGI("AnimationSystem initialized");
    initialized_ = true;
    return true;
}

void AnimationSystem::shutdown() {
    if (!initialized_) return;

    // Stop all instances
    for (auto* inst : instances_) {
        delete inst;
    }

    // Free skeletons
    for (auto& skel : skeletons_) {
        if (skel.skeleton) {
#if defined(LUX_USE_OZZ) && LUX_USE_OZZ
            // delete static_cast<ozz::animation::Skeleton*>(skel.skeleton);
#endif
            skel.skeleton = nullptr;
        }
    }

    // Free animations
    for (auto& anim : animations_) {
        if (anim.anim) {
#if defined(LUX_USE_OZZ) && LUX_USE_OZZ
            // delete static_cast<ozz::animation::Animation*>(anim.anim);
#endif
            anim.anim = nullptr;
        }
    }

    initialized_ = false;
    LOGI("AnimationSystem shut down");
}

SkeletonHandle AnimationSystem::loadSkeleton(const std::string& path) {
    for (uint32_t i = 0; i < kMaxSkeletons; ++i) {
        auto& slot = skeletons_[i];
        if (!slot.skeleton) {
#if defined(LUX_USE_OZZ) && LUX_USE_OZZ
            // auto* skel = new ozz::animation::Skeleton();
            // ozz::io::File file(path.c_str(), "rb");
            // ozz::io::IArchive archive(&file);
            // archive >> *skel;
            // slot.jointCount = skel->num_joints();
            // slot.skeleton = skel;
            // LOGI("Loaded skeleton: %s (%d joints)", path.c_str(), slot.jointCount);
            // return i + 1;
#endif
            slot.skeleton = reinterpret_cast<void*>(0x1); // stub marker
            slot.jointCount = 42; // placeholder
            LOGI("Loaded skeleton (stub): %s", path.c_str());
            return i + 1;
        }
    }
    LOGE("Too many skeletons (max %d)", kMaxSkeletons);
    return kInvalidSkeleton;
}

AnimationHandle AnimationSystem::loadAnimation(const std::string& path) {
    for (uint32_t i = 0; i < kMaxAnimations; ++i) {
        auto& slot = animations_[i];
        if (!slot.anim) {
#if defined(LUX_USE_OZZ) && LUX_USE_OZZ
            // auto* anim = new ozz::animation::Animation();
            // ozz::io::File file(path.c_str(), "rb");
            // ozz::io::IArchive archive(&file);
            // archive >> *anim;
            // slot.duration = anim->duration();
            // slot.anim = anim;
#endif
            slot.anim = reinterpret_cast<void*>(0x1);
            slot.duration = 2.0f;
            LOGI("Loaded animation (stub): %s", path.c_str());
            return i + 1;
        }
    }
    LOGE("Too many animations (max %d)", kMaxAnimations);
    return kInvalidAnimation;
}

AnimInstanceHandle AnimationSystem::playAnimation(SkeletonHandle /*skeleton*/,
                                                   AnimationHandle /*animation*/,
                                                   const AnimPlayConfig& config) {
    // Stub: just return a dummy handle
    for (uint32_t i = 0; i < kMaxInstances; ++i) {
        if (!instances_[i]) {
            instances_[i] = new AnimInstance();
            LOGI("Playing animation (stub, blend=%.2f, speed=%.2f, loop=%d)",
                 config.blendDuration, config.speed, config.looping);
            return i + 1;
        }
    }
    return kInvalidAnimInstance;
}

void AnimationSystem::stopAnimation(AnimInstanceHandle instance) {
    if (instance == kInvalidAnimInstance || instance > kMaxInstances) return;
    delete instances_[instance - 1];
    instances_[instance - 1] = nullptr;
}

void AnimationSystem::update(float dt) {
    // In real mode, we'd sample all active animations and blend them.
    (void)dt;
}

const float* AnimationSystem::getJointMatrices(SkeletonHandle /*skeleton*/,
                                                int* outCount) {
    if (outCount) *outCount = 0;
    return nullptr;
}

int AnimationSystem::getJointCount(SkeletonHandle skeleton) const {
    if (skeleton == kInvalidSkeleton || skeleton > kMaxSkeletons) return 0;
    return skeletons_[skeleton - 1].jointCount;
}

} // namespace lux
