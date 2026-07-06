#include "core/app_lifecycle.h"
#include <cassert>
#include <android/log.h>

#define LOG_TAG "LuxLifecycle"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace lux {

AppLifecycle::AppLifecycle() {
    LOGI("AppLifecycle created");
}

AppLifecycle::~AppLifecycle() {
    LOGI("AppLifecycle destroyed");
}

void AppLifecycle::addListener(LifecycleListener* listener) {
    assert(listener != nullptr);
    if (listenerCount_ >= MAX_LISTENERS) return;
    listeners_[listenerCount_++] = listener;
}

void AppLifecycle::removeListener(LifecycleListener* listener) {
    for (int i = 0; i < listenerCount_; ++i) {
        if (listeners_[i] == listener) {
            listeners_[i] = listeners_[--listenerCount_];
            return;
        }
    }
}

void AppLifecycle::transitionTo(AppState newState) {
    if (newState == state_) return;

    const char* stateNames[] = {
        "Uninitialized", "Created", "Started", "Resumed",
        "Paused", "Stopped", "Destroyed"
    };
    LOGI("Lifecycle: %s -> %s",
         stateNames[static_cast<int>(state_)],
         stateNames[static_cast<int>(newState)]);

    // Notify all listeners
    for (int i = 0; i < listenerCount_; ++i) {
        switch (newState) {
            case AppState::Created:   listeners_[i]->onAppCreate();  break;
            case AppState::Started:   listeners_[i]->onAppStart();   break;
            case AppState::Resumed:   listeners_[i]->onAppResume();  break;
            case AppState::Paused:    listeners_[i]->onAppPause();   break;
            case AppState::Stopped:   listeners_[i]->onAppStop();    break;
            case AppState::Destroyed: listeners_[i]->onAppDestroy(); break;
            default: break;
        }
    }

    state_ = newState;
}

void AppLifecycle::onSurfaceCreated(void* nativeWindow) {
    nativeWindow_ = nativeWindow;
    LOGI("Native surface created: %p", nativeWindow);
}

void AppLifecycle::onSurfaceDestroyed() {
    LOGI("Native surface destroyed");
    nativeWindow_ = nullptr;
}

} // namespace lux
