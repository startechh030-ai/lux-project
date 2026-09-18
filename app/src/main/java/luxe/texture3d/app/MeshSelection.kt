package luxe.texture3d.app

/**
 * Which kind of geometry element Edit mode is selecting.
 *
 * Matches the vertex / edge / face triad Luxe's WIP chrome already advertises.
 */
enum class ElementMode { VERTEX, EDGE, FACE }

/**
 * Element selection for a mesh in Edit mode.
 *
 * ## Model
 *
 * Blender's model: at any moment exactly one [ElementMode] is active and its set is
 * authoritative; the other two are derived on demand by [syncDerived]. Switching mode
 * converts the selection rather than discarding it, so selecting faces, switching to
 * vertices, moving them, and switching back keeps the artist's intent intact.
 *
 * Element ids are stable **only while the mesh is unchanged**. Every operator rebuilds
 * vertex, edge and face numbering, so callers must reselect after an edit. The controller
 * handles this by asking each operator for the ids it produced rather than by trying to
 * preserve old ones.
 */
class MeshSelection {
    var mode: ElementMode = ElementMode.FACE
        private set

    val vertices = LinkedHashSet<Int>()
    val edges = LinkedHashSet<Int>()
    val faces = LinkedHashSet<Int>()

    /** The authoritative set for the current [mode]. */
    fun active(): LinkedHashSet<Int> = when (mode) {
        ElementMode.VERTEX -> vertices
        ElementMode.EDGE -> edges
        ElementMode.FACE -> faces
    }

    fun count(): Int = active().size

    fun isEmpty(): Boolean = active().isEmpty()

    // ------------------------------------------------------------- mutation

    fun select(index: Int) { active() += index }

    fun deselect(index: Int) { active() -= index }

    fun toggle(index: Int) {
        val set = active()
        if (!set.add(index)) set.remove(index)
    }

    fun selectOnly(index: Int) {
        active().clear()
        active() += index
    }

    fun selectAll(count: Int) {
        val set = active()
        set.clear()
        for (i in 0 until count) set += i
    }

    fun clear() = active().clear()

    fun clearAll() {
        vertices.clear(); edges.clear(); faces.clear()
    }

    fun invert(count: Int) {
        val set = active()
        val inverted = LinkedHashSet<Int>()
        for (i in 0 until count) if (i !in set) inverted += i
        set.clear()
        set.addAll(inverted)
    }

    // -------------------------------------------------------------- convert

    /**
     * Recomputes the two non-authoritative sets from the active one.
     * Call after any bulk mutation that bypassed [select]/[toggle].
     */
    fun syncDerived(mesh: EditMesh, topo: MeshTopology) {
        when (mode) {
            ElementMode.FACE -> {
                edges.clear(); vertices.clear()
                for (f in faces) {
                    if (f < 0 || f >= mesh.faceCount) continue
                    val o = f * 3
                    edges += topo.faceEdge[o]
                    edges += topo.faceEdge[o + 1]
                    edges += topo.faceEdge[o + 2]
                    vertices += mesh.indices[o]
                    vertices += mesh.indices[o + 1]
                    vertices += mesh.indices[o + 2]
                }
            }
            ElementMode.EDGE -> {
                faces.clear(); vertices.clear()
                for (e in edges) {
                    if (e < 0 || e >= topo.edgeCount) continue
                    vertices += topo.edgeV0(e)
                    vertices += topo.edgeV1(e)
                    for (i in topo.edgeFaceStart[e] until topo.edgeFaceStart[e + 1]) {
                        faces += topo.edgeFaces[i]
                    }
                }
            }
            ElementMode.VERTEX -> {
                edges.clear(); faces.clear()
                for (v in vertices) {
                    if (v < 0 || v >= mesh.vertexCount) continue
                    for (i in topo.vertexEdgeStart[v] until topo.vertexEdgeStart[v + 1]) {
                        val e = topo.vertexEdges[i]
                        // An edge is only selected when both endpoints are.
                        if (topo.otherVertex(e, v) in vertices) edges += e
                    }
                    for (i in topo.vertexFaceStart[v] until topo.vertexFaceStart[v + 1]) {
                        val f = topo.vertexFaces[i]
                        val o = f * 3
                        if (mesh.indices[o] in vertices &&
                            mesh.indices[o + 1] in vertices &&
                            mesh.indices[o + 2] in vertices
                        ) faces += f
                    }
                }
            }
        }
    }

