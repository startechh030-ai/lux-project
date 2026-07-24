#include <jni.h>
#include <android/log.h>
#include <assimp/Importer.hpp>
#include <assimp/Exporter.hpp>
#include <assimp/config.h>
#include <assimp/postprocess.h>
#include <assimp/scene.h>
#include <assimp/version.h>
#include <atomic>
#include <filesystem>
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
        const unsigned flags = aiProcess_Triangulate |
                               aiProcess_JoinIdenticalVertices |
                               aiProcess_GenSmoothNormals |
                               aiProcess_CalcTangentSpace |
                               aiProcess_ImproveCacheLocality |
                               aiProcess_LimitBoneWeights |
                               aiProcess_SortByPType |
                               aiProcess_FindInvalidData |
                               aiProcess_ValidateDataStructure;
        const aiScene* scene = importer.ReadFile(sourcePath, flags);
        if (!scene) return toJString(env, std::string("Import failed: ") + importer.GetErrorString());
        if (cancelled.load(std::memory_order_relaxed)) return toJString(env, "Cancelled");
        if (!scene->HasMeshes()) return toJString(env, "The source contains no mesh data");

        const std::filesystem::path outputPath = std::filesystem::path(outputDirectory) / "model.gltf";
        Assimp::Exporter exporter;
        const aiReturn status = exporter.Export(scene, "gltf2", outputPath.string(),
                aiProcess_JoinIdenticalVertices | aiProcess_Triangulate | aiProcess_SortByPType);
        if (status != aiReturn_SUCCESS) {
            return toJString(env, std::string("glTF export failed: ") + exporter.GetErrorString());
        }
        if (cancelled.load(std::memory_order_relaxed)) return toJString(env, "Cancelled");
        __android_log_print(ANDROID_LOG_INFO, TAG, "Converted %s to %s", sourcePath.c_str(), outputPath.string().c_str());
        return toJString(env, "");
    } catch (const std::exception& error) {
        return toJString(env, std::string("Conversion exception: ") + error.what());
    } catch (...) {
        return toJString(env, "Unknown native conversion failure");
    }
}
