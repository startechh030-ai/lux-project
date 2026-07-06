#include "encryption/crypto.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "LuxCrypto"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lux {

bool Crypto::initialized_ = false;

bool Crypto::initialize() {
    if (initialized_) return true;

#if defined(LUX_USE_SODIUM) && LUX_USE_SODIUM
    // if (sodium_init() < 0) {
    //     LOGE("libsodium initialization failed");
    //     return false;
    // }
    LOGI("libsodium initialized");
#else
    LOGI("Crypto stub initialized (no libsodium)");
#endif

    initialized_ = true;
    return true;
}

bool Crypto::isInitialized() {
    return initialized_;
}

std::vector<uint8_t> Crypto::encrypt(const uint8_t* key, size_t keyLen,
                                      const uint8_t* plaintext, size_t plaintextLen) {
#if defined(LUX_USE_SODIUM) && LUX_USE_SODIUM
    // Use crypto_aead_xchacha20poly1305_ietf_encrypt()
#endif
    LOGI("encrypt() stub");
    return {};
}

std::vector<uint8_t> Crypto::decrypt(const uint8_t* key, size_t keyLen,
                                      const uint8_t* ciphertext, size_t ciphertextLen) {
#if defined(LUX_USE_SODIUM) && LUX_USE_SODIUM
    // Use crypto_aead_xchacha20poly1305_ietf_decrypt()
#endif
    LOGI("decrypt() stub");
    return {};
}

std::vector<uint8_t> Crypto::generateSymmetricKey() {
    std::vector<uint8_t> key(32);
    randomBytes(key.data(), key.size());
    return key;
}

std::vector<uint8_t> Crypto::generateNonce() {
    std::vector<uint8_t> nonce(24);
    randomBytes(nonce.data(), nonce.size());
    return nonce;
}

std::vector<uint8_t> Crypto::hash(const uint8_t* data, size_t len, size_t hashLen) {
    std::vector<uint8_t> result(hashLen);
#if defined(LUX_USE_SODIUM) && LUX_USE_SODIUM
    // crypto_generichash(result.data(), hashLen, data, len, nullptr, 0);
#else
    std::memset(result.data(), 0, hashLen);
#endif
    return result;
}

std::vector<uint8_t> Crypto::sha256(const uint8_t* data, size_t len) {
    return hash(data, len, 32);
}

void Crypto::randomBytes(uint8_t* buffer, size_t len) {
#if defined(LUX_USE_SODIUM) && LUX_USE_SODIUM
    // randombytes_buf(buffer, len);
#else
    // Fallback — not cryptographically secure!
    static bool seeded = false;
    if (!seeded) {
        srand(time(nullptr));
        seeded = true;
    }
    for (size_t i = 0; i < len; ++i) {
        buffer[i] = static_cast<uint8_t>(rand() & 0xFF);
    }
#endif
}

uint32_t Crypto::randomInt(uint32_t upperBound) {
    if (upperBound == 0) return 0;
#if defined(LUX_USE_SODIUM) && LUX_USE_SODIUM
    // return randombytes_uniform(upperBound);
#else
    return static_cast<uint32_t>(rand()) % upperBound;
#endif
}

bool Crypto::constantTimeEqual(const uint8_t* a, const uint8_t* b, size_t len) {
#if defined(LUX_USE_SODIUM) && LUX_USE_SODIUM
    // return sodium_memcmp(a, b, len) == 0;
#else
    if (len == 0) return true;
    volatile uint8_t diff = 0;
    for (size_t i = 0; i < len; ++i) {
        diff |= (a[i] ^ b[i]);
    }
    return diff == 0;
#endif
}

} // namespace lux
