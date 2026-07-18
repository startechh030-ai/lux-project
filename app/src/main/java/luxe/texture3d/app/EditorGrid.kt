package luxe.texture3d.app

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real Filament geometry for the editor ground grid, world axes, and model-center cross. */
class EditorGrid(
    private val engine: Engine,
    private val scene: Scene,
    materialBuffer: ByteBuffer
) {
    private val material = Material.Builder()
        .payload(materialBuffer, materialBuffer.remaining())
        .build(engine)

    init {
        // ModelViewer normalizes imported assets around (0, 0, -4).
        val centerZ = -4f
        val groundY = -1f
        val half = 10
        val grid = ArrayList<Float>((half * 2 + 1) * 12)
        for (i in -half..half) {
            val p = i.toFloat()
            grid += listOf(-half.toFloat(), groundY, centerZ + p, half.toFloat(), groundY, centerZ + p)
            grid += listOf(p, groundY, centerZ - half, p, groundY, centerZ + half)
        }
        addLines(grid.toFloatArray(), 0.22f, 0.24f, 0.27f, 0.58f)

        // Axis guides intersect at the normalized model center.
        addLines(floatArrayOf(-2f, 0f, centerZ, 2f, 0f, centerZ), 0.90f, 0.22f, 0.24f, 0.95f) // X
        addLines(floatArrayOf(0f, -1f, centerZ, 0f, 3f, centerZ), 0.28f, 0.86f, 0.36f, 0.95f) // Y
        addLines(floatArrayOf(0f, 0f, centerZ - 2f, 0f, 0f, centerZ + 2f), 0.20f, 0.45f, 0.95f, 0.95f) // Z

        // Small center cross remains readable when an asset covers parts of the axes.
        val c = 0.10f
        addLines(floatArrayOf(-c,0f,centerZ,c,0f,centerZ, 0f,-c,centerZ,0f,c,centerZ),
            0.73f, 0.90f, 0.98f, 1f)
    }

    private fun addLines(points: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        val vertexCount = points.size / 3
        val vertices = ByteBuffer.allocateDirect(points.size * 4).order(ByteOrder.nativeOrder())
        vertices.asFloatBuffer().put(points)

        val indices = ByteBuffer.allocateDirect(vertexCount * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until vertexCount) indices.putShort(i.toShort())
        indices.flip()

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, vertices)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(vertexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)

        val instance = material.createInstance().apply { setParameter("lineColor", r, g, b, a) }
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, -4f, 12f, 5f, 12f))
            .geometry(0, RenderableManager.PrimitiveType.LINES, vertexBuffer, indexBuffer)
            .material(0, instance)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
    }
}
