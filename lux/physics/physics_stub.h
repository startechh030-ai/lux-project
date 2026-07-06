#ifndef LUX_PHYSICS_PHYSICS_STUB_H
#define LUX_PHYSICS_PHYSICS_STUB_H

#include <cstdint>

namespace lux {

/// Physics interface — currently a stub.
/// Will be backed by Jolt Physics or PhysX in the future.
class PhysicsEngine {
public:
    PhysicsEngine() = default;
    ~PhysicsEngine() = default;

    /// Initialize the physics engine (stub — always succeeds).
    bool initialize() { return true; }

    /// Shutdown.
    void shutdown() {}

    /// Step the physics simulation by dt seconds (stub — no-op).
    void step(float dt) { (void)dt; }

    /// Create a rigid body (stub — returns dummy id).
    uint32_t createBody() { return 0; }

    /// Destroy a rigid body (stub — no-op).
    void destroyBody(uint32_t /*bodyId*/) {}

    /// Returns true if initialized.
    bool isInitialized() const { return true; }
};

} // namespace lux

#endif // LUX_PHYSICS_PHYSICS_STUB_H
