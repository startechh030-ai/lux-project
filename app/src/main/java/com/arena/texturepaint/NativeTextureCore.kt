package com.arena.texturepaint

object NativeTextureCore {
    init { System.loadLibrary("texturecore") }

    external fun createProject(): Long
    external fun destroyProject(handle: Long)

    /** Loads GLB/GLTF through tinygltf. Returns true when geometry was parsed. */
    external fun loadGltf(handle: Long, absolutePath: String): Boolean

    /** [vertexCount, indexCount, meshCount, primitiveCount, hasUvInt] */
    external fun getMeshStats(handle: Long): IntArray

    /** Runs xatlas on the loaded geometry and stores generated UVs native-side. */
    external fun unwrapAuto(handle: Long, atlasSize: Int): Boolean

    /** Writes current native project debug info; real GLB export hook is here. */
    external fun exportDebugInfo(handle: Long, absolutePath: String): Boolean
}
