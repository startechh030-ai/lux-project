#ifndef LUX_GAME_GAME_FRAMEWORK_H
#define LUX_GAME_GAME_FRAMEWORK_H

#include "game/mini_game.h"
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>
#include <functional>
#include <memory>

namespace lux {

/// Factory for creating mini-game instances.
using MiniGameCreator = std::function<MiniGame*()>;

/// Singleton registry of all available mini-games.
/// Mini-games self-register via LUX_REGISTER_GAME macro.
class MiniGameRegistry {
public:
    static MiniGameRegistry& instance();

    /// Register a mini-game creator under a unique ID.
    void registerCreator(const std::string& id, MiniGameCreator creator);

    /// Create a new instance of a mini-game by ID.
    MiniGame* create(const std::string& id) const;

    /// Check if a game with the given ID is registered.
    bool hasGame(const std::string& id) const;

    /// Get the list of all registered game IDs.
    std::vector<std::string> availableGames() const;

    /// Get metadata for all registered games.
    std::vector<MiniGameInfo> gameInfos() const;

    /// Get metadata for a specific game.
    MiniGameInfo getGameInfo(const std::string& id) const;

private:
    MiniGameRegistry() = default;
    ~MiniGameRegistry() = default;
    MiniGameRegistry(const MiniGameRegistry&) = delete;
    MiniGameRegistry& operator=(const MiniGameRegistry&) = delete;

    struct Entry {
        MiniGameCreator creator;
        mutable std::unique_ptr<MiniGame> infoInstance; // for metadata queries
    };

    std::unordered_map<std::string, Entry> registry_;
};

/// The central game framework manager.
/// Owns all subsystems, manages mini-game lifecycle, drives the main loop.
class GameFramework {
public:
    GameFramework();
    ~GameFramework();

    GameFramework(const GameFramework&) = delete;
    GameFramework& operator=(const GameFramework&) = delete;

    /// ── Initialization ───────────────────────────────────────────────

    /// Initialize the engine and all subsystems.
    /// @param nativeWindow ANativeWindow* from Android.
    /// @param assetManager AAssetManager* from Android.
    /// @param screenWidth Initial screen width in pixels.
    /// @param screenHeight Initial screen height in pixels.
    bool initialize(void* nativeWindow, void* assetManager,
                     int screenWidth, int screenHeight);

    /// Shutdown everything.
    void shutdown();

    /// ── Main loop (called from JNI every frame) ──────────────────────

    /// Tick the framework: process input, update game, render.
    void tick(float dt);

    /// ── Game management ──────────────────────────────────────────────

    /// Load and switch to a mini-game by ID.
    bool loadAndStartGame(const std::string& gameId);

    /// Stop the current mini-game and return to the menu.
    void stopCurrentGame();

    /// Returns the currently active mini-game, or nullptr.
    MiniGame* currentGame() const { return currentGame_; }

    /// Returns the ID of the currently active game, or empty.
    const std::string& currentGameId() const { return currentGameId_; }

    /// Returns true if a game is currently active.
    bool isGameRunning() const { return currentGame_ != nullptr; }

    /// ── Input ────────────────────────────────────────────────────────

    /// Feed a touch event into the framework.
    void onTouchEvent(int pointerId, float x, float y, bool pressed);

    /// ── Subsystem access ─────────────────────────────────────────────

    Renderer*       renderer()       { return renderer_.get(); }
    AudioEngine*    audio()          { return audio_.get(); }
    AssetManager*   assets()         { return assets_.get(); }
    AnimationSystem* animation()     { return animation_.get(); }
    NetworkClient*  network()        { return network_.get(); }
    JobSystem*      jobs()           { return jobs_.get(); }

private:
    // Subsystems (owned)
    std::unique_ptr<Renderer>       renderer_;
    std::unique_ptr<AudioEngine>    audio_;
    std::unique_ptr<AssetManager>   assets_;
    std::unique_ptr<AnimationSystem> animation_;
    std::unique_ptr<NetworkClient>  network_;
    std::unique_ptr<JobSystem>      jobs_;
    std::unique_ptr<AppLifecycle>   lifecycle_;

    // Current mini-game
    MiniGame* currentGame_ = nullptr;
    std::string currentGameId_;
};

} // namespace lux

#endif // LUX_GAME_GAME_FRAMEWORK_H
