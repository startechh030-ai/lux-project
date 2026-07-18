package luxe.texture3d.app

import com.google.android.filament.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Godot/Prisma-inspired editor ground: procedural grid plus compact world origin. */
class EditorGrid(
    private val engine: Engine,
    private val scene: Scene,
    gridMaterialBuffer: ByteBuffer,
    lineMaterialBuffer: ByteBuffer
) {
    private val gridMaterial = Material.Builder().payload(gridMaterialBuffer, gridMaterialBuffer.remaining()).build(engine)
    private val lineMaterial = Material.Builder().payload(lineMaterialBuffer, lineMaterialBuffer.remaining()).build(engine)

    init {
        addGridPlane()
        addWorldOrigin()
    }

    private fun addGridPlane() {
        val y=0f; val z=-4f; val half=100f
        val points=floatArrayOf(-half,y,z-half, half,y,z-half, half,y,z+half, -half,y,z+half)
        val vb=createVertexBuffer(points)
        val ib=createIndexBuffer(shortArrayOf(0,1,2,0,2,3))
        val entity=EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f,y,z,half,0.05f,half))
            .geometry(0,RenderableManager.PrimitiveType.TRIANGLES,vb,ib)
            .material(0,gridMaterial.defaultInstance)
            .culling(false).castShadows(false).receiveShadows(false)
            .build(engine,entity)
        scene.addEntity(entity)
    }

    private fun addWorldOrigin() {
        val z=-4f; val y=0.012f; val radius=0.42f
        addLines(floatArrayOf(-radius,y,z,radius,y,z),0.72f,0.20f,0.22f,0.78f)
        addLines(floatArrayOf(0f,y,z-radius,0f,y,z+radius),0.18f,0.40f,0.80f,0.78f)
        addLines(floatArrayOf(0f,y,z,0f,y+0.42f,z),0.25f,0.72f,0.32f,0.82f)
        val c=0.055f
        addLines(floatArrayOf(-c,y+0.01f,z,c,y+0.01f,z,0f,y+0.01f,z-c,0f,y+0.01f,z+c),
            0.68f,0.82f,0.90f,0.9f)
    }

    private fun addLines(points:FloatArray,r:Float,g:Float,b:Float,a:Float) {
        val count=points.size/3
        val vb=createVertexBuffer(points)
        val ib=createIndexBuffer(ShortArray(count){it.toShort()})
        val instance=lineMaterial.createInstance().apply{setParameter("lineColor",r,g,b,a)}
        val entity=EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f,0.2f,-4f,1f,1f,1f))
            .geometry(0,RenderableManager.PrimitiveType.LINES,vb,ib)
            .material(0,instance).culling(false).castShadows(false).receiveShadows(false)
            .build(engine,entity)
        scene.addEntity(entity)
    }

    private fun createVertexBuffer(points:FloatArray):VertexBuffer {
        val data=ByteBuffer.allocateDirect(points.size*4).order(ByteOrder.nativeOrder())
        data.asFloatBuffer().put(points)
        return VertexBuffer.Builder().vertexCount(points.size/3).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION,0,VertexBuffer.AttributeType.FLOAT3,0,12)
            .build(engine).also{it.setBufferAt(engine,0,data)}
    }

    private fun createIndexBuffer(indices:ShortArray):IndexBuffer {
        val data=ByteBuffer.allocateDirect(indices.size*2).order(ByteOrder.nativeOrder())
        indices.forEach{data.putShort(it)};data.flip()
        return IndexBuffer.Builder().indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
            .also{it.setBuffer(engine,data)}
    }
}
