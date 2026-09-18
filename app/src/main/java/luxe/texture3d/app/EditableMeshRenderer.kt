package luxe.texture3d.app

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws an [EditMesh] and its Edit-mode overlay using Filament.
 *
 * ## The GPU round trip
 *
 * This is the other half of the missing pipeline. Luxe currently renders exclusively
 * through gltfio, which owns its vertex and index buffers and exposes no way to change
 * them. So an edited mesh cannot be shown by the existing asset — it has to be drawn by a
 * renderable this class owns, whose buffers are rebuilt whenever the operator changes the
 * mesh:
 *
 * ```
 * EditMesh ──► VertexBuffer / IndexBuffer ──► RenderableManager ──► Scene
 *     ▲                                                              │
 *     └──────────────── operator result ─────────────────────────────┘
 * ```
 *
 * ## Overlay strategy
 *
 * Selected elements are drawn as lines, reusing the existing `luxe_lines.filamat` material
 * the same way [SelectionBoundsRenderer] and [EditorGrid] already do. Points would be
 * simpler but Filament does not expose `glPointSize`, so selected vertices are drawn as
 * small three-axis crosses whose size is derived from the mesh bounds — resolution
 * independent, and it works identically on every backend.
 *
 * Two line renderables are used (dim wireframe, bright selection) because
 * `luxe_lines.filamat` takes its colour as a uniform, not per-vertex.
 *
 * ## Integration notes (verify against Filament 1.69.4)
 *
 * - [surfaceMaterial] should come from gltfio's `UbershaderProvider` so the edited mesh
 *   keeps the project's PBR look. If the chosen material samples a normal map, a TANGENTS
 *   buffer must be supplied too; a simple unlit/lit material avoids the question.
 * - Buffers are destroyed and recreated when the vertex count changes. Filament has no
 *   partial resize, which is why [update] checks the counts before reallocating.
 */
