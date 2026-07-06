#include "platform/android_input.h"

namespace lux {

InputSystem::InputSystem() = default;
InputSystem::~InputSystem() = default;

void InputSystem::onTouch(int pointerId, float x, float y, bool pressed) {
    TouchEvent event;
    event.pointerId = pointerId;
    event.x = x;
    event.y = y;
    event.pressed = pressed;
    event.released = !pressed;
    state_.addTouch(event);
}

void InputSystem::onAccelerometer(float x, float y, float z) {
    state_.accelX = x;
    state_.accelY = y;
    state_.accelZ = z;
}

void InputSystem::endFrame() {
    state_.clear();
}

} // namespace lux
