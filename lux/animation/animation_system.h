#ifndef LUX_ANIMATION_ANIMATION_SYSTEM_H
#define LUX_ANIMATION_ANIMATION_SYSTEM_H

#include <cstdint>
#include <string>
#include <vector>

namespace lux {

/// Handle for a loaded animation clip.
using AnimationHandle = uint32_t;
constexpr AnimationHandle kInvalidAnimation = 0;

/// Handle for a skinned mesh / skeleton.
using SkeletonHandle = uint32_t;
constexpr SkeletonHandle kInvalidSkeleton = 0;

/// Handle for a playing animation instance.
using AnimInstanceHandle = uint32_t;
constexpr AnimInstanceHandle kInvalidAnimInstance = 0;

/// Configuration for playing an animation.
struct AnimPlayConfig {
    float blendDuration = 0.15f;  ///< Crossfade duration in seconds
    float speed = 1.0f;
    bool looping = true;
    int layer = 0;
};

/// Runtime skeletal animation system using ozz-animation.
class AnimationSystem {
public:
    AnimationSystem();
    ~AnimationSystem();

    AnimationSystem(const AnimationSystem&) = delete;
    AnimationSystem& operator=(const AnimationSystem&) = delete;

    /// Initialize the animation system.
    bool initialize();

    /// Shutdown.
    void shutdown();

    /// Load a skeleton from an ozz skeleton file.
    SkeletonHandle loadSkeleton(const std::string& path);

    /// Load an animation clip from an ozz animation file.
    AnimationHandle loadAnimation(const std::string& path);

    /// Play an animation on a skeleton.
    AnimInstanceHandle playAnimation(SkeletonHandle skeleton,
                                     AnimationHandle animation,
                                     const AnimPlayConfig& config = {});

    /// Stop and remove an animation instance.
    void stopAnimation(AnimInstanceHandle instance);

    /// Advance all active animations by dt seconds.
    void update(float dt);

    /// Get the final joint transforms for a given skeleton instance.
    /// Returns nullptr if unavailable.
    const float* getJointMatrices(SkeletonHandle skeleton, int* outCount);

    /// Returns the number of joints in the skeleton.
    int getJointCount(SkeletonHandle skeleton) const;

    /// Returns true if initialized.
    bool isInitialized() const { return initialized_; }

private:
    bool initialized_ = false;

    struct SkeletonData { /* ozz::animation::Skeleton* */ void* skeleton = nullptr; int jointCount = 0; };
    struct AnimationData { /* ozz::animation::Animation* */ void* anim = nullptr; float duration = 0.0f; };
    struct AnimInstance { /* ozz::animation::SamplingJob + blending */ };

    static constexpr int kMaxSkeletons = 32;
    static constexpr int kMaxAnimations = 128;
    static constexpr int kMaxInstances = 64;

    SkeletonData skeletons_[kMaxSkeletons] = {};
    AnimationData animations_[kMaxAnimations] = {};
    AnimInstance* instances_[kMaxInstances] = {};

    uint32_t nextSkeletonId_ = 1;
    uint32_t nextAnimId_ = 1;
    uint32_t nextInstanceId_ = 1;
};

} // namespace lux

#endif // LUX_ANIMATION_ANIMATION_SYSTEM_H
