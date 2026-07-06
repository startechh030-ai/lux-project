#include "networking/network_client.h"
#include <android/log.h>

#define LOG_TAG "LuxNet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lux {

NetworkClient::NetworkClient() {
    LOGI("NetworkClient created");
}

NetworkClient::~NetworkClient() {
    shutdown();
}

bool NetworkClient::initialize(const std::string& host, int32_t port,
                                const std::string& serverKey, bool useSSL) {
    if (initialized_) return true;

#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // NClientParameters params;
    // params.host = host;
    // params.port = port;
    // params.serverKey = serverKey;
    // params.ssl = useSSL;
    // client_ = nakama::NClient::create(params);
    LOGI("Nakama client created: %s:%d (SSL=%d)", host.c_str(), port, useSSL);
#else
    LOGI("NetworkClient stub initialized (no Nakama): %s:%d", host.c_str(), port);
#endif

    initialized_ = true;
    return true;
}

void NetworkClient::shutdown() {
    if (!initialized_) return;

#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // if (socket_) { socket_->disconnect(); delete socket_; socket_ = nullptr; }
    // delete static_cast<nakama::NSession*>(session_);
    // delete static_cast<nakama::NClient*>(client_);
#endif

    client_ = nullptr;
    session_ = nullptr;
    socket_ = nullptr;
    initialized_ = false;
    state_ = ConnectionState::Disconnected;
    LOGI("NetworkClient shut down");
}

void NetworkClient::authenticateWithDevice(const std::string& deviceId) {
    state_ = ConnectionState::Connecting;
    LOGI("Authenticating with device ID: %s", deviceId.c_str());

#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // auto success = [this](nakama::NSessionPtr session) {
    //     session_ = session.release();
    //     sessionToken_ = session_->getAuthToken();
    //     state_ = ConnectionState::Connected;
    //     if (onConnected_) onConnected_();
    // };
    // auto failure = [](const nakama::NError& error) {
    //     LOGE("Auth failed: %s", error.message.c_str());
    // };
    // static_cast<nakama::NClient*>(client_)->authenticateDevice(deviceId, {}, success, failure);
#endif
}

void NetworkClient::authenticateWithEmail(const std::string& email,
                                           const std::string& password) {
    state_ = ConnectionState::Connecting;
    LOGI("Authenticating with email: %s", email.c_str());

#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // auto success = [this](nakama::NSessionPtr session) { ... };
    // auto failure = [](const nakama::NError& error) { ... };
    // static_cast<nakama::NClient*>(client_)->authenticateEmail(email, password, {}, success, failure);
#endif
}

void NetworkClient::disconnect() {
    state_ = ConnectionState::Disconnecting;
#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // if (socket_) socket_->disconnect();
#endif
    state_ = ConnectionState::Disconnected;
    LOGI("Disconnected");
    if (onDisconnected_) onDisconnected_("user disconnect");
}

void NetworkClient::findMatch(int32_t minPlayers, int32_t maxPlayers,
                               const std::string& query) {
    LOGI("Finding match: %d-%d players, query='%s'",
         minPlayers, maxPlayers, query.c_str());

#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // auto* rtClient = getOrCreateRtClient();
    // rtClient->addMatchmaker(minPlayers, maxPlayers, query, {}, ...);
#endif
}

void NetworkClient::joinMatch(const std::string& matchId) {
    LOGI("Joining match: %s", matchId.c_str());
#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // auto* rtClient = getOrCreateRtClient();
    // rtClient->joinMatch(matchId, ...);
#endif
}

void NetworkClient::leaveMatch() {
    LOGI("Leaving current match");
#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // if (rtClient) rtClient->leaveMatch(matchId, ...);
#endif
}

void NetworkClient::sendMatchData(int64_t opCode, const uint8_t* data, size_t size) {
#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // if (rtClient) rtClient->sendMatchData(matchId, opCode, std::string(data, data + size), {});
#else
    LOGI("sendMatchData stub: op=%lld, size=%zu", (long long)opCode, size);
#endif
}

void NetworkClient::update() {
#if defined(LUX_USE_NAKAMA) && LUX_USE_NAKAMA
    // auto* rtClient = static_cast<nakama::NRtClient*>(socket_);
    // if (rtClient) rtClient->tick();
#endif
}

void NetworkClient::setOnConnected(OnConnectedCallback cb) {
    onConnected_ = std::move(cb);
}

void NetworkClient::setOnDisconnected(OnDisconnectedCallback cb) {
    onDisconnected_ = std::move(cb);
}

void NetworkClient::setOnMatchFound(OnMatchFoundCallback cb) {
    onMatchFound_ = std::move(cb);
}

void NetworkClient::setOnMatchData(OnMatchDataCallback cb) {
    onMatchData_ = std::move(cb);
}

} // namespace lux
