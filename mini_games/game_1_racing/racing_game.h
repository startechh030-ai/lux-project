#ifndef LUX_GAME_RACING_H
#define LUX_GAME_RACING_H

#include "game/mini_game.h"

namespace lux {

/// The first mini-game: a top-down arcade racing game.
///
/// Concept: Player controls a car on a 2D track, avoiding obstacles
/// and other cars. Tilt to steer, tap to boost. Lap-based progression.
///
/// This is a template/skeleton — the game logic will be built up
/// step by step as we iterate.
class RacingGame final : public MiniGame {
public:
    RacingGame();
    ~RacingGame() override;

    // ── MiniGame interface ────────────────────────────────────────────
    bool onInit(Renderer* renderer, AudioEngine* audio,
                AssetManager* assets, AnimationSystem* anim) override;
    bool onStart() override;
    void onUpdate(float dt) override;
    void onRender() override;
    void onPause() override;
    void onResume() override;
    void onStop() override;
    void onShutdown() override;

    bool onTouch(int pointerId, float x, float y, bool pressed) override;
    bool onKey(int keyCode, bool pressed) override;

    MiniGameInfo getInfo() const override;

    // ── Game-specific state ───────────────────────────────────────────
    struct PlayerCar {
        float x = 0.0f;
        float y = 0.0f;
        float speed = 0.0f;
        float maxSpeed = 500.0f;
        float acceleration = 300.0f;
        float steering = 0.0f;
        float rotation = 0.0f;
        float boost = 100.0f;    // Boost fuel
        int lapsCompleted = 0;
    };

    struct GameState {
        PlayerCar player;
        float trackLength = 2000.0f;
        float worldTime = 0.0f;
        bool isPlaying = false;
        bool isPaused = false;
    };

private:
    // Subsystem pointers (set in onInit, do NOT own)
    Renderer* renderer_ = nullptr;
    AudioEngine* audio_ = nullptr;
    AssetManager* assets_ = nullptr;
    AnimationSystem* anim_ = nullptr;

    // Game state
    GameState state_;

    // Internal
    void resetPlayer();
    void updatePlayer(float dt);
};

} // namespace lux

#endif // LUX_GAME_RACING_H
