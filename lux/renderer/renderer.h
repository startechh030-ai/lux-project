#ifndef LUX_RENDERER_RENDERER_H
#define LUX_RENDERER_RENDERER_H

#include <cstdint>

namespace lux {

/// Forward declarations for Filament types (avoid header dependency).
struct RendererBackend {
    enum Type : uint8_t {
        OpenGL = 0,
        Vulkan = 1,
        Metal  = 2
    };
};

/// Configuration passed to Renderer::initialize().
struct RendererConfig {
    RendererBackend::Type backend = RendererBackend::Vulkan;
    void* nativeWindow = nullptr;
    int width = 0;
    int height = 0;
    bool vsync = true;
};

/// Renderer abstraction over Google Filament.
/// Provides a simplified interface for the mini-game framework.
class Renderer {
public:
    Renderer();
    ~Renderer();

    Renderer(const Renderer&) = delete;
    Renderer& operator=(const Renderer&) = delete;

    /// Initialize the renderer with the given config.
    /// Must be called on the main thread with a valid native window.
    bool initialize(const RendererConfig& config);

    /// Shutdown the renderer, release all GPU resources.
    void shutdown();

    /// Begin a new frame. Returns false if the frame should be skipped.
    bool beginFrame();

    /// End the current frame and swap buffers.
    void endFrame();

    /// Handle window resize.
    void resize(int width, int height);

    /// Returns true if the renderer has been initialized.
    bool isInitialized() const { return initialized_; }

    /// Access the underlying Filament engine (opaque handle).
    void* engineHandle() const { return engine_; }

    /// Access the underlying Filament renderer (opaque handle).
    void* rendererHandle() const { return renderer_; }

private:
    bool initialized_ = false;
    void* engine_ = nullptr;     ///< filament::Engine*
    void* renderer_ = nullptr;   ///< filament::Renderer*
    void* swapChain_ = nullptr;  ///< filament::SwapChain*
    void* scene_ = nullptr;      ///< filament::Scene*
    void* view_ = nullptr;       ///< filament::View*
    void* camera_ = nullptr;     ///< filament::Camera*
    int width_ = 0;
    int height_ = 0;
};

} // namespace lux

#endif // LUX_RENDERER_RENDERER_H
