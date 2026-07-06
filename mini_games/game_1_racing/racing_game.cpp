#include "racing_game.h"
#include "game/mini_game.h"
#include "renderer/renderer.h"
#include "audio/audio_engine.h"
#include "assets/asset_manager.h"
#include "animation/animation_system.h"
#include <android/log.h>
#include <cmath>

#define LOG_TAG "LuxRacing"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace lux {

// ── Self-registration ─────────────────────────────────────────────────────
LUX_REGISTER_GAME("racing", RacingGame);

// ── Constructor / Destructor ──────────────────────────────────────────────

RacingGame::RacingGame() {
    LOGI("RacingGame created");
}

RacingGame::~RacingGame() {
    LOGI("RacingGame destroyed");
}

// ── Lifecycle ─────────────────────────────────────────────────────────────

bool RacingGame::onInit(Renderer* renderer, AudioEngine* audio,
                         AssetManager* assets, AnimationSystem* anim) {
    renderer_ = renderer;
    audio_ = audio;
    assets_ = assets;
    anim_ = anim;
    LOGI("RacingGame: onInit");
    return true;
}

bool RacingGame::onStart() {
    LOGI("RacingGame: onStart");
    state_.isPlaying = true;
    state_.isPaused = false;
    state_.worldTime = 0.0f;
    resetPlayer();
    return true;
}

void RacingGame::onUpdate(float dt) {
    if (!state_.isPlaying || state_.isPaused) return;

    state_.worldTime += dt;
    updatePlayer(dt);
}

void RacingGame::onRender() {
    // ── Stub rendering ────────────────────────────────────────────────
    // In the real game, we'd draw track segments, the car, obstacles, etc.
    // For now we just clear and submit a simple scene.
    //
    // Using Filament:
    // - Create a MaterialInstance for the car
    // - Draw a ground plane
    // - Add scene objects to filament::Scene
    //
    // For stub mode, this is a no-op since the renderer handles clearing.
}

void RacingGame::onPause() {
    LOGI("RacingGame: onPause");
    state_.isPaused = true;
}

void RacingGame::onResume() {
    LOGI("RacingGame: onResume");
    state_.isPaused = false;
}

void RacingGame::onStop() {
    LOGI("RacingGame: onStop");
    state_.isPlaying = false;
}

void RacingGame::onShutdown() {
    LOGI("RacingGame: onShutdown");
    renderer_ = nullptr;
    audio_ = nullptr;
    assets_ = nullptr;
    anim_ = nullptr;
}

// ── Input ─────────────────────────────────────────────────────────────────

bool RacingGame::onTouch(int pointerId, float x, float y, bool pressed) {
    if (!state_.isPlaying) return false;

    if (pressed) {
        // Map screen position to steering
        // (screen center = no steer, left = steer left, right = steer right)
        float screenCenter = 540.0f; // assuming 1080p / 2
        state_.player.steering = (x - screenCenter) / screenCenter;

        // Bottom half = boost
        if (y > 960.0f) {
            state_.player.boost = std::min(100.0f, state_.player.boost + 50.0f);
        }
    }

    return true;
}

bool RacingGame::onKey(int keyCode, bool pressed) {
    (void)keyCode;
    (void)pressed;
    return false;
}

// ── Metadata ──────────────────────────────────────────────────────────────

MiniGameInfo RacingGame::getInfo() const {
    MiniGameInfo info;
    info.id = "racing";
    info.title = "Speed Rush";
    info.description = "Top-down arcade racing. Tilt to steer, tap to boost!";
    info.version = "0.1.0";
    info.minPlayers = 1;
    info.maxPlayers = 2;
    info.supportsMultiplayer = true;
    return info;
}

// ── Game Logic ────────────────────────────────────────────────────────────

void RacingGame::resetPlayer() {
    state_.player.x = 0.0f;
    state_.player.y = 0.0f;
    state_.player.speed = 0.0f;
    state_.player.rotation = 0.0f;
    state_.player.steering = 0.0f;
    state_.player.boost = 100.0f;
    state_.player.lapsCompleted = 0;
}

void RacingGame::updatePlayer(float dt) {
    auto& p = state_.player;

    // Acceleration (always forward)
    p.speed += p.acceleration * dt;
    if (p.speed > p.maxSpeed) p.speed = p.maxSpeed;

    // Steering
    p.rotation += p.steering * dt * 2.0f;

    // Apply velocity
    p.x += std::sin(p.rotation) * p.speed * dt;
    p.y += std::cos(p.rotation) * p.speed * dt;

    // Friction when not accelerating
    // p.speed *= 0.99f;

    // Boost regeneration
    p.boost = std::min(100.0f, p.boost + 5.0f * dt);
}

} // namespace lux
