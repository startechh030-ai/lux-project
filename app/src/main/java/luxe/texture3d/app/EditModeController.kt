package luxe.texture3d.app

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Owns Edit mode for one scene instance: the CPU mesh, its topology, element selection,
 * undo history, and the GPU round trip.
 *
 * ## Where this sits
 *
 * Everything Luxe has today is object mode. [EditorSceneManager] moves whole glTF assets
 * around and `View.pick()` resolves a tap to a renderable. This controller is the layer
 * that makes a *single* instance editable: it loads that instance's geometry onto the CPU,
 * runs operators on it, and pushes the result back to the GPU.
 *
 * Object mode and Edit mode stay separate on purpose. Edit mode is entered for one
 * instance at a time and owns the viewport's touch handling while active; the object-level
 * scene manager remains the source of truth for placement, visibility and locking.
 *
 * ## Element ids after an edit
 *
 * Every operator renumbers vertices, edges and faces, and the final [MeshOperators.compact]
 * renumbers again. Keeping selection across that requires each operator to return an id
 * mapping, so this first iteration clears the selection after a structural change rather
 * than silently selecting the wrong geometry. Blender keeps the new faces selected after
 * an extrude; adding that is a matter of threading an id map out of the operators, and it
 * is the first thing to do once the basics are on screen.
 *
 * ## Memory
 *
 * Only one instance is in Edit mode at a time, so at most one CPU mesh is resident. The
 * undo history is the part that can grow: see [MeshHistory] for its limits and the plan to
 * move to attribute deltas.
 */
