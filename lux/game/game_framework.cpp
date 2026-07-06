#include "game/game_framework.h"
#include "core/app_lifecycle.h"
#include "core/job_queue.h"
#include "renderer/renderer.h"
#include "audio/audio_engine.h"
#include "assets/asset_manager.h"
#include "animation/animation_system.h"
#include "networking/network_client.h"
#include <android/log.h>

#define LOG_TAG "LuxFramework"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lux {

GameFramework::GameFramework() {
    LOGI("GameFramework created");
}

GameFramework::~GameFramework() {
    shutdown();
}

bool GameFramework::initialize(void* nativeWindow, void* assetManager,
                                 int screenWidth, int screenHeight) {
    LOGI("GameFramework initializing...");

    // 1. Lifecycle manager
    lifecycle_ = std::make_unique<AppLifecycle>();

    // 2. Job system (multi-threading)
    jobs_ = std::make_unique<JobSystem>(0); // auto-detect thread count

    // 3. Renderer
    renderer_ = std::make_unique<Renderer>();
    RendererConfig renderConfig;
    renderConfig.nativeWindow = nativeWindow;
    renderConfig.width = screenWidth;
    renderConfig.height = screenHeight;
    renderConfig.backend = RendererBackend::Vulkan;
    if (!renderer_->initialize(renderConfig)) {
        LOGE("Failed to initialize renderer");
        return false;
    }

    // 4. Audio
    audio_ = std::make_unique<AudioEngine>();
    if (!audio_->initialize()) {
        LOGE("Failed to initialize audio");
        return false;
    }

    // 5. Asset manager
    assets_ = std::make_unique<AssetManager>();
    if (!assets_->initialize(assetManager)) {
        LOGE("Failed to initialize asset manager");
        return false;
    }

    // 6. Animation system
    animation_ = std::make_unique<AnimationSystem>();
    if (!animation_->initialize()) {
        LOGE("Failed to initialize animation system");
        return false;
    }

    // 7. Networking (optional — no server connection at startup)
    network_ = std::make_unique<NetworkClient>();

    // Mark lifecycle as started
    lifecycle_->transitionTo(AppState::Created);
    lifecycle_->transitionTo(AppState::Started);

    LOGI("GameFramework initialized successfully (%dx%d)",
         screenWidth, screenHeight);
    return true;
}

void GameFramework::shutdown() {
    // Stop any running game
    stopCurrentGame();

    // Shutdown subsystems in reverse order
    network_.reset();
    animation_.reset();
    assets_.reset();
    audio_.reset();
    renderer_.reset();
    jobs_.reset();

    if (lifecycle_) {
        lifecycle_->transitionTo(AppState::Destroyed);
        lifecycle_.reset();
    }

    LOGI("GameFramework shut down");
}

void GameFramework::tick(float dt) {
    if (!lifecycle_) return;

    // Update networking
    network_->update();

    // Update animation system
    animation_->update(dt);

    // Update audio
    audio_->update();

    // Hot-reload assets
    assets_->pollHotReload();

    // Update the current mini-game
    if (currentGame_) {
        currentGame_->onUpdate(dt);

        // Render
        if (renderer_->beginFrame()) {
            currentGame_->onRender();
            renderer_->endFrame();
        }
    }
}

bool GameFramework::loadAndStartGame(const std::string& gameId) {
    // Stop any running game first
    stopCurrentGame();

    // Check if the game is registered
    if (!MiniGameRegistry::instance().hasGame(gameId)) {
        LOGE("Game '%s' not registered", gameId.c_str());
        return false;
    }

    // Create the mini-game instance
    currentGame_ = MiniGameRegistry::instance().create(gameId);
    if (!currentGame_) {
        LOGE("Failed to create game '%s'", gameId.c_str());
        return false;
    }

    currentGameId_ = gameId;

    // Initialize
    if (!currentGame_->onInit(renderer_.get(), audio_.get(),
                               assets_.get(), animation_.get())) {
        LOGE("Game '%s' failed onInit", gameId.c_str());
        delete currentGame_;
        currentGame_ = nullptr;
        currentGameId_.clear();
        return false;
    }

    // Start
    if (!currentGame_->onStart()) {
        LOGE("Game '%s' failed onStart", gameId.c_str());
        currentGame_->onShutdown();
        delete currentGame_;
        currentGame_ = nullptr;
        currentGameId_.clear();
        return false;
    }

    LOGI("Started game: %s", gameId.c_str());
    return true;
}

void GameFramework::stopCurrentGame() {
    if (currentGame_) {
        currentGame_->onStop();
        currentGame_->onShutdown();
        delete currentGame_;
        currentGame_ = nullptr;
        currentGameId_.clear();
        LOGI("Stopped current game");
    }
}

void GameFramework::onTouchEvent(int pointerId, float x, float y, bool pressed) {
    if (currentGame_) {
        currentGame_->onTouch(pointerId, x, y, pressed);
    }
}

} // namespace lux
