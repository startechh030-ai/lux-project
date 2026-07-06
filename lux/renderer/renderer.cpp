#include "renderer/renderer.h"
#include <android/log.h>

#define LOG_TAG "LuxRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lux {

Renderer::Renderer() {
    LOGI("Renderer created");
}

Renderer::~Renderer() {
    shutdown();
}

bool Renderer::initialize(const RendererConfig& config) {
    if (initialized_) {
        LOGW("Renderer already initialized");
        return true;
    }

    width_ = config.width;
    height_ = config.height;

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT
    // ── Filament backend ──────────────────────────────────────────────
    using namespace filament;

    Engine::Backend filamentBackend = Engine::Backend::VULKAN;
    if (config.backend == RendererBackend::OpenGL) {
        filamentBackend = Engine::Backend::OPENGL;
    }

    engine_ = Engine::create(filamentBackend);
    if (!engine_) {
        LOGE("Failed to create Filament Engine");
        return false;
    }

    renderer_ = engine_->createRenderer();
    scene_ = engine_->createScene();
    view_ = engine_->createView();
    camera_ = engine_->createCamera(EntityManager::get().create());
    swapChain_ = engine_->createSwapChain(
        static_cast<ANativeWindow*>(config.nativeWindow));

    view_->setCamera(camera_);
    view_->setScene(scene_);
    view_->setViewport({0, 0, static_cast<uint32_t>(width_),
                        static_cast<uint32_t>(height_)});

    LOGI("Filament renderer initialized (%s backend)",
         config.backend == RendererBackend::Vulkan ? "Vulkan" : "OpenGL");
#else
    // ── Stub / fallback renderer ──────────────────────────────────────
    LOGI("Renderer stub initialized (no Filament)");
    // In stub mode, we just track state without doing real rendering.
#endif

    initialized_ = true;
    return true;
}

void Renderer::shutdown() {
    if (!initialized_) return;

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT
    using namespace filament;
    if (engine_) {
        auto* e = static_cast<Engine*>(engine_);
        e->destroy(static_cast<SwapChain*>(swapChain_));
        e->destroy(static_cast<View*>(view_));
        e->destroy(static_cast<Renderer*>(renderer_));
        e->destroy(static_cast<Scene*>(scene_));
        e->destroyCameraComponent(EntityManager::get().create());
        Engine::destroy(&e);
        engine_ = nullptr;
    }
#endif

    engine_ = nullptr;
    renderer_ = nullptr;
    swapChain_ = nullptr;
    scene_ = nullptr;
    view_ = nullptr;
    camera_ = nullptr;
    initialized_ = false;
    LOGI("Renderer shut down");
}

bool Renderer::beginFrame() {
    if (!initialized_ || !renderer_) return false;

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT
    auto* r = static_cast<filament::Renderer*>(renderer_);
    return r->beginFrame(static_cast<filament::SwapChain*>(swapChain_));
#else
    return true;
#endif
}

void Renderer::endFrame() {
    if (!initialized_ || !renderer_) return;

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT
    auto* r = static_cast<filament::Renderer*>(renderer_);
    r->endFrame();
#endif
}

void Renderer::resize(int width, int height) {
    width_ = width;
    height_ = height;

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT
    if (view_) {
        static_cast<filament::View*>(view_)
            ->setViewport({0, 0, static_cast<uint32_t>(width_),
                           static_cast<uint32_t>(height_)});
    }
#endif

    LOGI("Renderer resized to %dx%d", width_, height_);
}

} // namespace lux
