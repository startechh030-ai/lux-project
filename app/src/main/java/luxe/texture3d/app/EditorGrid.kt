package luxe.texture3d.app

import com.google.android.filament.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Procedural ground grid plus a compact ground-level origin marker. */
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
        val z=-4f; val y=-0.995f; val r=0.55f
        addLines(floatArrayOf(-r,y,z,r,y,z),0.92f,0.20f,0.22f,1f)
        addLines(floatArrayOf(0f,y,z-r,0f,y,z+r),0.18f,0.43f,0.96f,1f)
        // Short, bold-looking vertical locator instead of the old full-screen axes.
        addLines(floatArrayOf(0f,y,z,0f,y+0.55f,z),0.25f,0.92f,0.36f,1f)
        val c=0.07f
        addLines(floatArrayOf(-c,y+0.01f,z,c,y+0.01f,z,0f,y+0.01f,z-c,0f,y+0.01f,z+c),
            0.76f,0.92f,1f,1f)
    }

    private fun addGridPlane() {
        val y=-1f; val z=-4f; val h=100f
        val points=floatArrayOf(-h,y,z-h, h,y,z-h, h,y,z+h, -h,y,z+h)
        val indices=shortArrayOf(0,1,2,0,2,3)
        val vb=createVertexBuffer(points)
        val ib=createIndexBuffer(indices)
        val entity=EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f,y,z,h,0.1f,h))
            .geometry(0,RenderableManager.PrimitiveType.TRIANGLES,vb,ib)
            .material(0,gridMaterial.defaultInstance)
            .culling(false).castShadows(false).receiveShadows(false)
            .build(engine,entity)
        scene.addEntity(entity)
    }

    private fun addLines(points:FloatArray,r:Float,g:Float,b:Float,a:Float) {
        val count=points.size/3
        val indices=ShortArray(count){it.toShort()}
        val vb=createVertexBuffer(points); val ib=createIndexBuffer(indices)
        val mi=lineMaterial.createInstance().apply{setParameter("lineColor",r,g,b,a)}
        val entity=EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f,0f,-4f,2f,2f,2f))
            .geometry(0,RenderableManager.PrimitiveType.LINES,vb,ib)
            .material(0,mi).culling(false).castShadows(false).receiveShadows(false)
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
        return IndexBuffer.Builder().indexCount(indices.size).bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine).also{it.setBuffer(engine,data)}
    }
}
