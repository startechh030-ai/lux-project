package luxe.texture3d.app

/**
 * Half-edge style adjacency for an [EditMesh], stored as flat arrays.
 *
 * ## Why not objects
 *
 * A classic half-edge mesh allocates one object per half-edge. For a 200k-triangle model
 * that is 1.2M objects — far past what an Android heap tolerates. This is the same
 * information in a handful of `IntArray`s built once per edit, which is also exactly the
 * layout that survives a JNI crossing if operators are ever moved to native code.
 *
 * ## Lifetime
 *
 * A topology instance describes **one specific** [EditMesh]. Any operator that changes
 * vertex or face counts invalidates it; rebuild with [MeshTopology.of]. Topology is built
 * on entering Edit mode and rebuilt after each operator, never per frame.
 *
 * ## Non-manifold meshes
 *
 * Imported content is routinely non-manifold (an edge shared by three or more faces,
 * duplicated vertices at UV seams, internal walls). Rather than rejecting such meshes,
 * adjacency is stored as a CSR list so an edge may carry any number of incident faces:
 * [isBoundaryEdge] and [isManifoldEdge] let operators degrade gracefully, and
 * [nonManifoldEdges] lets the UI warn instead of corrupting geometry.
 */
class MeshTopology private constructor(
    val vertexCount: Int,
    val faceCount: Int,

    /** Number of unique undirected edges. */
    val edgeCount: Int,

    /** Two vertex indices per edge, always stored as `(min, max)`. */
    val edgeVertex: IntArray,

    /** Edge index for each of a face's three corners; corner `i` spans vertex `i -> i+1`. */
    val faceEdge: IntArray,

    /** CSR offsets into [edgeFaces]; an edge `e` owns `edgeFaces[edgeFaceStart[e]..<edgeFaceStart[e+1]]`. */
    val edgeFaceStart: IntArray,
    val edgeFaces: IntArray,

    /** CSR offsets into [vertexEdges]. */
    val vertexEdgeStart: IntArray,
    val vertexEdges: IntArray,

    /** CSR offsets into [vertexFaces]. */
    val vertexFaceStart: IntArray,
    val vertexFaces: IntArray
) {
    companion object {
        /** Builds adjacency for [mesh]. O(V + E + F) with one hash map allocation. */
        fun of(mesh: EditMesh): MeshTopology {
            val faceCount = mesh.faceCount
            val vertexCount = mesh.vertexCount

            // Pass 1 — assign an id to every unique undirected edge.
            val edgeIds = HashMap<Long, Int>(faceCount * 2)
            val faceEdge = IntArray(faceCount * 3)
            val edgeV0 = IntArray(faceCount * 3)
            val edgeV1 = IntArray(faceCount * 3)
            val edgeFaceCount = IntArray(faceCount * 3)
            var edgeCount = 0

            for (f in 0 until faceCount) {
                val o = f * 3
                for (k in 0..2) {
                    val a = mesh.indices[o + k]
                    val b = mesh.indices[o + (k + 1) % 3]
                    val lo: Int; val hi: Int
                    if (a <= b) { lo = a; hi = b } else { lo = b; hi = a }
                    val key = (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
                    val id = edgeIds[key]
                    if (id == null) {
                        val newId = edgeCount++
                        edgeIds[key] = newId
                        edgeV0[newId] = lo
                        edgeV1[newId] = hi
                        faceEdge[o + k] = newId
                        edgeFaceCount[newId] = 1
                    } else {
                        faceEdge[o + k] = id
                        edgeFaceCount[id]++
                    }
                }
            }

            // Pass 2 — count per-vertex incidences so CSR blocks can be sized exactly.
            val vertexEdgeCount = IntArray(vertexCount)
            val vertexFaceCount = IntArray(vertexCount)
            val edgeFaceCountFinal = IntArray(edgeCount)
            for (f in 0 until faceCount) {
                val o = f * 3
                for (k in 0..2) {
                    val e = faceEdge[o + k]
                    edgeFaceCountFinal[e]++
                    vertexFaceCount[mesh.indices[o + k]]++
                }
            }
            for (e in 0 until edgeCount) {
                vertexEdgeCount[edgeV0[e]]++
                if (edgeV1[e] != edgeV0[e]) vertexEdgeCount[edgeV1[e]]++
            }

            // Pass 3 — prefix sums.
            val vertexEdgeStart = IntArray(vertexCount + 1)
            val vertexFaceStart = IntArray(vertexCount + 1)
            val edgeFaceStart = IntArray(edgeCount + 1)
            for (v in 0 until vertexCount) {
                vertexEdgeStart[v + 1] = vertexEdgeStart[v] + vertexEdgeCount[v]
                vertexFaceStart[v + 1] = vertexFaceStart[v] + vertexFaceCount[v]
            }
            for (e in 0 until edgeCount) {
                edgeFaceStart[e + 1] = edgeFaceStart[e] + edgeFaceCountFinal[e]
            }

            // Pass 4 — fill using the counts as write cursors.
            val vertexEdges = IntArray(vertexEdgeStart[vertexCount])
            val vertexFaces = IntArray(vertexFaceStart[vertexCount])
            val edgeFaces = IntArray(edgeFaceStart[edgeCount])
            val veCursor = vertexEdgeStart.copyOf()
            val vfCursor = vertexFaceStart.copyOf()
            val efCursor = edgeFaceStart.copyOf()

            for (e in 0 until edgeCount) {
                val v0 = edgeV0[e]; val v1 = edgeV1[e]
                vertexEdges[veCursor[v0]++] = e
                if (v1 != v0) vertexEdges[veCursor[v1]++] = e
            }
            for (f in 0 until faceCount) {
                val o = f * 3
                for (k in 0..2) {
                    val e = faceEdge[o + k]
                    edgeFaces[efCursor[e]++] = f
                    vertexFaces[vfCursor[mesh.indices[o + k]]++] = f
                }
            }

            val edgeVertex = IntArray(edgeCount * 2)
            for (e in 0 until edgeCount) {
                edgeVertex[e * 2] = edgeV0[e]
                edgeVertex[e * 2 + 1] = edgeV1[e]
            }

            return MeshTopology(
                vertexCount = vertexCount,
                faceCount = faceCount,
                edgeCount = edgeCount,
                edgeVertex = edgeVertex,
                faceEdge = faceEdge,
                edgeFaceStart = edgeFaceStart,
                edgeFaces = edgeFaces,
                vertexEdgeStart = vertexEdgeStart,
                vertexEdges = vertexEdges,
                vertexFaceStart = vertexFaceStart,
                vertexFaces = vertexFaces
            )
        }
    }

    // ------------------------------------------------------------- accessors

    fun edgeV0(e: Int): Int = edgeVertex[e * 2]
    fun edgeV1(e: Int): Int = edgeVertex[e * 2 + 1]

    /** The two vertices of edge [e] written into [out]. */
    fun edge(e: Int, out: IntArray = IntArray(2)): IntArray {
        out[0] = edgeVertex[e * 2]; out[1] = edgeVertex[e * 2 + 1]
        return out
    }

    /** `e` spans [v] and this vertex — the other endpoint. */
    fun otherVertex(e: Int, v: Int): Int =
        if (edgeVertex[e * 2] == v) edgeVertex[e * 2 + 1] else edgeVertex[e * 2]

    fun facesOfEdge(e: Int, out: IntArray = IntArray(edgeFaceStart[e + 1] - edgeFaceStart[e])): IntArray {
        var w = 0
        for (i in edgeFaceStart[e] until edgeFaceStart[e + 1]) out[w++] = edgeFaces[i]
        return out
    }

    fun edgesOfFace(f: Int, out: IntArray = IntArray(3)): IntArray {
        out[0] = faceEdge[f * 3]; out[1] = faceEdge[f * 3 + 1]; out[2] = faceEdge[f * 3 + 2]
        return out
    }

    fun edgesOfVertex(v: Int): IntArray =
        vertexEdges.copyOfRange(vertexEdgeStart[v], vertexEdgeStart[v + 1])

    fun facesOfVertex(v: Int): IntArray =
        vertexFaces.copyOfRange(vertexFaceStart[v], vertexFaceStart[v + 1])

    /** Distinct vertices adjacent to [v] — the 1-ring. */
    fun neighborsOfVertex(v: Int): IntArray {
        val edges = edgesOfVertex(v)
        val seen = LinkedHashSet<Int>(edges.size)
        for (e in edges) seen += otherVertex(e, v)
        return seen.toIntArray()
    }

    fun faceCountOfEdge(e: Int): Int = edgeFaceStart[e + 1] - edgeFaceStart[e]
    fun valence(v: Int): Int = vertexEdgeStart[v + 1] - vertexEdgeStart[v]

    // ------------------------------------------------------------ predicates

    /** An edge used by exactly one face sits on an open boundary of the surface. */
    fun isBoundaryEdge(e: Int): Boolean = faceCountOfEdge(e) == 1

    /** An edge used by exactly two faces — the well-formed interior case. */
    fun isManifoldEdge(e: Int): Boolean = faceCountOfEdge(e) == 2

    fun isBoundaryVertex(v: Int): Boolean {
        for (i in vertexEdgeStart[v] until vertexEdgeStart[v + 1]) {
            if (isBoundaryEdge(vertexEdges[i])) return true
        }
        return false
    }

    /**
     * Edges shared by three or more faces. Operators such as extrude behave unpredictably
     * here, so the UI should surface these to the artist rather than silently proceeding.
     */
    fun nonManifoldEdges(): IntArray {
        val out = mutableListOf<Int>()
        for (e in 0 until edgeCount) if (faceCountOfEdge(e) > 2) out += e
        return out.toIntArray()
    }

    fun boundaryEdges(): IntArray {
        val out = mutableListOf<Int>()
        for (e in 0 until edgeCount) if (isBoundaryEdge(e)) out += e
        return out.toIntArray()
    }

    /** Number of edges that sit on a boundary — 0 for a closed mesh. */
    fun boundaryEdgeCount(): Int {
        var n = 0
        for (e in 0 until edgeCount) if (isBoundaryEdge(e)) n++
        return n
    }

    /**
     * Which corner (0, 1 or 2) of face [f] holds edge [e], or -1.
     * Corner `k` of a face spans vertex `k -> k+1`, which gives the direction the face
     * traverses the edge — what extrude needs to wind its side walls outwards.
     */
    fun cornerOfEdgeInFace(f: Int, e: Int): Int {
        val o = f * 3
        for (k in 0..2) if (faceEdge[o + k] == e) return k
        return -1
    }

    /**
     * Euler characteristic `V - E + F`.
     *
     * A closed genus-0 surface (sphere, cube) gives 2, an open disk gives 1, a torus 0.
     * The test suite uses this as a cheap topological invariant: operators must not
     * change it unless they are supposed to.
     */
    fun eulerCharacteristic(): Int = vertexCount - edgeCount + faceCount

    /**
     * Inflates the adjacency cost, for the editor's statistics strip.
     * Six `Int` arrays plus bookkeeping; roughly 20-30 bytes per edge on ART.
     */
    fun estimatedBytes(): Long =
        (edgeVertex.size + faceEdge.size + edgeFaceStart.size + edgeFaces.size +
            vertexEdgeStart.size + vertexEdges.size + vertexFaceStart.size + vertexFaces.size) * 4L
}
