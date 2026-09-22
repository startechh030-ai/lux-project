package com.google.android.filament.gltfio

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import java.nio.Buffer
import java.nio.ByteBuffer

class FilamentAsset {
    val boundingBox: Box = Box(0f, 0f, 0f, 1f, 1f, 1f)
    val root: Int = 0
    val entities: IntArray = IntArray(0)
    val renderableEntities: IntArray = IntArray(0)
    val lightEntities: IntArray = IntArray(0)
    val resourceUris: Array<String> = emptyArray()
    fun popRenderables(ready: IntArray): Int = 0
    fun releaseSourceData() {}
}

interface MaterialProvider {
    fun destroyMaterials()
    fun destroy()
}

class UbershaderProvider(engine: Engine) : MaterialProvider {
    override fun destroyMaterials() {}
    override fun destroy() {}
}

class AssetLoader(engine: Engine, provider: MaterialProvider, entityManager: EntityManager) {
    fun createAsset(buffer: Buffer): FilamentAsset? = null
    fun destroyAsset(asset: FilamentAsset) {}
    fun destroy() {}
}

class ResourceLoader(engine: Engine, normalizeSkinningWeights: Boolean) {
    fun addResourceData(uri: String, buffer: ByteBuffer) {}
    fun asyncBeginLoad(asset: FilamentAsset) {}
    fun asyncUpdateLoad() {}
    fun asyncCancelLoad() {}
    fun evictResourceData() {}
    fun destroy() {}
}
