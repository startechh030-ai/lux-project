#include "audio/audio_engine.h"
#include <android/log.h>

#define LOG_TAG "LuxAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lux {

AudioEngine::AudioEngine() {
    LOGI("AudioEngine created");
}

AudioEngine::~AudioEngine() {
    shutdown();
}

bool AudioEngine::initialize(int sampleRate, int channels) {
    if (initialized_) return true;

#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
    // miniaudio is header-only — we'd include miniaudio.h here and init:
    // ma_engine_config config = ma_engine_config_init();
    // config.sampleRate = sampleRate;
    // config.channels = channels;
    // ma_result result = ma_engine_init(&config, &engine_);
    // if (result != MA_SUCCESS) { ... }

    LOGI("AudioEngine initialized (miniaudio): %d Hz, %d channels",
         sampleRate, channels);
#else
    LOGI("AudioEngine stub initialized (no miniaudio)");
#endif

    initialized_ = true;
    return true;
}

void AudioEngine::shutdown() {
    if (!initialized_) return;

    // Stop all playbacks
    for (auto& pb : playbacks_) {
        if (pb.active) {
            stop(static_cast<PlaybackHandle>(&pb - playbacks_ + 1));
        }
    }

    // Unload all sounds
    for (auto& sound : sounds_) {
        if (sound.loaded) {
            unloadSound(static_cast<SoundHandle>(&sound - sounds_ + 1));
        }
    }

#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
    // ma_engine_uninit(&engine_);
#endif

    initialized_ = false;
    LOGI("AudioEngine shut down");
}

SoundHandle AudioEngine::loadSound(const std::string& path) {
    for (uint32_t i = 0; i < kMaxSounds; ++i) {
        auto& slot = sounds_[i];
        if (!slot.loaded) {
            slot.name = path;
#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
            // ma_sound_init_from_file(&engine_, path.c_str(), 0, nullptr, nullptr,
            //                        (ma_sound**)&slot.data);
#endif
            slot.loaded = true;
            SoundHandle handle = i + 1;
            LOGI("Loaded sound: %s (handle=%u)", path.c_str(), handle);
            return handle;
        }
    }
    LOGE("Too many sounds (max %d)", kMaxSounds);
    return kInvalidSound;
}

SoundHandle AudioEngine::loadSoundFromMemory(const uint8_t* /*data*/, size_t /*size*/) {
    // TODO: implement with ma_sound_init_from_data
    LOGI("loadSoundFromMemory stub");
    return kInvalidSound;
}

void AudioEngine::unloadSound(SoundHandle sound) {
    if (sound == kInvalidSound || sound > kMaxSounds) return;
    auto& slot = sounds_[sound - 1];
    if (slot.loaded) {
#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
        // ma_sound_uninit((ma_sound*)slot.data);
#endif
        slot.loaded = false;
        slot.data = nullptr;
        slot.name.clear();
        LOGI("Unloaded sound handle=%u", sound);
    }
}

PlaybackHandle AudioEngine::play(SoundHandle sound, const PlaybackConfig& config) {
    if (sound == kInvalidSound || sound > kMaxSounds) return kInvalidPlayback;
    if (!sounds_[sound - 1].loaded) return kInvalidPlayback;

    for (uint32_t i = 0; i < kMaxPlaybacks; ++i) {
        auto& slot = playbacks_[i];
        if (!slot.active) {
            slot.sound = sound;
#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
            // ma_sound_init_copy(&engine_, (ma_sound*)sounds_[sound-1].data,
            //                    0, nullptr, (ma_sound**)&slot.instance);
            // ma_sound_set_volume((ma_sound*)slot.instance, config.volume);
            // ma_sound_set_looping((ma_sound*)slot.instance, config.looping);
            // ma_sound_set_pitch((ma_sound*)slot.instance, config.pitch);
            // ma_sound_start((ma_sound*)slot.instance);
#endif
            slot.active = true;
            PlaybackHandle handle = i + 1;
            LOGI("Playing sound=%u (playback=%u, vol=%.2f, loop=%d)",
                 sound, handle, config.volume, config.looping);
            return handle;
        }
    }
    LOGE("Too many active playbacks (max %d)", kMaxPlaybacks);
    return kInvalidPlayback;
}

void AudioEngine::stop(PlaybackHandle playback) {
    if (playback == kInvalidPlayback || playback > kMaxPlaybacks) return;
    auto& slot = playbacks_[playback - 1];
    if (slot.active) {
#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
        // ma_sound_stop((ma_sound*)slot.instance);
        // ma_sound_uninit((ma_sound*)slot.instance);
#endif
        slot.active = false;
        slot.instance = nullptr;
    }
}

void AudioEngine::setPaused(PlaybackHandle playback, bool paused) {
    if (playback == kInvalidPlayback || playback > kMaxPlaybacks) return;
    auto& slot = playbacks_[playback - 1];
    if (slot.active && slot.instance) {
#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
        // ma_sound_set_stopped((ma_sound*)slot.instance, paused);
#endif
    }
}

void AudioEngine::setMasterVolume(float volume) {
#if defined(LUX_USE_MINIAUDIO) && LUX_USE_MINIAUDIO
    // ma_engine_set_volume(&engine_, volume);
#else
    (void)volume;
#endif
}

void AudioEngine::update() {
    // Nothing to do in stub mode; miniaudio handles its own threading.
}

} // namespace lux
