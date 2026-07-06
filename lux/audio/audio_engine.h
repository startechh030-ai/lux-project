#ifndef LUX_AUDIO_AUDIO_ENGINE_H
#define LUX_AUDIO_AUDIO_ENGINE_H

#include <cstdint>
#include <memory>
#include <string>

namespace lux {

/// Handle representing a loaded sound.
using SoundHandle = uint32_t;
constexpr SoundHandle kInvalidSound = 0;

/// Handle representing an active sound instance (playback).
using PlaybackHandle = uint32_t;
constexpr PlaybackHandle kInvalidPlayback = 0;

/// Audio playback configuration.
struct PlaybackConfig {
    float volume = 1.0f;
    float pitch = 1.0f;
    bool looping = false;
    float pan = 0.0f; // -1.0 (left) to 1.0 (right)
};

/// Audio engine using miniaudio as the backend.
class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    /// Initialize the audio engine with the given sample rate and channels.
    bool initialize(int sampleRate = 48000, int channels = 2);

    /// Shutdown the audio engine.
    void shutdown();

    /// Load a sound from a file path. Returns a handle or kInvalidSound.
    SoundHandle loadSound(const std::string& path);

    /// Load a sound from memory buffer.
    SoundHandle loadSoundFromMemory(const uint8_t* data, size_t size);

    /// Unload a sound, freeing its resources.
    void unloadSound(SoundHandle sound);

    /// Play a loaded sound. Returns a playback handle.
    PlaybackHandle play(SoundHandle sound, const PlaybackConfig& config = {});

    /// Stop a specific playback instance.
    void stop(PlaybackHandle playback);

    /// Pause/resume a playback instance.
    void setPaused(PlaybackHandle playback, bool paused);

    /// Set master volume (0.0 - 1.0).
    void setMasterVolume(float volume);

    /// Returns true if initialized.
    bool isInitialized() const { return initialized_; }

    /// Update the audio engine (call once per frame).
    void update();

private:
    bool initialized_ = false;

    // Opaque pointer to miniaudio engine context
    void* engineContext_ = nullptr;

    // Internal sound storage
    struct SoundSlot {
        std::string name;
        void* data = nullptr;   // ma_sound*
        size_t size = 0;
        bool loaded = false;
    };
    static constexpr int kMaxSounds = 256;
    SoundSlot sounds_[kMaxSounds] = {};
    uint32_t nextSoundId_ = 1;

    struct PlaybackSlot {
        SoundHandle sound = kInvalidSound;
        void* instance = nullptr; // ma_sound*
        bool active = false;
    };
    static constexpr int kMaxPlaybacks = 64;
    PlaybackSlot playbacks_[kMaxPlaybacks] = {};
    uint32_t nextPlaybackId_ = 1;
};

} // namespace lux

#endif // LUX_AUDIO_AUDIO_ENGINE_H
