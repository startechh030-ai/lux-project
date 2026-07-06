#ifndef LUX_GAME_MINI_GAME_H
#define LUX_GAME_MINI_GAME_H

#include <cstdint>
#include <string>

namespace lux {

// Forward declarations
class Renderer;
class AudioEngine;
class AssetManager;
class AnimationSystem;
class NetworkClient;
class InputSystem;

/// Describes a mini-game's metadata.
struct MiniGameInfo {
    std::string id;          ///< Unique identifier (e.g. "racing")
    std::string title;       ///< Display name (e.g. "Speed Rush")
    std::string description; ///< Short description
    std::string version;     ///< Semantic version
    uint8_t minPlayers = 1;
    uint8_t maxPlayers = 4;
    bool supportsMultiplayer = false;
};

/// Abstract base class for all mini-games.
/// Each mini-game inherits from this and implements the lifecycle methods.
class MiniGame {
public:
    MiniGame() = default;
    virtual ~MiniGame() = default;

    MiniGame(const MiniGame&) = delete;
    MiniGame& operator=(const MiniGame&) = delete;

    /// ── Lifecycle ────────────────────────────────────────────────────

    /// Called once when the mini-game is first loaded.
    /// Perform all one-time initialization here.
    virtual bool onInit(Renderer* renderer, AudioEngine* audio,
                        AssetManager* assets, AnimationSystem* anim) = 0;

    /// Called when the mini-game becomes active (player selected it).
    virtual bool onStart() = 0;

    /// Called every frame while the mini-game is active.
    /// @param dt Delta time in seconds.
    virtual void onUpdate(float dt) = 0;

    /// Called every frame for rendering (after onUpdate).
    virtual void onRender() = 0;

    /// Called when the mini-game is paused (e.g., another game interrupts).
    virtual void onPause() {}

    /// Called when the mini-game resumes after being paused.
    virtual void onResume() {}

    /// Called when the mini-game is stopped (player exits).
    virtual void onStop() = 0;

    /// Called once when the mini-game is unloaded.
    virtual void onShutdown() = 0;

    /// ── Input ────────────────────────────────────────────────────────

    /// Handle a touch event. Returns true if consumed.
    virtual bool onTouch(int pointerId, float x, float y, bool pressed) {
        (void)pointerId; (void)x; (void)y; (void)pressed;
        return false;
    }

    /// Handle a key event. Returns true if consumed.
    virtual bool onKey(int keyCode, bool pressed) {
        (void)keyCode; (void)pressed;
        return false;
    }

    /// ── Metadata ─────────────────────────────────────────────────────

    /// Return the game's metadata.
    virtual MiniGameInfo getInfo() const = 0;

    /// Return the game's preferred backend renderer type.
    virtual int preferredBackend() const { return 0; }
};

/// Registration macro: call this in the mini-game's cpp file.
/// Usage: LUX_REGISTER_GAME("racing", RacingGame);
#define LUX_REGISTER_GAME(id, className)                                \
    namespace {                                                         \
        struct className##_Registrar {                                  \
            className##_Registrar() {                                   \
                MiniGameRegistry::instance().registerCreator(           \
                    id, []() -> MiniGame* { return new className(); }); \
            }                                                           \
        } className##_registrar;                                        \
    }

} // namespace lux

#endif // LUX_GAME_MINI_GAME_H
