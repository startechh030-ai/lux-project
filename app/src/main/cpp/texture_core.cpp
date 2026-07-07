#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <cstdint>
#include <cstring>
#include <android/log.h>

#define TINYGLTF_IMPLEMENTATION
#define TINYGLTF_NO_STB_IMAGE
#define TINYGLTF_NO_STB_IMAGE_WRITE
#include "tiny_gltf.h"
#include "xatlas.h"

#define LOG_TAG "TextureCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct MeshData {
    std::vector<float> positions; // xyz
    std::vector<float> normals;   // xyz, optional
    std::vector<float> uvs;       // uv, optional/generated
    std::vector<uint32_t> indices;
    int meshCount = 0;
    int primitiveCount = 0;
};

struct TextureProject {
    MeshData mesh;
    std::string sourcePath;
    bool loaded = false;
    bool unwrapped = false;
};

static TextureProject* ptr(jlong handle) {
    return reinterpret_cast<TextureProject*>(handle);
}

static const unsigned char* accessorData(const tinygltf::Model& model, const tinygltf::Accessor& accessor, size_t* strideOut) {
    const auto& view = model.bufferViews[accessor.bufferView];
    const auto& buffer = model.buffers[view.buffer];
    const size_t componentSize = tinygltf::GetComponentSizeInBytes(accessor.componentType);
    const size_t components = tinygltf::GetNumComponentsInType(accessor.type);
    const size_t defaultStride = componentSize * components;
    *strideOut = accessor.ByteStride(view);
    if (*strideOut == 0) *strideOut = defaultStride;
    return buffer.data.data() + view.byteOffset + accessor.byteOffset;
}

static bool readFloatVec3(const tinygltf::Model& model, int accessorIndex, std::vector<float>& out) {
    if (accessorIndex < 0) return false;
    const auto& accessor = model.accessors[accessorIndex];
    if (accessor.componentType != TINYGLTF_COMPONENT_TYPE_FLOAT || accessor.type != TINYGLTF_TYPE_VEC3) return false;
    size_t stride = 0;
    const unsigned char* data = accessorData(model, accessor, &stride);
    out.reserve(out.size() + accessor.count * 3);
    for (size_t i = 0; i < accessor.count; ++i) {
        const float* f = reinterpret_cast<const float*>(data + i * stride);
        out.push_back(f[0]); out.push_back(f[1]); out.push_back(f[2]);
    }
    return true;
}

static bool readFloatVec2(const tinygltf::Model& model, int accessorIndex, std::vector<float>& out) {
    if (accessorIndex < 0) return false;
    const auto& accessor = model.accessors[accessorIndex];
    if (accessor.componentType != TINYGLTF_COMPONENT_TYPE_FLOAT || accessor.type != TINYGLTF_TYPE_VEC2) return false;
    size_t stride = 0;
    const unsigned char* data = accessorData(model, accessor, &stride);
    out.reserve(out.size() + accessor.count * 2);
    for (size_t i = 0; i < accessor.count; ++i) {
        const float* f = reinterpret_cast<const float*>(data + i * stride);
        out.push_back(f[0]); out.push_back(f[1]);
    }
    return true;
}

static bool readIndices(const tinygltf::Model& model, int accessorIndex, uint32_t vertexBase, std::vector<uint32_t>& out) {
    if (accessorIndex < 0) return false;
    const auto& accessor = model.accessors[accessorIndex];
    size_t stride = 0;
    const unsigned char* data = accessorData(model, accessor, &stride);
    out.reserve(out.size() + accessor.count);
    for (size_t i = 0; i < accessor.count; ++i) {
        const unsigned char* p = data + i * stride;
        uint32_t value = 0;
        switch (accessor.componentType) {
            case TINYGLTF_COMPONENT_TYPE_UNSIGNED_BYTE:  value = *reinterpret_cast<const uint8_t*>(p); break;
            case TINYGLTF_COMPONENT_TYPE_UNSIGNED_SHORT: value = *reinterpret_cast<const uint16_t*>(p); break;
            case TINYGLTF_COMPONENT_TYPE_UNSIGNED_INT:   value = *reinterpret_cast<const uint32_t*>(p); break;
            default: return false;
        }
        out.push_back(vertexBase + value);
    }
    return true;
}

