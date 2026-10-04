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
import kotlin.math.cos
import kotlin.math.sin

/**
 * The on-screen transform gizmo: three coloured axis handles, Blender-style.
 *
 * Everything tricky about it lives in [GizmoMath], which is pure Kotlin and covered by the
 * verification harness. This class is only the drawing and the book-keeping, and it is built
 * to do as little work per frame as possible:
 *
 * - Geometry is built **once per mode** at unit size. Moving the camera or the object does
 *   not touch a vertex buffer — it only rewrites a transform matrix.
 * - One renderable per handle, so the grabbed handle can be highlighted by swapping a
 *   material instance rather than rebuilding anything.
 *
 * ## Why it is sized the way it is
 *
 * The gizmo is scaled to hold a constant size on screen (see
 * [GizmoMath.worldSizeForPixels]) but never smaller than the object it belongs to. Both
 * halves matter: a fixed world size would leave the handles buried inside a large model,
 * and a purely object-relative size would make them microscopic on a small one viewed from
 * far away.
 */
class EditorGizmo(
    private val engine: Engine,
    private val scene: Scene,
    materialBuffer: ByteBuffer
) {
    /** Which tool the gizmo is currently representing. */
    var mode: GizmoMath.Mode = GizmoMath.Mode.MOVE
        set(value) {
            if (field == value) return
            field = value
            rebuildGeometry()
        }

    /** Whether the gizmo is drawn at all. */
    var visible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) addToScene() else removeFromScene()
        }

    private val renderableManager = engine.getRenderableManager()

    /** One material per axis colour, plus one for the handle being dragged. */
    private val materials = GizmoMath.Axis.values().associateWith { axis ->
        val c = GizmoMath.axisColor(axis)
        LineMaterial(engine, materialBuffer.duplicate(), c[0], c[1], c[2])
    }
    private val activeMaterial = LineMaterial(engine, materialBuffer.duplicate(), 1f, 0.92f, 0.30f)

    /** Buffers handed back by [build] to the [Handle] being constructed. */
    private val pending = mutableMapOf<Int, Pair<VertexBuffer, IndexBuffer>>()

    /** The handles, in the order X, Y, Z. Rebuilt only when [mode] changes. */
    private val handles = mutableListOf<Handle>()
    private var highlighted: GizmoMath.Axis? = null

    /** Current placement, kept so [hitTest] and drawing never disagree. */
    private val center = FloatArray(3)
    private val axes = arrayOf(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f)
    )
    private var radius = 1f

    init {
        rebuildGeometry()
        addToScene()
    }

    // ------------------------------------------------------------------ placement

    /**
     * Places the gizmo at [cx], oriented along [basis], at [scale] world units.
     *
     * Called every frame while the camera moves; it writes a matrix and nothing else.
     */
    fun place(cx: Float, cy: Float, cz: Float, basis: Array<FloatArray>, scale: Float) {
        center[0] = cx; center[1] = cy; center[2] = cz
        for (i in 0..2) { axes[i][0] = basis[i][0]; axes[i][1] = basis[i][1]; axes[i][2] = basis[i][2] }
        radius = scale

        // The geometry is unit-sized, so the transform is simply the object's basis scaled
        // out and moved into place: columns are the axes, the last column is the position.
        val m = FloatArray(16)
        for (i in 0..2) {
            m[i * 4 + 0] = axes[i][0] * scale
            m[i * 4 + 1] = axes[i][1] * scale
            m[i * 4 + 2] = axes[i][2] * scale
            m[i * 4 + 3] = 0f
        }
        m[12] = cx; m[13] = cy; m[14] = cz; m[15] = 1f
        for (handle in handles) {
            renderableManager.setTransform(renderableManager.getInstance(handle.entity), m)
        }
    }

    /** The world-space size the handles currently reach, for use by [hitTest]. */
    val handleReach: Float get() = radius

    /**
     * Which handle [ray] is over, or null when the touch belongs to the camera.
     *
     * The tolerance scales with the gizmo, so grabbing feels the same close up and far away.
     */
    fun hitTest(ray: GizmoMath.Ray): GizmoMath.Axis? {
        if (!visible) return null
        val tolerance = radius * 0.18f
        return when (mode) {
            GizmoMath.Mode.MOVE, GizmoMath.Mode.SCALE -> GizmoMath.pickAxis(
                ray, center[0], center[1], center[2], axes, radius, tolerance
            )
            GizmoMath.Mode.ROTATE -> GizmoMath.pickRing(
                ray, center[0], center[1], center[2], axes, radius, tolerance
            )
        }
    }

    /** Brings the grabbed handle up in a bright colour, and puts the others back. */
    fun setHighlight(axis: GizmoMath.Axis?) {
        if (highlighted == axis) return
        highlighted = axis
        for (handle in handles) {
            val instance = if (handle.axis == axis) activeMaterial.instance else materials[handle.axis]!!.instance
            renderableManager.setMaterialInstanceAt(
                renderableManager.getInstance(handle.entity), 0, instance
            )
        }
    }

    // ------------------------------------------------------------------- geometry

    private fun rebuildGeometry() {
        for (handle in handles) handle.destroy()
        handles.clear()
        // Shafts are drawn somewhat shorter than the full reach so the tips sit at `radius`.
        for (axis in 0..2) {
            val dir = when (axis) {
                0 -> floatArrayOf(1f, 0f, 0f)
                1 -> floatArrayOf(0f, 1f, 0f)
                else -> floatArrayOf(0f, 0f, 1f)
            }
            val segments = when (mode) {
                GizmoMath.Mode.MOVE -> moveHandle(dir)
                GizmoMath.Mode.SCALE -> scaleHandle(dir)
                GizmoMath.Mode.ROTATE -> ring(axis)
            }
            handles.add(Handle(GizmoMath.Axis.values()[axis], build(segments)))
        }
        setHighlight(highlighted)
    }

    /** A shaft ending in a pyramid tip — the familiar Blender translate handle. */
    private fun moveHandle(dir: FloatArray): FloatArray {
        val base = 0.74f
        val tip = 1.0f
        val width = 0.055f
        // Two directions perpendicular to the axis, so the tip can be drawn around it.
        val u = perpendicular(dir)
        val v = cross(dir, u)
        val out = mutableListOf<Float>()
        fun line(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
            out.add(ax); out.add(ay); out.add(az); out.add(bx); out.add(by); out.add(bz)
        }
        line(0f, 0f, 0f, dir[0] * base, dir[1] * base, dir[2] * base)
        val corners = Array(4) { i ->
            val a = i * Math.PI.toFloat() / 2f
            val c = cos(a.toDouble()).toFloat()
            val s = sin(a.toDouble()).toFloat()
            floatArrayOf(
                dir[0] * base + (u[0] * c + v[0] * s) * width,
                dir[1] * base + (u[1] * c + v[1] * s) * width,
                dir[2] * base + (u[2] * c + v[2] * s) * width
            )
        }
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            line(a[0], a[1], a[2], b[0], b[1], b[2])
            line(a[0], a[1], a[2], dir[0] * tip, dir[1] * tip, dir[2] * tip)
        }
        return out.toFloatArray()
    }

    /** A shaft ending in a small cube — the scale handle. */
    private fun scaleHandle(dir: FloatArray): FloatArray {
        val base = 0.72f
        val size = 0.07f
        val u = perpendicular(dir)
        val v = cross(dir, u)
        val out = mutableListOf<Float>()
        fun line(a: FloatArray, b: FloatArray) {
            out.add(a[0]); out.add(a[1]); out.add(a[2]); out.add(b[0]); out.add(b[1]); out.add(b[2])
        }
        line(floatArrayOf(0f, 0f, 0f), floatArrayOf(dir[0] * base, dir[1] * base, dir[2] * base))
        val cx = dir[0] * (base + size)
        val cy = dir[1] * (base + size)
        val cz = dir[2] * (base + size)
        val corner = Array(8) { i ->
            val su = if (i and 1 == 0) -size else size
            val sv = if (i and 2 == 0) -size else size
            val sd = if (i and 4 == 0) -size else size
            floatArrayOf(cx + u[0] * su + v[0] * sv + dir[0] * sd,
                cy + u[1] * su + v[1] * sv + dir[1] * sd,
                cz + u[2] * su + v[2] * sv + dir[2] * sd)
        }
        val boxEdges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        for (i in boxEdges.indices step 2) line(corner[boxEdges[i]], corner[boxEdges[i + 1]])
        return out.toFloatArray()
    }

    /** A circle in the plane of the other two axes — the rotate handle. */
    private fun ring(axisIndex: Int): FloatArray {
        val u = when (axisIndex) {
            0 -> floatArrayOf(0f, 1f, 0f)
            1 -> floatArrayOf(0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f)
        }
        val v = when (axisIndex) {
            0 -> floatArrayOf(0f, 0f, 1f)
            1 -> floatArrayOf(1f, 0f, 0f)
            else -> floatArrayOf(0f, 1f, 0f)
        }
        val segments = 64
        val out = FloatArray(segments * 6)
        for (s in 0 until segments) {
            val a0 = s.toFloat() / segments * TWO_PI
            val a1 = (s + 1).toFloat() / segments * TWO_PI
            out[s * 6 + 0] = u[0] * cos(a0.toDouble()).toFloat() + v[0] * sin(a0.toDouble()).toFloat()
            out[s * 6 + 1] = u[1] * cos(a0.toDouble()).toFloat() + v[1] * sin(a0.toDouble()).toFloat()
            out[s * 6 + 2] = u[2] * cos(a0.toDouble()).toFloat() + v[2] * sin(a0.toDouble()).toFloat()
            out[s * 6 + 3] = u[0] * cos(a1.toDouble()).toFloat() + v[0] * sin(a1.toDouble()).toFloat()
            out[s * 6 + 4] = u[1] * cos(a1.toDouble()).toFloat() + v[1] * sin(a1.toDouble()).toFloat()
            out[s * 6 + 5] = u[2] * cos(a1.toDouble()).toFloat() + v[2] * sin(a1.toDouble()).toFloat()
        }
        return out
    }

    /** Turns a flat list of line endpoints into a Filament renderable. */
    private fun build(segments: FloatArray): Int {
        val vertexCount = segments.size / 3
        val positionData = ByteBuffer.allocateDirect(segments.size * 4)
            .order(ByteOrder.nativeOrder())
        positionData.asFloatBuffer().put(segments)
        val vertices = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, 0, 12
            )
            .build(engine)
            .also { it.setBufferAt(engine, 0, positionData) }

        val indexData = ByteBuffer.allocateDirect(vertexCount * 2)
            .order(ByteOrder.nativeOrder())
        for (i in 0 until vertexCount) indexData.putShort(i.toShort())
        indexData.flip()
        val indices = IndexBuffer.Builder()
            .indexCount(vertexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
            .also { it.setBuffer(engine, indexData) }

        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 2f, 2f, 2f))
            .geometry(0, RenderableManager.PrimitiveType.LINES, vertices, indices)
            .material(0, materials[GizmoMath.Axis.X]!!.instance)
            // The gizmo is chrome, not geometry: it must not be culled when the camera
            // looks along an axis, and it must never cast a shadow on the model.
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        return entity.also { pending[it] = vertices to indices }
    }

    private fun addToScene() { for (handle in handles) scene.addEntity(handle.entity) }

    private fun removeFromScene() { for (handle in handles) scene.removeEntity(handle.entity) }

    /** Releases every Filament object this gizmo owns. */
    fun destroy() {
        removeFromScene()
        for (handle in handles) handle.destroy()
        handles.clear()
        for (material in materials.values) material.destroy()
        activeMaterial.destroy()
    }

    private fun perpendicular(dir: FloatArray): FloatArray {
        // Pick whichever global axis is least aligned with `dir`, and cross.
        val ref = if (kotlin.math.abs(dir[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        return normalize(cross(dir, ref))
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun normalize(v: FloatArray): FloatArray {
        val l = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l < 1e-8f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    /** One drawn handle: an entity plus the buffers backing it. */
    private inner class Handle(val axis: GizmoMath.Axis, val entity: Int) {
        private val vertices: VertexBuffer? = pending.remove(entity)?.first
        private val indices: IndexBuffer? = pending.remove(entity)?.second

        fun destroy() {
            scene.removeEntity(entity)
            engine.destroyEntity(entity)
            EntityManager.get().destroy(entity)
            vertices?.let { engine.destroyVertexBuffer(it) }
            indices?.let { engine.destroyIndexBuffer(it) }
        }
    }

    /** A line material carrying one colour, matching the one the mesh renderer uses. */
    private class LineMaterial(private val engine: Engine, buffer: ByteBuffer, r: Float, g: Float, b: Float) {
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
        const val TWO_PI = 6.2831855f
    }
}
