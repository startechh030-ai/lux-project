#ifndef LUX_CORE_APP_LIFECYCLE_H
#define LUX_CORE_APP_LIFECYCLE_H

#include <cstdint>

namespace lux {

/// Describes the current state of the Android application lifecycle.
enum class AppState : uint8_t {
    Uninitialized,
    Created,       ///< Native surface not yet available
    Started,       ///< App is visible but not interactive
    Resumed,       ///< App is in the foreground, fully interactive
    Paused,        ///< App partially obscured (e.g. incoming call)
    Stopped,       ///< App no longer visible
    Destroyed      ///< App is being torn down
};

/// Callbacks for Android activity lifecycle events.
class LifecycleListener {
public:
    virtual ~LifecycleListener() = default;
    virtual void onAppCreate()   {}
    virtual void onAppStart()    {}
    virtual void onAppResume()   {}
    virtual void onAppPause()    {}
    virtual void onAppStop()     {}
    virtual void onAppDestroy()  {}
};

/// Central lifecycle manager. Calls all registered listeners on state changes.
class AppLifecycle {
public:
    AppLifecycle();
    ~AppLifecycle();

    AppLifecycle(const AppLifecycle&) = delete;
    AppLifecycle& operator=(const AppLifecycle&) = delete;

    /// Register a listener. The listener is NOT owned by AppLifecycle.
    void addListener(LifecycleListener* listener);

    /// Remove a previously registered listener.
    void removeListener(LifecycleListener* listener);

    /// Transition to a new state, notifying all listeners.
    void transitionTo(AppState newState);

    /// Returns the current application state.
    AppState currentState() const { return state_; }

    /// Convenience: called from JNI when native surface is created.
    void onSurfaceCreated(void* nativeWindow);

    /// Convenience: called from JNI when native surface is destroyed.
    void onSurfaceDestroyed();

private:
    static constexpr int MAX_LISTENERS = 32;

    AppState state_ = AppState::Uninitialized;
    LifecycleListener* listeners_[MAX_LISTENERS] = {};
    int listenerCount_ = 0;
    void* nativeWindow_ = nullptr;
};

} // namespace lux

#endif // LUX_CORE_APP_LIFECYCLE_H