static bool loadGltfFile(const std::string& path, MeshData& outMesh) {
    tinygltf::TinyGLTF loader;
    tinygltf::Model model;
    std::string err, warn;
    bool ok = false;
    if (path.size() >= 4 && path.substr(path.size() - 4) == ".glb") {
        ok = loader.LoadBinaryFromFile(&model, &err, &warn, path);
    } else {
        ok = loader.LoadASCIIFromFile(&model, &err, &warn, path);
    }
    if (!warn.empty()) LOGI("tinygltf warn: %s", warn.c_str());
    if (!err.empty()) LOGE("tinygltf err: %s", err.c_str());
    if (!ok) return false;

    outMesh = MeshData{};
    outMesh.meshCount = static_cast<int>(model.meshes.size());

    for (const auto& mesh : model.meshes) {
        for (const auto& prim : mesh.primitives) {
            if (prim.mode != TINYGLTF_MODE_TRIANGLES) continue;
            auto posIt = prim.attributes.find("POSITION");
            if (posIt == prim.attributes.end()) continue;

            const uint32_t vertexBase = static_cast<uint32_t>(outMesh.positions.size() / 3);
            const size_t posBefore = outMesh.positions.size();
            if (!readFloatVec3(model, posIt->second, outMesh.positions)) continue;
            const size_t vertexAdded = (outMesh.positions.size() - posBefore) / 3;

            auto normalIt = prim.attributes.find("NORMAL");
            if (normalIt != prim.attributes.end()) readFloatVec3(model, normalIt->second, outMesh.normals);
            if (outMesh.normals.size() / 3 < outMesh.positions.size() / 3) {
                outMesh.normals.resize(outMesh.positions.size(), 0.0f);
            }

            auto uvIt = prim.attributes.find("TEXCOORD_0");
            if (uvIt != prim.attributes.end()) readFloatVec2(model, uvIt->second, outMesh.uvs);
            if (outMesh.uvs.size() / 2 < outMesh.positions.size() / 3) {
                outMesh.uvs.resize((outMesh.positions.size() / 3) * 2, 0.0f);
            }

            if (prim.indices >= 0) {
                readIndices(model, prim.indices, vertexBase, outMesh.indices);
            } else {
                for (uint32_t i = 0; i < vertexAdded; ++i) outMesh.indices.push_back(vertexBase + i);
            }
            outMesh.primitiveCount++;
        }
    }
    return !outMesh.positions.empty() && !outMesh.indices.empty();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_arena_texturepaint_NativeTextureCore_createProject(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new TextureProject());
}