class EditModeController(
    private val engine: Engine,
    private val scene: Scene,
    private val lineMaterialBuffer: ByteBuffer,
    private val surfaceMaterialProvider: () -> MaterialInstance,
    private val onSelectionChanged: (String) -> Unit = {},
    private val onGeometryChanged: (EditMesh) -> Unit = {}
) {
    var active = false
        private set

    /** UID of the scene instance currently being edited. */
    var instanceUid: String? = null
        private set

    var displayName: String = ""
        private set

    var mesh: EditMesh = EditMesh.empty()
        private set

    var topology: MeshTopology = MeshTopology.of(mesh)
        private set

    val selection = MeshSelection()
    val history = MeshHistory()

    /** Warnings from the last load: non-manifold edges, unsupported primitives, missing buffers. */
    var loadWarnings: List<String> = emptyList()
        private set

    private var renderer: EditableMeshRenderer? = null

    /**
     * Asset-root space -> world. Mirrors `EditorSceneManager.applyTransform`, which
     * composes TRS over the model's grounded normalisation matrix.
     */
    private var worldMatrix: FloatArray = IDENTITY.copyOf()

    // ------------------------------------------------------------- lifecycle

    /**
     * Enters Edit mode for [record].
     *
     * @param sessionDir project session directory, used to resolve relative sources
     * @return false when the source geometry could not be loaded
     */
    fun enter(record: EditorSceneManager.Record, sessionDir: File? = null): Boolean {
        exit()
        val source = File(record.source).let {
            if (it.isAbsolute || sessionDir == null) it else File(sessionDir, record.source)
        }
        if (!source.exists()) return false

        val loaded = runCatching {
            if (source.isDirectory) {
                GltfMeshLoader.loadFolder(source, record.name)
            } else if (source.extension.equals("glb", ignoreCase = true)) {
                GltfMeshLoader.loadGlb(source.readBytes(), record.name)
            } else {
                return false
            }
        }.getOrNull() ?: return false

        if (!loaded.isUsable) return false

        mesh = loaded.mesh
        loadWarnings = loaded.warnings
        topology = MeshTopology.of(mesh)
        selection.reset(ElementMode.FACE)
        history.clear()
        worldMatrix = worldMatrixOf(record)
        displayName = record.name
        instanceUid = record.uid

        renderer = EditableMeshRenderer(engine, scene, surfaceMaterialProvider(), lineMaterialBuffer)
        refresh()
        active = true
        onSelectionChanged(selection.describe())
        return true
    }

    fun exit() {
        renderer?.clear()
        renderer?.destroy()
        renderer = null
        active = false
        instanceUid = null
        displayName = ""
        loadWarnings = emptyList()
        onSelectionChanged("")
    }

    fun destroy() = exit()

    /** Recomputes the world matrix after the object was transformed in object mode. */
    fun syncTransform(record: EditorSceneManager.Record) {
        if (!active || record.uid != instanceUid) return
        worldMatrix = worldMatrixOf(record)
    }

    private fun worldMatrixOf(record: EditorSceneManager.Record): FloatArray {
        val translation = IDENTITY.copyOf().apply {
            this[12] = record.position[0]
            this[13] = record.position[1]
            this[14] = record.position[2]
        }
        val rotation = quaternionMatrix(record.rotation)
        val scale = IDENTITY.copyOf().apply {
            this[0] = record.scale[0]
            this[5] = record.scale[1]
            this[10] = record.scale[2]
        }
        val rs = FloatArray(16)
        val trs = FloatArray(16)
        val out = FloatArray(16)
        MeshMath.multiply4(rotation, scale, rs)
        MeshMath.multiply4(translation, rs, trs)
        MeshMath.multiply4(trs, record.baseMatrix, out)
        return out
    }

    private fun quaternionMatrix(q: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val n = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(1e-6f)
        val nx = x / n; val ny = y / n; val nz = z / n; val nw = w / n
        return floatArrayOf(
            1 - 2 * ny * ny - 2 * nz * nz, 2 * nx * ny + 2 * nz * nw, 2 * nx * nz - 2 * ny * nw, 0f,
            2 * nx * ny - 2 * nz * nw, 1 - 2 * nx * nx - 2 * nz * nz, 2 * ny * nz + 2 * nx * nw, 0f,
            2 * nx * nz + 2 * ny * nw, 2 * ny * nz - 2 * nx * nw, 1 - 2 * nx * nx - 2 * ny * ny, 0f,
            0f, 0f, 0f, 1f
        )
    }

    // ---------------------------------------------------------------- picking

    /**
     * Hit tests a world-space ray against the edited mesh and updates the selection.
     *
     * @param additive when true the hit is added to the selection instead of replacing it
     * @return true when something was hit
     */
    fun pick(originWorld: FloatArray, directionWorld: FloatArray, additive: Boolean = false): Boolean {
        if (!active) return false
        val ray = MeshRaycast.toLocalRay(worldMatrix, originWorld, directionWorld) ?: return false
        val origin = floatArrayOf(ray[0], ray[1], ray[2])
        val direction = floatArrayOf(ray[3], ray[4], ray[5])

        val hitIndex = when (selection.mode) {
            ElementMode.VERTEX -> MeshRaycast.pickVertex(mesh, origin, direction, PICK_TOLERANCE)
            ElementMode.EDGE -> MeshRaycast.pickEdge(mesh, topology, origin, direction, PICK_TOLERANCE)
            ElementMode.FACE -> MeshRaycast.pickFace(mesh, origin, direction)
        }

        if (hitIndex < 0) {
            if (!additive) selection.clear()
            refresh()
            onSelectionChanged(selection.describe())
            return false
        }
        if (additive) selection.toggle(hitIndex) else selection.selectOnly(hitIndex)
        refresh()
        onSelectionChanged(selection.describe())
        return true
    }

    // -------------------------------------------------------------- operators

    fun setElementMode(mode: ElementMode) {
        selection.switchMode(mode, mesh, topology)
        refresh()
        onSelectionChanged(selection.describe())
    }

    fun selectAll() {
        val count = when (selection.mode) {
            ElementMode.VERTEX -> mesh.vertexCount
            ElementMode.EDGE -> topology.edgeCount
            ElementMode.FACE -> mesh.faceCount
        }
        selection.selectAll(count)
        refresh()
        onSelectionChanged(selection.describe())
    }

    fun invertSelection() {
        val count = when (selection.mode) {
            ElementMode.VERTEX -> mesh.vertexCount
            ElementMode.EDGE -> topology.edgeCount
            ElementMode.FACE -> mesh.faceCount
        }
        selection.invert(count)
        refresh()
        onSelectionChanged(selection.describe())
    }

    fun clearSelection() {
        selection.clear()
        refresh()
        onSelectionChanged(selection.describe())
    }

    fun growSelection() {
        selection.grow(mesh, topology)
        refresh()
        onSelectionChanged(selection.describe())
    }

    fun subdivide(scheme: MeshOperators.Scheme = MeshOperators.Scheme.LOOP, iterations: Int = 1) {
        apply("Subdivide", MeshOperators.subdivide(mesh, scheme, iterations))
    }

    fun extrude(distance: Float, individual: Boolean = false) {
        if (selection.faces.isEmpty()) return
        val mode = if (individual) MeshOperators.ExtrudeMode.INDIVIDUAL else MeshOperators.ExtrudeMode.REGION
        apply("Extrude", MeshOperators.extrudeFaces(mesh, topology, selection.faces, distance, mode))
    }

    fun inset(amount: Float, individual: Boolean = false) {
        if (selection.faces.isEmpty()) return
        val mode = if (individual) MeshOperators.ExtrudeMode.INDIVIDUAL else MeshOperators.ExtrudeMode.REGION
        apply("Inset", MeshOperators.insetFaces(mesh, topology, selection.faces, amount, mode))
    }

    fun deleteSelected() {
        when (selection.mode) {
            ElementMode.VERTEX -> apply("Delete", MeshOperators.deleteVertices(mesh, selection.vertices))
            ElementMode.EDGE -> apply("Delete", MeshOperators.deleteEdges(mesh, topology, selection.edges))
            ElementMode.FACE -> apply("Delete", MeshOperators.deleteFaces(mesh, selection.faces))
        }
    }

    fun weld(tolerance: Float = 1e-5f) {
        apply("Weld", MeshOperators.weldVertices(mesh, tolerance))
    }

    fun undo() {
        val entry = history.undo(mesh, selection) ?: return
        mesh = entry.mesh
        entry.selection?.let { selection.restore(it) }
        topology = MeshTopology.of(mesh)
        refresh()
        onSelectionChanged(selection.describe())
    }

    fun redo() {
        val entry = history.redo(mesh, selection) ?: return
        mesh = entry.mesh
        entry.selection?.let { selection.restore(it) }
        topology = MeshTopology.of(mesh)
        refresh()
        onSelectionChanged(selection.describe())
    }

    private fun apply(label: String, result: EditMesh) {
        history.record(label, mesh, selection)
        mesh = result
        topology = MeshTopology.of(mesh)
        // Operators renumber elements, so there is nothing meaningful left to select
        // until they report an id mapping (see the class documentation).
        selection.clearAll()
        refresh()
        onSelectionChanged(selection.describe())
        onGeometryChanged(mesh)
    }

    // ----------------------------------------------------------------- output

    private fun refresh() {
        renderer?.update(mesh)
        renderer?.updateOverlay(mesh, topology, selection)
    }

    /** One-line summary for the editor's statistics strip. */
    fun stats(): String {
        val v = formatCount(mesh.vertexCount)
        val e = formatCount(topology.edgeCount)
        val f = formatCount(mesh.faceCount)
        val selected = selection.describe()
        val nonManifold = topology.nonManifoldEdges().size
        val warning = if (nonManifold > 0) "  •  $nonManifold non-manifold" else ""
        return "$v verts  •  $e edges  •  $f faces  •  $selected$warning"
    }

    private fun formatCount(value: Int): String = when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000f)
        value >= 1_000 -> String.format("%.1fk", value / 1_000f)
        else -> value.toString()
    }

    private companion object {
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f
        )

        /**
         * Angular pick tolerance. Roughly `pixels / viewportHeight * 2 * tan(fov / 2)`;
         * 0.022 is a comfortable fingertip at ModelViewer's default field of view.
         */
        const val PICK_TOLERANCE = 0.022f
    }
}
