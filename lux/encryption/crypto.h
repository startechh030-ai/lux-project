#ifndef LUX_ENCRYPTION_CRYPTO_H
#define LUX_ENCRYPTION_CRYPTO_H

#include <cstdint>
#include <string>
#include <vector>

namespace lux {

/// Cryptographic utilities wrapping libsodium.
/// Provides encryption, signing, hashing, and secure random generation.
class Crypto {
public:
    /// Initialize libsodium. Must be called once before any other crypto ops.
    static bool initialize();

    /// Returns true if libsodium was initialized successfully.
    static bool isInitialized();

    // ── Symmetric encryption (secret-key) ─────────────────────────────

    /// Encrypt plaintext using XChaCha20-Poly1305 (secret-key).
    /// Returns ciphertext + nonce prepended.
    static std::vector<uint8_t> encrypt(const uint8_t* key, size_t keyLen,
                                         const uint8_t* plaintext, size_t plaintextLen);

    /// Decrypt using XChaCha20-Poly1305.
    static std::vector<uint8_t> decrypt(const uint8_t* key, size_t keyLen,
                                         const uint8_t* ciphertext, size_t ciphertextLen);

    // ── Key generation ────────────────────────────────────────────────

    /// Generate a random symmetric key (32 bytes).
    static std::vector<uint8_t> generateSymmetricKey();

    /// Generate a random nonce (24 bytes).
    static std::vector<uint8_t> generateNonce();

    // ── Hashing ───────────────────────────────────────────────────────

    /// Compute BLAKE2b hash of data.
    static std::vector<uint8_t> hash(const uint8_t* data, size_t len,
                                      size_t hashLen = 32);

    /// Compute SHA-256 hash.
    static std::vector<uint8_t> sha256(const uint8_t* data, size_t len);

    // ── Secure random ─────────────────────────────────────────────────

    /// Fill buffer with cryptographically secure random bytes.
    static void randomBytes(uint8_t* buffer, size_t len);

    /// Generate a random integer in [0, upperBound).
    static uint32_t randomInt(uint32_t upperBound);

    // ── Utilities ─────────────────────────────────────────────────────

    /// Constant-time comparison of two byte arrays.
    static bool constantTimeEqual(const uint8_t* a, const uint8_t* b, size_t len);

private:
    Crypto() = delete;
    static bool initialized_;
};

} // namespace lux

#endif // LUX_ENCRYPTION_CRYPTO_H