class EditableMeshRenderer(
    private val engine: Engine,
    private val scene: Scene,
    private val surfaceMaterial: MaterialInstance,
    lineMaterialBuffer: ByteBuffer
) {
    private val dimLines: LineMaterial
    private val brightLines: LineMaterial

    private var surfaceEntity = 0
    private var wireEntity = 0
    private var selectionEntity = 0
    private var vertices: VertexBuffer? = null
    private var normals: VertexBuffer? = null
    private var indices: IndexBuffer? = null
    private var wireVertices: VertexBuffer? = null
    private var wireIndices: IndexBuffer? = null
    private var selVertices: VertexBuffer? = null
    private var selIndices: IndexBuffer? = null

    private var cachedVertexCount = -1
    private var cachedIndexCount = -1

    init {
        val a = lineMaterialBuffer.duplicate()
        val b = lineMaterialBuffer.duplicate()
        dimLines = LineMaterial(engine, a, 0.30f, 0.36f, 0.44f)
        brightLines = LineMaterial(engine, b, 1.0f, 0.58f, 0.16f)
    }

    // -------------------------------------------------------------- surface

    /** Pushes [mesh] to the GPU. Cheap when only positions moved and counts are stable. */
    fun update(mesh: EditMesh) {
        if (mesh.vertexCount == 0 || mesh.faceCount == 0) {
            clearSurface()
            return
        }
        val positionData = directFloatBuffer(mesh.positions)
        val normalData = directFloatBuffer(mesh.normals ?: mesh.computeVertexNormals())
        val indexData = directIndexBuffer(mesh)

        if (mesh.vertexCount != cachedVertexCount || mesh.indices.size != cachedIndexCount) {
            clearSurface()
            cachedVertexCount = mesh.vertexCount
            cachedIndexCount = mesh.indices.size

            vertices = VertexBuffer.Builder()
                .vertexCount(mesh.vertexCount)
                .bufferCount(2)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .attribute(VertexBuffer.VertexAttribute.NORMAL, 1, VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .build(engine)
                .also {
                    it.setBufferAt(engine, 0, positionData)
                    it.setBufferAt(engine, 1, normalData)
                }

            indices = IndexBuffer.Builder()
                .indexCount(mesh.indices.size)
                .bufferType(bufferTypeFor(mesh))
                .build(engine)
                .also { it.setBuffer(engine, indexData) }

            val bounds = mesh.bounds()
            val cx = (bounds[0] + bounds[3]) * 0.5f
            val cy = (bounds[1] + bounds[4]) * 0.5f
            val cz = (bounds[2] + bounds[5]) * 0.5f
            val hx = (bounds[3] - bounds[0]) * 0.5f + 1f
            val hy = (bounds[4] - bounds[1]) * 0.5f + 1f
            val hz = (bounds[5] - bounds[2]) * 0.5f + 1f

            surfaceEntity = EntityManager.get().create()
            RenderableManager.Builder(1)
                .boundingBox(Box(cx, cy, cz, hx, hy, hz))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices!!, indices!!)
                .material(0, surfaceMaterial)
                .culling(true)
                .castShadows(true)
                .receiveShadows(true)
                .build(engine, surfaceEntity)
            scene.addEntity(surfaceEntity)
        } else {
            vertices?.setBufferAt(engine, 0, positionData)
            normals?.setBufferAt(engine, 1, normalData)
            indices?.setBuffer(engine, indexData)
        }
    }

    // -------------------------------------------------------------- overlay

    /**
     * Rebuilds the wireframe and selection highlight.
     *
     * Drawing every edge as a line is fine at the densities Edit mode targets; if a mesh
     * pushes past roughly 50k triangles this should switch to drawing only selected and
     * boundary edges.
     */
    fun updateOverlay(mesh: EditMesh, topo: MeshTopology, selection: MeshSelection) {
        clearOverlay()

        val edgeCount = topo.edgeCount
        if (edgeCount > 0 && edgeCount <= MAX_WIREFRAME_EDGES) {
            val points = FloatArray(edgeCount * 6)
            val order = IntArray(edgeCount * 2)
            for (e in 0 until edgeCount) {
                val a = topo.edgeV0(e) * 3
                val b = topo.edgeV1(e) * 3
                val o = e * 6
                points[o] = mesh.positions[a]
                points[o + 1] = mesh.positions[a + 1]
                points[o + 2] = mesh.positions[a + 2]
                points[o + 3] = mesh.positions[b]
                points[o + 4] = mesh.positions[b + 1]
                points[o + 5] = mesh.positions[b + 2]
                order[e * 2] = e * 2
                order[e * 2 + 1] = e * 2 + 1
            }
            wireVertices = directVertexBuffer(edgeCount * 2, points)
            wireIndices = directLineIndexBuffer(order)
            wireEntity = addLineEntity(dimLines, wireVertices!!, wireIndices!!, mesh)
        }

        val selected = selectionOverlaySegments(mesh, topo, selection)
        if (selected.first.isNotEmpty()) {
            selVertices = directVertexBuffer(selected.first.size / 3, selected.first)
            selIndices = directLineIndexBuffer(selected.second)
            selectionEntity = addLineEntity(brightLines, selVertices!!, selIndices!!, mesh)
        }
    }

    /**
     * Line segments covering whatever the current element mode has selected.
     * Selected faces are shown by outlining them, which needs no extra material.
     */
    private fun selectionOverlaySegments(
        mesh: EditMesh,
        topo: MeshTopology,
        selection: MeshSelection
    ): Pair<FloatArray, IntArray> {
        val edges = LinkedHashSet<Int>()
        val vertices = LinkedHashSet<Int>()

        when (selection.mode) {
            ElementMode.FACE -> {
                for (f in selection.faces) {
                    if (f !in 0 until mesh.faceCount) continue
                    val o = f * 3
                    edges += topo.faceEdge[o]
                    edges += topo.faceEdge[o + 1]
                    edges += topo.faceEdge[o + 2]
                }
            }
            ElementMode.EDGE -> edges.addAll(selection.edges)
            ElementMode.VERTEX -> vertices.addAll(selection.vertices)
        }
        for (e in edges) {
            if (e !in 0 until topo.edgeCount) continue
            vertices += topo.edgeV0(e)
            vertices += topo.edgeV1(e)
        }

        val bounds = mesh.bounds()
        val size = maxOf(
            bounds[3] - bounds[0],
            bounds[4] - bounds[1],
            bounds[5] - bounds[2]
        ).coerceAtLeast(1e-4f) * VERTEX_MARKER_SCALE

        val total = edges.size * 2 + vertices.size * 6
        val points = FloatArray(total * 3)
        val order = IntArray(total * 2)
        var p = 0
        var i = 0

        for (e in edges) {
            if (e !in 0 until topo.edgeCount) continue
            writePoint(points, p++, mesh, topo.edgeV0(e))
            writePoint(points, p++, mesh, topo.edgeV1(e))
            order[i++] = p - 2
            order[i++] = p - 1
        }
        for (v in vertices) {
            if (v !in 0 until mesh.vertexCount) continue
            val o = v * 3
            val x = mesh.positions[o]
            val y = mesh.positions[o + 1]
            val z = mesh.positions[o + 2]
            for (axis in 0..2) {
                val s = floatArrayOf(x, y, z)
                val e = floatArrayOf(x, y, z)
                s[axis] -= size
                e[axis] += size
                points[p * 3] = s[0]; points[p * 3 + 1] = s[1]; points[p * 3 + 2] = s[2]
                order[i++] = p++
                points[p * 3] = e[0]; points[p * 3 + 1] = e[1]; points[p * 3 + 2] = e[2]
                order[i++] = p++
            }
        }
        return Pair(points.copyOf(p * 3), order.copyOf(i))
    }

    private fun writePoint(out: FloatArray, slot: Int, mesh: EditMesh, vertex: Int) {
        val o = vertex * 3
        out[slot * 3] = mesh.positions[o]
        out[slot * 3 + 1] = mesh.positions[o + 1]
        out[slot * 3 + 2] = mesh.positions[o + 2]
    }

    // --------------------------------------------------------------- helpers

    private fun addLineEntity(
        material: LineMaterial,
        vb: VertexBuffer,
        ib: IndexBuffer,
        mesh: EditMesh
    ): Int {
        val entity = EntityManager.get().create()
        val bounds = mesh.bounds()
        RenderableManager.Builder(1)
            .boundingBox(
                Box(
                    (bounds[0] + bounds[3]) * 0.5f,
                    (bounds[1] + bounds[4]) * 0.5f,
                    (bounds[2] + bounds[5]) * 0.5f,
                    (bounds[3] - bounds[0]) * 0.5f + 1f,
                    (bounds[4] - bounds[1]) * 0.5f + 1f,
                    (bounds[5] - bounds[2]) * 0.5f + 1f
                )
            )
            .geometry(0, RenderableManager.PrimitiveType.LINES, vb, ib)
            .material(0, material.instance)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
        return entity
    }

    private fun bufferTypeFor(mesh: EditMesh): IndexBuffer.Builder.IndexType =
        if (mesh.vertexCount > 65535) IndexBuffer.Builder.IndexType.UINT
        else IndexBuffer.Builder.IndexType.USHORT

    private fun directFloatBuffer(values: FloatArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(values)
        }

    private fun directIndexBuffer(mesh: EditMesh): ByteBuffer {
        val wide = mesh.vertexCount > 65535
        val buffer = ByteBuffer.allocateDirect(mesh.indices.size * if (wide) 4 else 2)
            .order(ByteOrder.nativeOrder())
        if (wide) {
            for (v in mesh.indices) buffer.putInt(v)
        } else {
            for (v in mesh.indices) buffer.putShort(v.toShort())
        }
        buffer.flip()
        return buffer
    }

    private fun directVertexBuffer(vertexCount: Int, points: FloatArray): VertexBuffer =
        VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
            .also { it.setBufferAt(engine, 0, directFloatBuffer(points)) }

    private fun directLineIndexBuffer(order: IntArray): IndexBuffer {
        val wide = order.size > 0 && order.maxOrNull()!! > 65535
        val buffer = ByteBuffer.allocateDirect(order.size * if (wide) 4 else 2)
            .order(ByteOrder.nativeOrder())
        if (wide) {
            for (v in order) buffer.putInt(v)
        } else {
            for (v in order) buffer.putShort(v.toShort())
        }
        buffer.flip()
        return IndexBuffer.Builder()
            .indexCount(order.size)
            .bufferType(if (wide) IndexBuffer.Builder.IndexType.UINT else IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
            .also { it.setBuffer(engine, buffer) }
    }

    // -------------------------------------------------------------- lifecycle

    fun clearSurface() {
        if (surfaceEntity != 0) {
            scene.removeEntity(surfaceEntity)
            engine.destroyEntity(surfaceEntity)
            EntityManager.get().destroy(surfaceEntity)
            surfaceEntity = 0
        }
        vertices?.let { engine.destroyVertexBuffer(it) }
        indices?.let { engine.destroyIndexBuffer(it) }
        vertices = null
        indices = null
        normals = null
        cachedVertexCount = -1
        cachedIndexCount = -1
    }

    fun clearOverlay() {
        for (entity in intArrayOf(wireEntity, selectionEntity)) {
            if (entity != 0) {
                scene.removeEntity(entity)
                engine.destroyEntity(entity)
                EntityManager.get().destroy(entity)
            }
        }
        wireEntity = 0
        selectionEntity = 0
        wireVertices?.let { engine.destroyVertexBuffer(it) }
        wireIndices?.let { engine.destroyIndexBuffer(it) }
        selVertices?.let { engine.destroyVertexBuffer(it) }
        selIndices?.let { engine.destroyIndexBuffer(it) }
        wireVertices = null; wireIndices = null
        selVertices = null; selIndices = null
    }

    fun clear() {
        clearSurface()
        clearOverlay()
    }

    fun destroy() {
        clear()
        dimLines.destroy()
        brightLines.destroy()
    }

    /** A line material instance carrying a single colour uniform. */
    private class LineMaterial(
        private val engine: Engine,
        buffer: ByteBuffer,
        r: Float,
        g: Float,
        b: Float
    ) {
        private val material = com.google.android.filament.Material.Builder()
            .payload(buffer, buffer.remaining())
            .build(engine)
        val instance: MaterialInstance = material.createInstance().apply {
            setParameter("lineColor", r, g, b, 1f)
        }

        fun destroy() {
            engine.destroyMaterialInstance(instance)
            engine.destroyMaterial(material)
        }
    }

    private companion object {
        /** Above this, the full wireframe is skipped and only selections are drawn. */
        const val MAX_WIREFRAME_EDGES = 120_000

        /** Vertex cross half-length as a fraction of the mesh's largest dimension. */
        const val VERTEX_MARKER_SCALE = 0.015f
    }
}