extern "C" JNIEXPORT void JNICALL
Java_com_arena_texturepaint_NativeTextureCore_destroyProject(JNIEnv*, jobject, jlong handle) {
    delete ptr(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arena_texturepaint_NativeTextureCore_loadGltf(JNIEnv* env, jobject, jlong handle, jstring path_) {
    auto* project = ptr(handle);
    if (!project) return JNI_FALSE;
    const char* cpath = env->GetStringUTFChars(path_, nullptr);
    std::string path(cpath ? cpath : "");
    env->ReleaseStringUTFChars(path_, cpath);
    project->sourcePath = path;
    project->loaded = loadGltfFile(path, project->mesh);
    project->unwrapped = false;
    return project->loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_arena_texturepaint_NativeTextureCore_getMeshStats(JNIEnv* env, jobject, jlong handle) {
    jint values[5] = {0, 0, 0, 0, 0};
    auto* project = ptr(handle);
    if (project) {
        values[0] = static_cast<jint>(project->mesh.positions.size() / 3);
        values[1] = static_cast<jint>(project->mesh.indices.size());
        values[2] = project->mesh.meshCount;
        values[3] = project->mesh.primitiveCount;
        values[4] = (!project->mesh.uvs.empty() || project->unwrapped) ? 1 : 0;
    }
    jintArray arr = env->NewIntArray(5);
    env->SetIntArrayRegion(arr, 0, 5, values);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arena_texturepaint_NativeTextureCore_unwrapAuto(JNIEnv*, jobject, jlong handle, jint atlasSize) {
    auto* project = ptr(handle);
    if (!project || !project->loaded) return JNI_FALSE;
    MeshData& mesh = project->mesh;
    if (mesh.positions.empty() || mesh.indices.empty()) return JNI_FALSE;

    xatlas::Atlas* atlas = xatlas::Create();
    xatlas::MeshDecl decl{};
    decl.vertexCount = static_cast<uint32_t>(mesh.positions.size() / 3);
    decl.vertexPositionData = mesh.positions.data();
    decl.vertexPositionStride = sizeof(float) * 3;
    if (!mesh.normals.empty()) {
        decl.vertexNormalData = mesh.normals.data();
        decl.vertexNormalStride = sizeof(float) * 3;
    }
    decl.indexCount = static_cast<uint32_t>(mesh.indices.size());
    decl.indexData = mesh.indices.data();
    decl.indexFormat = xatlas::IndexFormat::UInt32;

    xatlas::AddMeshError addMeshError = xatlas::AddMesh(atlas, decl);
    if (addMeshError != xatlas::AddMeshError::Success) {
        LOGE("xatlas AddMesh failed: %s", xatlas::StringForEnum(addMeshError));
        xatlas::Destroy(atlas);
        return JNI_FALSE;
    }
    xatlas::AddMeshJoin(atlas);

    xatlas::ChartOptions chartOptions{};
    xatlas::PackOptions packOptions{};
    packOptions.resolution = static_cast<uint32_t>(atlasSize > 0 ? atlasSize : 2048);
    xatlas::Generate(atlas, chartOptions, packOptions);

    if (atlas->meshCount < 1) {
        xatlas::Destroy(atlas);
        return JNI_FALSE;
    }

    const xatlas::Mesh& xaMesh = atlas->meshes[0];
    MeshData unwrapped;
    unwrapped.meshCount = 1;
    unwrapped.primitiveCount = 1;
    unwrapped.positions.resize(xaMesh.vertexCount * 3);
    unwrapped.normals.resize(xaMesh.vertexCount * 3);
    unwrapped.uvs.resize(xaMesh.vertexCount * 2);
    unwrapped.indices.resize(xaMesh.indexCount);

    for (uint32_t i = 0; i < xaMesh.vertexCount; ++i) {
        const xatlas::Vertex& v = xaMesh.vertexArray[i];
        uint32_t original = v.xref;
        unwrapped.positions[i * 3 + 0] = mesh.positions[original * 3 + 0];
        unwrapped.positions[i * 3 + 1] = mesh.positions[original * 3 + 1];
        unwrapped.positions[i * 3 + 2] = mesh.positions[original * 3 + 2];
        if (!mesh.normals.empty() && original * 3 + 2 < mesh.normals.size()) {
            unwrapped.normals[i * 3 + 0] = mesh.normals[original * 3 + 0];
            unwrapped.normals[i * 3 + 1] = mesh.normals[original * 3 + 1];
            unwrapped.normals[i * 3 + 2] = mesh.normals[original * 3 + 2];
        }
        unwrapped.uvs[i * 2 + 0] = v.uv[0] / static_cast<float>(atlas->width);
        unwrapped.uvs[i * 2 + 1] = v.uv[1] / static_cast<float>(atlas->height);
    }
    for (uint32_t i = 0; i < xaMesh.indexCount; ++i) unwrapped.indices[i] = xaMesh.indexArray[i];

    mesh = std::move(unwrapped);
    project->unwrapped = true;
    xatlas::Destroy(atlas);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arena_texturepaint_NativeTextureCore_exportDebugInfo(JNIEnv* env, jobject, jlong handle, jstring path_) {
    auto* project = ptr(handle);
    if (!project) return JNI_FALSE;
    const char* cpath = env->GetStringUTFChars(path_, nullptr);
    std::string path(cpath ? cpath : "");
    env->ReleaseStringUTFChars(path_, cpath);

    std::ofstream out(path);
    if (!out) return JNI_FALSE;
    out << "TexturePaintMobile native project\n";
    out << "source=" << project->sourcePath << "\n";
    out << "loaded=" << project->loaded << "\n";
    out << "unwrapped=" << project->unwrapped << "\n";
    out << "vertices=" << project->mesh.positions.size() / 3 << "\n";
    out << "indices=" << project->mesh.indices.size() << "\n";
    out << "uvs=" << project->mesh.uvs.size() / 2 << "\n";
    return JNI_TRUE;
}
