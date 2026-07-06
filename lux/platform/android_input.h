#ifndef LUX_PLATFORM_ANDROID_INPUT_H
#define LUX_PLATFORM_ANDROID_INPUT_H

#include <cstdint>

namespace lux {

/// Represents a single touch point.
struct TouchEvent {
    int pointerId = 0;
    float x = 0.0f;
    float y = 0.0f;
    bool pressed = false;
    bool released = false;
    bool moved = false;
};

/// Input state for the current frame.
struct InputState {
    static constexpr int kMaxTouches = 10;

    int touchCount = 0;
    TouchEvent touches[kMaxTouches];

    // Accelerometer (if available)
    float accelX = 0.0f;
    float accelY = 0.0f;
    float accelZ = 0.0f;

    /// Clear all input for the start of a new frame.
    void clear() {
        touchCount = 0;
    }

    /// Add a touch event (up to kMaxTouches).
    void addTouch(const TouchEvent& event) {
        if (touchCount < kMaxTouches) {
            touches[touchCount++] = event;
        }
    }
};

/// Processes Android input events and makes them available to the game.
class InputSystem {
public:
    InputSystem();
    ~InputSystem();

    InputSystem(const InputSystem&) = delete;
    InputSystem& operator=(const InputSystem&) = delete;

    /// Called from JNI when a touch event arrives.
    void onTouch(int pointerId, float x, float y, bool pressed);

    /// Called from JNI for accelerometer data.
    void onAccelerometer(float x, float y, float z);

    /// Get the current frame's input state.
    const InputState& state() const { return state_; }

    /// Advance to the next frame (clears transient state).
    void endFrame();

private:
    InputState state_;
};

} // namespace lux

#endif // LUX_PLATFORM_ANDROID_INPUT_H
