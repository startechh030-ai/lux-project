#ifndef LUX_ASSETS_ASSET_MANAGER_H
#define LUX_ASSETS_ASSET_MANAGER_H

#include <cstdint>
#include <string>
#include <functional>
#include <unordered_map>
#include <vector>

namespace lux {

/// Callback invoked when a hot-reloaded asset changes.
using AssetReloadCallback = std::function<void(const std::string& path)>;

/// Describes a loaded asset.
struct AssetInfo {
    std::string path;
    uint8_t* data = nullptr;
    size_t size = 0;
    int64_t lastModified = 0;
    bool loaded = false;
};

/// Asset manager: loads GLB models (via Filament gltfio), textures, and raw data.
/// Supports hot-reloading for rapid iteration.
class AssetManager {
public:
    AssetManager();
    ~AssetManager();

    AssetManager(const AssetManager&) = delete;
    AssetManager& operator=(const AssetManager&) = delete;

    /// Initialize with an Android asset manager (AAssetManager).
    bool initialize(void* androidAssetManager);

    /// Shutdown and free all assets.
    void shutdown();

    /// Load a GLB/GLTF model. Returns an opaque handle > 0, or 0 on failure.
    uint32_t loadModel(const std::string& path);

    /// Load raw file bytes.
    const uint8_t* loadRaw(const std::string& path, size_t* outSize);

    /// Unload a specific asset.
    void unload(uint32_t assetId);

    /// Set a callback for hot-reload notifications.
    void setHotReloadCallback(AssetReloadCallback callback);

    /// Poll for file changes and trigger hot-reload (call once per frame).
    /// Only works if LUX_ENABLE_HOT_RELOAD is defined.
    void pollHotReload();

    /// Access the underlying gltfio AssetLoader (opaque).
    void* gltfLoader() const { return gltfLoader_; }

    /// Returns true if initialized.
    bool isInitialized() const { return initialized_; }

private:
    bool initialized_ = false;
    void* assetManager_ = nullptr;  ///< AAssetManager*
    void* gltfLoader_ = nullptr;    ///< gltfio::AssetLoader*

    struct ModelAsset {
        std::string path;
        void* asset = nullptr;      ///< gltfio::FilamentAsset*
        void* renderable = nullptr; ///< utils::Entity
    };

    static constexpr int kMaxModels = 128;
    static constexpr int kMaxRawAssets = 256;

    ModelAsset models_[kMaxModels] = {};
    AssetInfo rawAssets_[kMaxRawAssets] = {};
    uint32_t nextModelId_ = 1;
    uint32_t nextRawId_ = 1;

    std::vector<AssetInfo*> trackedAssets_;
    AssetReloadCallback reloadCallback_;
};

} // namespace lux

#endif // LUX_ASSETS_ASSET_MANAGER_H
