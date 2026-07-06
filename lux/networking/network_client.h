#ifndef LUX_NETWORKING_NETWORK_CLIENT_H
#define LUX_NETWORKING_NETWORK_CLIENT_H

#include <cstdint>
#include <string>
#include <functional>

namespace lux {

/// Connection state.
enum class ConnectionState : uint8_t {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Disconnecting
};

/// Matchmaking result.
struct MatchInfo {
    std::string matchId;
    std::string label;
    int32_t size = 0;
};

/// Callback types for network events.
using OnConnectedCallback = std::function<void()>;
using OnDisconnectedCallback = std::function<void(const std::string& reason)>;
using OnMatchFoundCallback = std::function<void(const MatchInfo& match)>;
using OnMatchDataCallback = std::function<void(const uint8_t* data, size_t size)>;

/// Nakama C++ client wrapper for authentication, matchmaking, and real-time multiplayer.
class NetworkClient {
public:
    NetworkClient();
    ~NetworkClient();

    NetworkClient(const NetworkClient&) = delete;
    NetworkClient& operator=(const NetworkClient&) = delete;

    /// Initialize the network client with server details.
    bool initialize(const std::string& host, int32_t port,
                    const std::string& serverKey = "defaultkey",
                    bool useSSL = false);

    /// Shutdown and disconnect.
    void shutdown();

    /// Authenticate with the given token (device ID, custom token, etc.).
    void authenticateWithDevice(const std::string& deviceId);

    /// Authenticate with email + password.
    void authenticateWithEmail(const std::string& email,
                                const std::string& password);

    /// Disconnect from the server.
    void disconnect();

    /// Find a match using the Nakama matchmaker.
    void findMatch(int32_t minPlayers, int32_t maxPlayers,
                   const std::string& query = "*");

    /// Join a specific match by ID.
    void joinMatch(const std::string& matchId);

    /// Leave the current match.
    void leaveMatch();

    /// Send data to the current match.
    void sendMatchData(int64_t opCode, const uint8_t* data, size_t size);

    /// Process incoming messages (call once per frame).
    void update();

    // ── Callbacks ─────────────────────────────────────────────────────
    void setOnConnected(OnConnectedCallback cb);
    void setOnDisconnected(OnDisconnectedCallback cb);
    void setOnMatchFound(OnMatchFoundCallback cb);
    void setOnMatchData(OnMatchDataCallback cb);

    /// Returns the current connection state.
    ConnectionState state() const { return state_; }

    /// Returns the current user's session token.
    const std::string& sessionToken() const { return sessionToken_; }

    /// Returns true if initialized.
    bool isInitialized() const { return initialized_; }

private:
    bool initialized_ = false;
    ConnectionState state_ = ConnectionState::Disconnected;
    std::string sessionToken_;

    // Opaque Nakama client pointer
    void* client_ = nullptr;   ///< nakama::NClient*
    void* session_ = nullptr;  ///< nakama::NSession*
    void* socket_ = nullptr;   ///< nakama::NRtClient*

    // Callbacks
    OnConnectedCallback onConnected_;
    OnDisconnectedCallback onDisconnected_;
    OnMatchFoundCallback onMatchFound_;
    OnMatchDataCallback onMatchData_;
};

} // namespace lux

#endif // LUX_NETWORKING_NETWORK_CLIENT_H
