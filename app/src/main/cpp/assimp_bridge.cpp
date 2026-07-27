#include <jni.h>
#include <android/log.h>
#include <assimp/Importer.hpp>
#include <assimp/Exporter.hpp>
#include <assimp/config.h>
#include <assimp/postprocess.h>
#include <assimp/scene.h>
#include <assimp/metadata.h>
#include <assimp/version.h>
#include <atomic>
#include <algorithm>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>

namespace {
std::mutex conversionMutex;
std::atomic<bool> cancelled{false};
constexpr const char* TAG = "LuxeAssimp";

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string extensionOf(const std::string& path) {
    std::string ext = std::filesystem::path(path).extension().string();
    if (!ext.empty() && ext[0] == '.') ext.erase(0, 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c){ return (char)std::tolower(c); });
    return ext;
}

unsigned flagsFor(const std::string& ext, std::string& profile) {
    const unsigned validation = aiProcess_SortByPType | aiProcess_FindInvalidData | aiProcess_ValidateDataStructure;
    if (ext == "gltf" || ext == "glb") { profile = "preserve_gltf"; return validation; }
    if (ext == "fbx" || ext == "dae" || ext == "blend") {
        profile = "preserve_scene";
        return validation | aiProcess_Triangulate | aiProcess_JoinIdenticalVertices |
               aiProcess_GenSmoothNormals | aiProcess_CalcTangentSpace |
               aiProcess_ImproveCacheLocality | aiProcess_LimitBoneWeights;
    }
    if (ext == "obj") {
        profile = "surface_mesh";
        return validation | aiProcess_Triangulate | aiProcess_JoinIdenticalVertices |
               aiProcess_GenSmoothNormals | aiProcess_CalcTangentSpace | aiProcess_ImproveCacheLocality;
    }
    profile = "static_geometry";
    return validation | aiProcess_Triangulate | aiProcess_JoinIdenticalVertices |
           aiProcess_GenSmoothNormals | aiProcess_ImproveCacheLocality;
}

const char* axisName(int axis) { return axis == 0 ? "X" : axis == 1 ? "Y" : axis == 2 ? "Z" : "unknown"; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_luxe_texture3d_app_AssimpBridge_nativeVersion(JNIEnv* env, jobject) {
    std::string version = std::to_string(aiGetVersionMajor()) + "." +
                          std::to_string(aiGetVersionMinor()) + "." +
                          std::to_string(aiGetVersionPatch());
    return toJString(env, version);
}

extern "C" JNIEXPORT void JNICALL
Java_luxe_texture3d_app_AssimpBridge_nativeCancel(JNIEnv*, jobject) {
    cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_luxe_texture3d_app_AssimpBridge_nativeConvertToGltf(
        JNIEnv* env, jobject, jstring sourcePathValue, jstring outputDirectoryValue) {
    std::lock_guard<std::mutex> lock(conversionMutex);
    cancelled.store(false, std::memory_order_relaxed);
    try {
        const std::string sourcePath = jstringToUtf8(env, sourcePathValue);
        const std::string outputDirectory = jstringToUtf8(env, outputDirectoryValue);
        if (sourcePath.empty() || outputDirectory.empty()) return toJString(env, "Missing source or output path");

        std::filesystem::create_directories(outputDirectory);
        Assimp::Importer importer;
        importer.SetPropertyInteger(AI_CONFIG_PP_SBP_REMOVE, aiPrimitiveType_POINT | aiPrimitiveType_LINE);
        std::string profile;
        const std::string sourceExtension = extensionOf(sourcePath);
        const unsigned flags = flagsFor(sourceExtension, profile);
        const aiScene* scene = importer.ReadFile(sourcePath, flags);
        if (!scene) return toJString(env, std::string("Import failed: ") + importer.GetErrorString());
        if (cancelled.load(std::memory_order_relaxed)) return toJString(env, "Cancelled");
        if (!scene->HasMeshes()) return toJString(env, "The source contains no mesh data");

        const std::filesystem::path outputPath = std::filesystem::path(outputDirectory) / "model.gltf";
        Assimp::Exporter exporter;
        const unsigned exportFlags = profile == "preserve_gltf" ? 0u :
                (aiProcess_JoinIdenticalVertices | aiProcess_Triangulate | aiProcess_SortByPType);
        const aiReturn status = exporter.Export(scene, "gltf2", outputPath.string(), exportFlags);
        if (status != aiReturn_SUCCESS) {
            return toJString(env, std::string("glTF export failed: ") + exporter.GetErrorString());
        }
        if (cancelled.load(std::memory_order_relaxed)) return toJString(env, "Cancelled");

        int upAxis = -1;
        double unitScale = 1.0;
        if (scene->mMetaData) {
            scene->mMetaData->Get("UpAxis", upAxis);
            if (!scene->mMetaData->Get("UnitScaleFactor", unitScale)) {
                float unitScaleFloat = 1.0f;
                if (scene->mMetaData->Get("UnitScaleFactor", unitScaleFloat)) unitScale = unitScaleFloat;
            }
        }
        std::ofstream metadata(std::filesystem::path(outputDirectory) / "conversion_native.json");
        metadata << "{\"profile\":\"" << profile << "\","
                 << "\"sourceExtension\":\"" << sourceExtension << "\","
                 << "\"sourceUpAxis\":\"" << axisName(upAxis) << "\","
                 << "\"unitScaleFactor\":" << unitScale << ","
                 << "\"animationCount\":" << scene->mNumAnimations << ","
                 << "\"cameraCount\":" << scene->mNumCameras << ","
                 << "\"lightCount\":" << scene->mNumLights << ","
                 << "\"materialCount\":" << scene->mNumMaterials << ","
                 << "\"hasBones\":";
        bool hasBones = false;
        for (unsigned i = 0; i < scene->mNumMeshes; ++i) if (scene->mMeshes[i]->HasBones()) { hasBones = true; break; }
        metadata << (hasBones ? "true" : "false") << ",\"warnings\":[";
        bool wroteWarning = false;
        auto warning = [&](const char* text) { if (wroteWarning) metadata << ','; metadata << '\"' << text << '\"'; wroteWarning = true; };
        if (sourceExtension == "stl" || sourceExtension == "ply" || sourceExtension == "dxf") warning("Source format commonly has limited material or texture data");
        if (sourceExtension == "blend") warning("Blender import is experimental; export GLB from Blender for best fidelity");
        if (scene->mNumMaterials <= 1) warning("Source contains one or no distinct material");
        metadata << "]}";
        metadata.close();

        __android_log_print(ANDROID_LOG_INFO, TAG, "Converted %s with %s profile", sourcePath.c_str(), profile.c_str());
        return toJString(env, "");
    } catch (const std::exception& error) {
        return toJString(env, std::string("Conversion exception: ") + error.what());
    } catch (...) {
        return toJString(env, "Unknown native conversion failure");
    }
}
