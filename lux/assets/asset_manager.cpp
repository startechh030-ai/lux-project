#include "assets/asset_manager.h"
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#define LOG_TAG "LuxAssets"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lux {

AssetManager::AssetManager() {
    LOGI("AssetManager created");
}

AssetManager::~AssetManager() {
    shutdown();
}

bool AssetManager::initialize(void* androidAssetManager) {
    if (initialized_) return true;

    assetManager_ = androidAssetManager;
    if (!assetManager_) {
        LOGE("AssetManager: null AAssetManager");
        return false;
    }

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT && defined(LUX_USE_GLTFIO)
    // gltfLoader_ = new gltfio::AssetLoader(...);
    LOGI("gltfio AssetLoader created");
#endif

    initialized_ = true;
    LOGI("AssetManager initialized");
    return true;
}

void AssetManager::shutdown() {
    if (!initialized_) return;

    // Unload all models
    for (auto& model : models_) {
        if (model.asset) {
#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT
            // auto* fa = static_cast<gltfio::FilamentAsset*>(model.asset);
            // fa->releaseSourceData();
            // ...destroy entities
#endif
            model.asset = nullptr;
        }
    }

    // Free raw assets
    for (auto& raw : rawAssets_) {
        if (raw.data) {
            delete[] raw.data;
            raw.data = nullptr;
        }
    }

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT
    // delete static_cast<gltfio::AssetLoader*>(gltfLoader_);
    gltfLoader_ = nullptr;
#endif

    initialized_ = false;
    LOGI("AssetManager shut down");
}

uint32_t AssetManager::loadModel(const std::string& path) {
    if (!initialized_) return 0;

    for (uint32_t i = 0; i < kMaxModels; ++i) {
        auto& slot = models_[i];
        if (!slot.asset) {
            slot.path = path;

#if defined(LUX_USE_FILAMENT) && LUX_USE_FILAMENT && defined(LUX_USE_GLTFIO)
            // Open from Android assets
            AAsset* asset = AAssetManager_open(
                static_cast<AAssetManager*>(assetManager_),
                path.c_str(), AASSET_MODE_BUFFER);
            if (!asset) {
                LOGE("Failed to open asset: %s", path.c_str());
                return 0;
            }
            size_t size = AAsset_getLength(asset);
            const void* data = AAsset_getBuffer(asset);

            // auto* loader = static_cast<gltfio::AssetLoader*>(gltfLoader_);
            // slot.asset = loader->createAssetFromJson(data, size);
            // AAsset_close(asset);

            if (!slot.asset) {
                LOGE("Failed to parse GLB: %s", path.c_str());
                return 0;
            }
            LOGI("Loaded model: %s", path.c_str());
#else
            LOGI("Loaded model (stub): %s", path.c_str());
            slot.asset = reinterpret_cast<void*>(0x1);
#endif
            return i + 1;
        }
    }
    LOGE("Too many models (max %d)", kMaxModels);
    return 0;
}

const uint8_t* AssetManager::loadRaw(const std::string& path, size_t* outSize) {
    if (!initialized_ || !assetManager_) return nullptr;

    for (uint32_t i = 0; i < kMaxRawAssets; ++i) {
        auto& slot = rawAssets_[i];
        if (!slot.loaded) {
            slot.path = path;

            AAsset* asset = AAssetManager_open(
                static_cast<AAssetManager*>(assetManager_),
                path.c_str(), AASSET_MODE_BUFFER);
            if (!asset) {
                LOGE("Failed to open raw asset: %s", path.c_str());
                if (outSize) *outSize = 0;
                return nullptr;
            }

            size_t size = AAsset_getLength(asset);
            auto* buffer = new uint8_t[size];
            memcpy(buffer, AAsset_getBuffer(asset), size);
            AAsset_close(asset);

            slot.data = buffer;
            slot.size = size;
            slot.loaded = true;

#if LUX_ENABLE_HOT_RELOAD
            trackedAssets_.push_back(&slot);
#endif

            LOGI("Loaded raw asset: %s (%zu bytes)", path.c_str(), size);
            if (outSize) *outSize = size;
            return buffer;
        }
    }

    LOGE("Too many raw assets (max %d)", kMaxRawAssets);
    if (outSize) *outSize = 0;
    return nullptr;
}

void AssetManager::unload(uint32_t assetId) {
    (void)assetId;
    // TODO: implement per-ID unload
}

void AssetManager::setHotReloadCallback(AssetReloadCallback callback) {
    reloadCallback_ = std::move(callback);
}

void AssetManager::pollHotReload() {
#if LUX_ENABLE_HOT_RELOAD
    for (auto* info : trackedAssets_) {
        // Check file modification time and reload if changed
        // On Android, this requires polling via AAsset or file stat.
        // Stub for now.
    }
#endif
}

} // namespace lux
