package com.google.android.filament

import java.nio.ByteBuffer

class Engine {
    val transformManager: TransformManager = TransformManager()
    fun destroyEntity(entity: Int) {}
    fun destroyVertexBuffer(buffer: VertexBuffer) {}
    fun destroyIndexBuffer(buffer: IndexBuffer) {}
    fun destroyMaterialInstance(instance: MaterialInstance) {}
    fun destroyMaterial(material: Material) {}
}

class TransformManager {
    fun getInstance(entity: Int): Int = 0
    fun setTransform(instance: Int, matrix: FloatArray) {}
}

class Scene {
    var skybox: Any? = null
    var indirectLight: Any? = null
    fun addEntity(entity: Int) {}
    fun removeEntity(entity: Int) {}
    fun addEntities(entities: IntArray) {}
    fun removeEntities(entities: IntArray) {}
}

class EntityManager {
    fun create(): Int = 0
    fun destroy(entity: Int) {}
    companion object { @JvmStatic fun get(): EntityManager = EntityManager() }
}

class Box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float) {
    val center: FloatArray = floatArrayOf(cx, cy, cz)
    val halfExtent: FloatArray = floatArrayOf(hx, hy, hz)
}

class VertexBuffer {
    enum class VertexAttribute { POSITION, NORMAL, TANGENTS, COLOR, UV0, UV1 }
    enum class AttributeType { FLOAT2, FLOAT3, FLOAT4, HALF2, HALF4, UBYTE4, SHORT4 }
    fun setBufferAt(engine: Engine, index: Int, buffer: ByteBuffer) {}
    class Builder {
        fun vertexCount(count: Int): Builder = this
        fun bufferCount(count: Int): Builder = this
        fun attribute(a: VertexAttribute, i: Int, t: AttributeType, o: Int, s: Int): Builder = this
        fun build(engine: Engine): VertexBuffer = VertexBuffer()
    }
}

class IndexBuffer {
    fun setBuffer(engine: Engine, buffer: ByteBuffer) {}
    class Builder {
        enum class IndexType { USHORT, UINT }
        fun indexCount(count: Int): Builder = this
        fun bufferType(type: IndexType): Builder = this
        fun build(engine: Engine): IndexBuffer = IndexBuffer()
    }
}

class RenderableManager {
    enum class PrimitiveType { POINTS, LINES, LINE_STRIP, TRIANGLES }
    class Builder(count: Int) {
        fun boundingBox(box: Box): Builder = this
        fun geometry(slot: Int, type: PrimitiveType, v: VertexBuffer, i: IndexBuffer): Builder = this
        fun material(slot: Int, instance: MaterialInstance): Builder = this
        fun culling(enabled: Boolean): Builder = this
        fun castShadows(enabled: Boolean): Builder = this
        fun receiveShadows(enabled: Boolean): Builder = this
        fun build(engine: Engine, entity: Int) {}
    }
}

class MaterialInstance {
    fun setParameter(name: String, x: Float, y: Float, z: Float, w: Float) {}
    fun setParameter(name: String, x: Float, y: Float, z: Float) {}
    fun setParameter(name: String, value: Float) {}
}

class Material {
    fun createInstance(): MaterialInstance = MaterialInstance()
    class Builder {
        fun payload(buffer: ByteBuffer, size: Int): Builder = this
        fun build(engine: Engine): Material = Material()
    }
}