    /**
     * Switches the active mode, converting the current selection into the new domain.
     * This is Blender's behaviour when pressing 1 / 2 / 3 with geometry selected.
     */
    fun switchMode(target: ElementMode, mesh: EditMesh, topo: MeshTopology) {
        if (target == mode) return
        syncDerived(mesh, topo)
        mode = target
        // The target set is now authoritative; drop the others so nothing stale lingers.
        when (target) {
            ElementMode.VERTEX -> { edges.clear(); faces.clear() }
            ElementMode.EDGE -> { vertices.clear(); faces.clear() }
            ElementMode.FACE -> { vertices.clear(); edges.clear() }
        }
    }

    // ----------------------------------------------------------- operators

    /** Expands the selection by one ring of adjacent elements. */
    fun grow(mesh: EditMesh, topo: MeshTopology) {
        when (mode) {
            ElementMode.VERTEX -> {
                val add = LinkedHashSet<Int>()
                for (v in vertices) if (v in 0 until mesh.vertexCount) {
                    add.addAll(topo.neighborsOfVertex(v).asIterable())
                }
                vertices.addAll(add)
            }
            ElementMode.EDGE -> {
                val add = LinkedHashSet<Int>()
                for (e in edges) {
                    if (e !in 0 until topo.edgeCount) continue
                    add.addAll(topo.edgesOfVertex(topo.edgeV0(e)).asIterable())
                    add.addAll(topo.edgesOfVertex(topo.edgeV1(e)).asIterable())
                }
                edges.addAll(add)
            }
            ElementMode.FACE -> {
                val add = LinkedHashSet<Int>()
                for (f in faces) {
                    if (f !in 0 until mesh.faceCount) continue
                    val o = f * 3
                    add.addAll(topo.facesOfVertex(mesh.indices[o]).asIterable())
                    add.addAll(topo.facesOfVertex(mesh.indices[o + 1]).asIterable())
                    add.addAll(topo.facesOfVertex(mesh.indices[o + 2]).asIterable())
                }
                faces.addAll(add)
            }
        }
    }

    /**
     * Edges where the face selection meets unselected geometry — the seam an extrude or
     * inset builds walls along. An edge qualifies when its incident faces are a mix of
     * selected and unselected.
     */
    fun regionBoundaryEdges(topo: MeshTopology): IntArray {
        if (faces.isEmpty()) return IntArray(0)
        val out = mutableListOf<Int>()
        for (e in 0 until topo.edgeCount) {
            var selected = 0
            for (i in topo.edgeFaceStart[e] until topo.edgeFaceStart[e + 1]) {
                if (topo.edgeFaces[i] in faces) selected++
            }
            val total = topo.edgeFaceStart[e + 1] - topo.edgeFaceStart[e]
            if (selected >= 1 && selected < total) out += e
        }
        return out.toIntArray()
    }

    /**
     * Drops everything and starts again in [target]. Used when entering Edit mode or
     * loading a different mesh, where carrying ids across would be meaningless.
     */
    fun reset(target: ElementMode) {
        clearAll()
        mode = target
    }

    // --------------------------------------------------------------- state

    /** Snapshot for undo: selection state is part of what an edit changes. */
    fun snapshot(): MeshSelection {
        val copy = MeshSelection()
        copy.mode = mode
        copy.vertices.addAll(vertices)
        copy.edges.addAll(edges)
        copy.faces.addAll(faces)
        return copy
    }

    fun restore(from: MeshSelection) {
        mode = from.mode
        vertices.clear(); vertices.addAll(from.vertices)
        edges.clear(); edges.addAll(from.edges)
        faces.clear(); faces.addAll(from.faces)
    }

    /** Compact status line for the editor's statistics strip. */
    fun describe(): String {
        val what = when (mode) {
            ElementMode.VERTEX -> "verts"
            ElementMode.EDGE -> "edges"
            ElementMode.FACE -> "faces"
        }
        return "${active().size} $what selected"
    }
}
