package luxe.texture3d.app

import kotlin.math.PI
import kotlin.math.cos

/**
 * The Edit-mode modelling operators: subdivide, extrude, inset, delete and weld.
 *
 * ## Contract
 *
 * Every operator takes an [EditMesh] plus (where topology is needed) a [MeshTopology]
 * built from it, and returns a **new** [EditMesh]. Inputs are never mutated. That is what
 * lets [MeshHistory] implement undo by swapping references, and it means a failed or
 * cancelled operator leaves the scene untouched.
 *
 * Normals are always recomputed. Luxe's imported content arrives through Assimp with
 * inconsistent winding, so carrying stale normals across an edit produces black facets
 * that look like a renderer bug.
 *
 * ## Non-manifold handling
 *
 * Imported meshes are frequently non-manifold. Operators do not throw on them; they
 * degrade. Extrude treats any edge with a mix of selected and unselected faces as a
 * boundary and builds a wall there, which is the best available behaviour. The UI is
 * expected to warn via `MeshTopology.nonManifoldEdges()` rather than the kernel refusing.
 */
object MeshOperators {

    enum class Scheme {
        /** Linear 1-to-4 split. Blender's plain "Subdivide". Geometry unchanged. */
        LINEAR,

        /** Loop subdivision: 1-to-4 split plus the Loop smoothing rules. */
        LOOP
    }

    enum class ExtrudeMode {
        /** Selected faces move as one connected region; walls close the open seam. */
        REGION,

        /** Each selected face extrudes independently, producing separate spikes. */
        INDIVIDUAL
    }

    // =============================================================== subdivide

    /**
     * Subdivides the whole mesh, or only [faces] when supplied.
     *
     * Subdividing a subset leaves T-junctions where selected faces meet unselected ones
     * (the shared edge splits on one side only). Blender has the same artefact. Prefer
     * whole-mesh subdivision for production edits; the subset path exists for quick
     * local density and should be paired with a weld.
     */
    fun subdivide(
        mesh: EditMesh,
        scheme: Scheme = Scheme.LOOP,
        iterations: Int = 1,
        faces: Set<Int>? = null
    ): EditMesh {
        require(iterations >= 1) { "iterations must be at least 1" }
        var current = mesh
        repeat(iterations) {
            current = if (faces == null) subdivideAll(current, scheme) else subdivideSelected(current, faces, scheme)
        }
        return current
    }

    /** Whole-mesh subdivision. Keeps the surface watertight: `F -> 4F`, `V -> V + E`. */
    fun subdivideAll(mesh: EditMesh, scheme: Scheme): EditMesh {
        val topo = MeshTopology.of(mesh)
        val v0 = mesh.vertexCount
        val e0 = topo.edgeCount
        val f0 = mesh.faceCount
        val total = v0 + e0

        val positions = FloatArray(total * 3)
        mesh.positions.copyInto(positions, 0, 0, v0 * 3)

        val srcUv = mesh.uvs
        val uvs = if (srcUv != null) FloatArray(total * 2) else null
        srcUv?.copyInto(uvs!!, 0, 0, v0 * 2)

        // Odd vertices: one new vertex per existing edge.
        for (e in 0 until e0) {
            val a = topo.edgeV0(e)
            val b = topo.edgeV1(e)
            val ao = a * 3
            val bo = b * 3
            val dst = (v0 + e) * 3
            if (scheme == Scheme.LOOP) {
                loopOddVertex(mesh, topo, e, positions, dst)
            } else {
                positions[dst] = (mesh.positions[ao] + mesh.positions[bo]) * 0.5f
                positions[dst + 1] = (mesh.positions[ao + 1] + mesh.positions[bo + 1]) * 0.5f
                positions[dst + 2] = (mesh.positions[ao + 2] + mesh.positions[bo + 2]) * 0.5f
            }
            if (uvs != null && srcUv != null) {
                val d = (v0 + e) * 2
                uvs[d] = (srcUv[a * 2] + srcUv[b * 2]) * 0.5f
                uvs[d + 1] = (srcUv[a * 2 + 1] + srcUv[b * 2 + 1]) * 0.5f
            }
        }

        // Even vertices: Loop repositions the originals, linear leaves them alone.
        if (scheme == Scheme.LOOP) {
            val smoothed = FloatArray(v0 * 3)
            loopEvenVertices(mesh, topo, smoothed)
            smoothed.copyInto(positions, 0, 0, v0 * 3)
        }

        val indices = IntArray(f0 * 4 * 3)
        var w = 0
        for (f in 0 until f0) {
            val o = f * 3
            val a = mesh.indices[o]
            val b = mesh.indices[o + 1]
            val c = mesh.indices[o + 2]
            val mab = v0 + topo.faceEdge[o]       // corner 0 spans a -> b
            val mbc = v0 + topo.faceEdge[o + 1]   // corner 1 spans b -> c
            val mca = v0 + topo.faceEdge[o + 2]   // corner 2 spans c -> a
            indices[w++] = a; indices[w++] = mab; indices[w++] = mca
            indices[w++] = mab; indices[w++] = b; indices[w++] = mbc
            indices[w++] = mca; indices[w++] = mbc; indices[w++] = c
            indices[w++] = mab; indices[w++] = mbc; indices[w++] = mca
        }

        return EditMesh(positions, indices, null, uvs).refreshNormals()
    }

    /** Subset subdivision. See the T-junction caveat on [subdivide]. */
    fun subdivideSelected(mesh: EditMesh, faces: Set<Int>, scheme: Scheme): EditMesh {
        if (faces.isEmpty()) return mesh.clone()
        val topo = MeshTopology.of(mesh)
        val v0 = mesh.vertexCount
        val f0 = mesh.faceCount

        // One new vertex per unique edge touched by a selected face.
        val edgeMap = HashMap<Int, Int>()
        val usedEdges = mutableListOf<Int>()
        for (f in faces) {
            if (f < 0 || f >= f0) continue
            for (k in 0..2) {
                val e = topo.faceEdge[f * 3 + k]
                if (edgeMap.putIfAbsent(e, usedEdges.size) == null) usedEdges += e
            }
        }

        val positions = FloatArray((v0 + usedEdges.size) * 3)
        mesh.positions.copyInto(positions, 0, 0, v0 * 3)
        for ((slot, e) in usedEdges.withIndex()) {
            val dst = (v0 + slot) * 3
            if (scheme == Scheme.LOOP) loopOddVertex(mesh, topo, e, positions, dst)
            else {
                val ao = topo.edgeV0(e) * 3
                val bo = topo.edgeV1(e) * 3
                positions[dst] = (mesh.positions[ao] + mesh.positions[bo]) * 0.5f
                positions[dst + 1] = (mesh.positions[ao + 1] + mesh.positions[bo + 1]) * 0.5f
                positions[dst + 2] = (mesh.positions[ao + 2] + mesh.positions[bo + 2]) * 0.5f
            }
        }
        if (scheme == Scheme.LOOP) {
            val smoothed = FloatArray(v0 * 3)
            loopEvenVertices(mesh, topo, smoothed)
            smoothed.copyInto(positions, 0, 0, v0 * 3)
        }

        val outCount = (f0 - faces.size) + faces.size * 4
        val indices = IntArray(outCount * 3)
        var w = 0
        for (f in 0 until f0) {
            val o = f * 3
            val a = mesh.indices[o]
            val b = mesh.indices[o + 1]
            val c = mesh.indices[o + 2]
            if (f !in faces) {
                indices[w++] = a; indices[w++] = b; indices[w++] = c
            } else {
                val mab = v0 + edgeMap[topo.faceEdge[o]]!!
                val mbc = v0 + edgeMap[topo.faceEdge[o + 1]]!!
                val mca = v0 + edgeMap[topo.faceEdge[o + 2]]!!
                indices[w++] = a; indices[w++] = mab; indices[w++] = mca
                indices[w++] = mab; indices[w++] = b; indices[w++] = mbc
                indices[w++] = mca; indices[w++] = mbc; indices[w++] = c
                indices[w++] = mab; indices[w++] = mbc; indices[w++] = mca
            }
        }
        return EditMesh(positions, indices).refreshNormals()
    }

    // ------------------------------------------------------------- Loop rules

    /**
     * Loop's odd-vertex rule.
     *
     * Interior edge: `3/8 (v0 + v1) + 1/8 (v2 + v3)` where `v2`, `v3` are the two
     * opposite vertices. Boundary edge: plain midpoint — the 1/8 terms do not exist.
     */
    private fun loopOddVertex(mesh: EditMesh, topo: MeshTopology, e: Int, out: FloatArray, dst: Int) {
        val a = topo.edgeV0(e)
        val b = topo.edgeV1(e)
        val ao = a * 3
        val bo = b * 3
        val incident = topo.facesOfEdge(e)
        if (incident.size == 2) {
            val o0 = oppositeVertex(mesh, topo, incident[0], e)
            val o1 = oppositeVertex(mesh, topo, incident[1], e)
            if (o0 >= 0 && o1 >= 0) {
                val p0 = o0 * 3
                val p1 = o1 * 3
                out[dst] = 0.375f * (mesh.positions[ao] + mesh.positions[bo]) +
                    0.125f * (mesh.positions[p0] + mesh.positions[p1])
                out[dst + 1] = 0.375f * (mesh.positions[ao + 1] + mesh.positions[bo + 1]) +
                    0.125f * (mesh.positions[p0 + 1] + mesh.positions[p1 + 1])
                out[dst + 2] = 0.375f * (mesh.positions[ao + 2] + mesh.positions[bo + 2]) +
                    0.125f * (mesh.positions[p0 + 2] + mesh.positions[p1 + 2])
                return
            }
        }
        out[dst] = (mesh.positions[ao] + mesh.positions[bo]) * 0.5f
        out[dst + 1] = (mesh.positions[ao + 1] + mesh.positions[bo + 1]) * 0.5f
        out[dst + 2] = (mesh.positions[ao + 2] + mesh.positions[bo + 2]) * 0.5f
    }

    /** The vertex of face [f] that is not on edge [e]. */
    private fun oppositeVertex(mesh: EditMesh, topo: MeshTopology, f: Int, e: Int): Int {
        val k = topo.cornerOfEdgeInFace(f, e)
        if (k < 0) return -1
        return mesh.indices[f * 3 + (k + 2) % 3]
    }

    /**
     * Loop's even-vertex rule.
     *
     * Interior vertex of valence `n`: `(1 - nB) * v + B * sum(ring)`, with
     * `B = 1/n * (5/8 - (3/8 + 1/4 cos(2*pi/n))^2)` — Warren's weights, which reduce to
     * the familiar `3/16` at valence 3 and `1/16` at valence 6.
     *
     * Boundary vertex: `3/4 v + 1/8 (n0 + n1)` using its two boundary neighbours, which
     * keeps open borders smooth without pulling them off the boundary curve.
     */
    private fun loopEvenVertices(mesh: EditMesh, topo: MeshTopology, out: FloatArray) {
        for (v in 0 until mesh.vertexCount) {
            val vo = v * 3
            if (topo.isBoundaryVertex(v)) {
                val ring = boundaryNeighbors(topo, v)
                if (ring.size >= 2) {
                    val n0 = ring[0] * 3
                    val n1 = ring[1] * 3
                    out[vo] = 0.75f * mesh.positions[vo] + 0.125f * (mesh.positions[n0] + mesh.positions[n1])
                    out[vo + 1] = 0.75f * mesh.positions[vo + 1] + 0.125f * (mesh.positions[n0 + 1] + mesh.positions[n1 + 1])
                    out[vo + 2] = 0.75f * mesh.positions[vo + 2] + 0.125f * (mesh.positions[n0 + 2] + mesh.positions[n1 + 2])
                    continue
                }
            }
            val ring = topo.neighborsOfVertex(v)
            if (ring.isEmpty()) {
                out[vo] = mesh.positions[vo]
                out[vo + 1] = mesh.positions[vo + 1]
                out[vo + 2] = mesh.positions[vo + 2]
                continue
            }
            val beta = loopBeta(ring.size)
            var sx = 0f
            var sy = 0f
            var sz = 0f
            for (nb in ring) {
                val o = nb * 3
                sx += mesh.positions[o]
                sy += mesh.positions[o + 1]
                sz += mesh.positions[o + 2]
            }
            val w = 1f - ring.size * beta
            out[vo] = w * mesh.positions[vo] + beta * sx
            out[vo + 1] = w * mesh.positions[vo + 1] + beta * sy
            out[vo + 2] = w * mesh.positions[vo + 2] + beta * sz
        }
    }

    private fun loopBeta(n: Int): Float {
        if (n <= 0) return 0f
        val c = cos(2.0 * PI / n).toFloat()
        val inner = 0.375f + 0.25f * c
        return (1f / n) * (0.625f - inner * inner)
    }

    private fun boundaryNeighbors(topo: MeshTopology, v: Int): IntArray {
        val out = LinkedHashSet<Int>()
        for (i in topo.vertexEdgeStart[v] until topo.vertexEdgeStart[v + 1]) {
            val e = topo.vertexEdges[i]
            if (topo.isBoundaryEdge(e)) out += topo.otherVertex(e, v)
        }
        return out.toIntArray()
    }

    // ================================================================ extrude

    /**
     * Extrudes the selected faces along their averaged normals.
     *
     * Blender semantics: the selected faces are duplicated and moved, the originals are
     * consumed, and new walls close the seam where the region met the rest of the mesh.
     * Vertices that ended up orphaned by the move are removed by the final [compact].
     *
     * Wall winding is derived from the direction the selected face traverses each
     * boundary edge, so walls face outwards regardless of the source mesh's winding.
     */
    fun extrudeFaces(
        mesh: EditMesh,
        topo: MeshTopology,
        faces: Set<Int>,
        distance: Float,
        mode: ExtrudeMode = ExtrudeMode.REGION
    ): EditMesh {
        if (faces.isEmpty()) return mesh.clone()
        return if (mode == ExtrudeMode.REGION) {
            extrudeRegion(mesh, topo, faces) { v -> accumulatedNormalDisplacement(mesh, topo, faces, v, distance) }
        } else {
            extrudeIndividual(mesh, faces, distance)
        }
    }

    private fun accumulatedNormalDisplacement(
        mesh: EditMesh,
        topo: MeshTopology,
        faces: Set<Int>,
        vertex: Int,
        distance: Float
    ): FloatArray {
        // Sum the area-weighted normals of this vertex's selected faces. Area weighting
        // means a large neighbouring face dominates, which is what artists expect.
        val n = FloatArray(3)
        val acc = FloatArray(3)
        for (i in topo.vertexFaceStart[vertex] until topo.vertexFaceStart[vertex + 1]) {
            val f = topo.vertexFaces[i]
            if (f in faces) {
                mesh.faceNormalWeighted(f, n)
                acc[0] += n[0]; acc[1] += n[1]; acc[2] += n[2]
            }
        }
        if (acc[0] == 0f && acc[1] == 0f && acc[2] == 0f) return acc
        MeshMath.normalize(acc)
        acc[0] *= distance; acc[1] *= distance; acc[2] *= distance
        return acc
    }

    /**
     * Shared region machinery for extrude and inset: duplicate the region's vertices,
     * offset each by [displacementFor], move the selected faces onto the copies, and
     * stitch walls along the region boundary.
     */
    private fun extrudeRegion(
        mesh: EditMesh,
        topo: MeshTopology,
        faces: Set<Int>,
        displacementFor: (vertex: Int) -> FloatArray
    ): EditMesh {
        val v0 = mesh.vertexCount
        val f0 = mesh.faceCount

        val regionVertices = LinkedHashSet<Int>()
        for (f in faces) {
            if (f < 0 || f >= f0) continue
            val o = f * 3
            regionVertices += mesh.indices[o]
            regionVertices += mesh.indices[o + 1]
            regionVertices += mesh.indices[o + 2]
        }
        if (regionVertices.isEmpty()) return mesh.clone()
        val ordered = regionVertices.toIntArray()

        // old vertex -> index of its duplicate
        val duplicateOf = IntArray(v0) { -1 }
        for ((i, v) in ordered.withIndex()) duplicateOf[v] = v0 + i

        val positions = FloatArray((v0 + ordered.size) * 3)
        mesh.positions.copyInto(positions, 0, 0, v0 * 3)
        for ((i, v) in ordered.withIndex()) {
            val src = v * 3
            val dst = (v0 + i) * 3
            val d = displacementFor(v)
            positions[dst] = mesh.positions[src] + d[0]
            positions[dst + 1] = mesh.positions[src + 1] + d[1]
            positions[dst + 2] = mesh.positions[src + 2] + d[2]
        }

        val srcUv = mesh.uvs
        val uvs = if (srcUv != null) FloatArray((v0 + ordered.size) * 2) else null
        if (srcUv != null && uvs != null) {
            srcUv.copyInto(uvs, 0, 0, v0 * 2)
            for ((i, v) in ordered.withIndex()) {
                uvs[(v0 + i) * 2] = srcUv[v * 2]
                uvs[(v0 + i) * 2 + 1] = srcUv[v * 2 + 1]
            }
        }

        // Boundary = an edge whose incident faces are a mix of selected and unselected.
        val boundary = mutableListOf<Int>()
        for (e in 0 until topo.edgeCount) {
            var selected = 0
            for (i in topo.edgeFaceStart[e] until topo.edgeFaceStart[e + 1]) {
                if (topo.edgeFaces[i] in faces) selected++
            }
            val total = topo.edgeFaceStart[e + 1] - topo.edgeFaceStart[e]
            if (selected >= 1 && selected < total) boundary += e
        }

        val indices = IntArray((f0 + boundary.size * 2) * 3)
        var w = 0
        for (f in 0 until f0) {
            val o = f * 3
            if (f in faces) {
                indices[w++] = duplicateOf[mesh.indices[o]]
                indices[w++] = duplicateOf[mesh.indices[o + 1]]
                indices[w++] = duplicateOf[mesh.indices[o + 2]]
            } else {
                indices[w++] = mesh.indices[o]
                indices[w++] = mesh.indices[o + 1]
                indices[w++] = mesh.indices[o + 2]
            }
        }
        for (e in boundary) {
            var selectedFace = -1
            for (i in topo.edgeFaceStart[e] until topo.edgeFaceStart[e + 1]) {
                if (topo.edgeFaces[i] in faces) { selectedFace = topo.edgeFaces[i]; break }
            }
            if (selectedFace < 0) continue
            val k = topo.cornerOfEdgeInFace(selectedFace, e)
            if (k < 0) continue
            val o = selectedFace * 3
            val a = mesh.indices[o + k]                 // edge traversed a -> b by the face
            val b = mesh.indices[o + (k + 1) % 3]
            val a2 = duplicateOf[a]
            val b2 = duplicateOf[b]
            // Quad a -> b -> b' -> a', wound to face outwards.
            indices[w++] = a; indices[w++] = b; indices[w++] = b2
            indices[w++] = a; indices[w++] = b2; indices[w++] = a2
        }

        return compact(EditMesh(positions, indices.copyOf(w), null, uvs).refreshNormals())
    }

    /** Each selected face becomes its own closed spike. */
    private fun extrudeIndividual(mesh: EditMesh, faces: Set<Int>, distance: Float): EditMesh {
        val v0 = mesh.vertexCount
        val f0 = mesh.faceCount
        val valid = faces.filter { it in 0 until f0 }
        if (valid.isEmpty()) return mesh.clone()

        val n = FloatArray(3)
        val positions = FloatArray((v0 + valid.size * 3) * 3)
        mesh.positions.copyInto(positions, 0, 0, v0 * 3)
        val duplicateOf = HashMap<Int, Int>() // (face, vertex) -> duplicate

        for ((fi, f) in valid.withIndex()) {
            mesh.faceNormal(f, n)
            val o = f * 3
            for (k in 0..2) {
                val v = mesh.indices[o + k]
                val dst = (v0 + fi * 3 + k) * 3
                positions[dst] = mesh.positions[v * 3] + n[0] * distance
                positions[dst + 1] = mesh.positions[v * 3 + 1] + n[1] * distance
                positions[dst + 2] = mesh.positions[v * 3 + 2] + n[2] * distance
                duplicateOf[f * 8 + k] = v0 + fi * 3 + k
            }
        }

        val outFaces = (f0 - valid.size) + valid.size * 7 // cap + 3 quads (=6 tris)
        val indices = IntArray(outFaces * 3)
        var w = 0
        for (f in 0 until f0) {
            val o = f * 3
            if (f !in faces) {
                indices[w++] = mesh.indices[o]
                indices[w++] = mesh.indices[o + 1]
                indices[w++] = mesh.indices[o + 2]
            } else {
                val a = mesh.indices[o]
                val b = mesh.indices[o + 1]
                val c = mesh.indices[o + 2]
                val a2 = duplicateOf[f * 8]!!
                val b2 = duplicateOf[f * 8 + 1]!!
                val c2 = duplicateOf[f * 8 + 2]!!
                indices[w++] = a2; indices[w++] = b2; indices[w++] = c2
                addWall(indices, w, a, b, a2, b2); w += 6
                addWall(indices, w, b, c, b2, c2); w += 6
                addWall(indices, w, c, a, c2, a2); w += 6
            }
        }
        return compact(EditMesh(positions, indices.copyOf(w)).refreshNormals())
    }

    private fun addWall(out: IntArray, at: Int, a: Int, b: Int, a2: Int, b2: Int) {
        out[at] = a; out[at + 1] = b; out[at + 2] = b2
        out[at + 3] = a; out[at + 4] = b2; out[at + 5] = a2
    }

    // ================================================================== inset

    /**
     * Shrinks the selected region inside its own surface.
     *
     * [ExtrudeMode.REGION] pulls each vertex toward the average centroid of its selected
     * faces (a Laplacian-style shrink, which behaves sensibly on curved regions).
     * [ExtrudeMode.INDIVIDUAL] shrinks every face toward its own centroid, leaving gaps
     * between them.
     */
    fun insetFaces(
        mesh: EditMesh,
        topo: MeshTopology,
        faces: Set<Int>,
        amount: Float,
        mode: ExtrudeMode = ExtrudeMode.REGION
    ): EditMesh {
        if (faces.isEmpty()) return mesh.clone()
        val clamped = amount.coerceIn(0f, 0.99f)
        val centroid = FloatArray(3)
        return if (mode == ExtrudeMode.REGION) {
            // Precompute the per-vertex target: average centroid of its selected faces.
            val targetX = FloatArray(mesh.vertexCount)
            val targetY = FloatArray(mesh.vertexCount)
            val targetZ = FloatArray(mesh.vertexCount)
            val weight = IntArray(mesh.vertexCount)
            for (f in faces) {
                if (f < 0 || f >= mesh.faceCount) continue
                mesh.faceCentroid(f, centroid)
                val o = f * 3
                for (k in 0..2) {
                    val v = mesh.indices[o + k]
                    targetX[v] += centroid[0]
                    targetY[v] += centroid[1]
                    targetZ[v] += centroid[2]
                    weight[v]++
                }
            }
            val offset = FloatArray(3)
            extrudeRegion(mesh, topo, faces) { v ->
                if (weight[v] == 0) {
                    offset[0] = 0f; offset[1] = 0f; offset[2] = 0f
                } else {
                    val w = weight[v].toFloat()
                    offset[0] = (targetX[v] / w - mesh.positions[v * 3]) * clamped
                    offset[1] = (targetY[v] / w - mesh.positions[v * 3 + 1]) * clamped
                    offset[2] = (targetZ[v] / w - mesh.positions[v * 3 + 2]) * clamped
                }
                offset
            }
        } else {
            insetIndividual(mesh, faces, clamped)
        }
    }

    private fun insetIndividual(mesh: EditMesh, faces: Set<Int>, amount: Float): EditMesh {
        val positions = mesh.positions.copyOf()
        val centroid = FloatArray(3)
        for (f in faces) {
            if (f < 0 || f >= mesh.faceCount) continue
            mesh.faceCentroid(f, centroid)
            val o = f * 3
            for (k in 0..2) {
                val p = mesh.indices[o + k] * 3
                positions[p] += (centroid[0] - positions[p]) * amount
                positions[p + 1] += (centroid[1] - positions[p + 1]) * amount
                positions[p + 2] += (centroid[2] - positions[p + 2]) * amount
            }
        }
        return EditMesh(positions, mesh.indices.copyOf(), null, mesh.uvs?.copyOf()).refreshNormals()
    }

    // ================================================================= delete

    /** Removes the given faces, then drops any vertices left unreferenced. */
    fun deleteFaces(mesh: EditMesh, faces: Set<Int>): EditMesh {
        val keep = IntArray(mesh.faceCount)
        var n = 0
        for (f in 0 until mesh.faceCount) if (f !in faces) keep[n++] = f
        return compact(reindexFaces(mesh, keep, n))
    }

    /** Removes every face touching one of the given vertices. */
    fun deleteVertices(mesh: EditMesh, vertices: Set<Int>): EditMesh {
        val keep = IntArray(mesh.faceCount)
        var n = 0
        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            val hit = mesh.indices[o] in vertices ||
                mesh.indices[o + 1] in vertices ||
                mesh.indices[o + 2] in vertices
            if (!hit) keep[n++] = f
        }
        return compact(reindexFaces(mesh, keep, n))
    }

    /** Removes every face touching one of the given edges. */
    fun deleteEdges(mesh: EditMesh, topo: MeshTopology, edges: Set<Int>): EditMesh {
        val doomed = LinkedHashSet<Int>()
        for (e in edges) {
            if (e < 0 || e >= topo.edgeCount) continue
            for (i in topo.edgeFaceStart[e] until topo.edgeFaceStart[e + 1]) doomed += topo.edgeFaces[i]
        }
        return deleteFaces(mesh, doomed)
    }

    private fun reindexFaces(mesh: EditMesh, keep: IntArray, count: Int): EditMesh {
        val indices = IntArray(count * 3)
        var w = 0
        for (i in 0 until count) {
            val o = keep[i] * 3
            indices[w++] = mesh.indices[o]
            indices[w++] = mesh.indices[o + 1]
            indices[w++] = mesh.indices[o + 2]
        }
        val srcUv = mesh.uvs
        return EditMesh(
            mesh.positions.copyOf(),
            indices,
            null,
            srcUv?.copyOf()
        ).refreshNormals()
    }

    /**
     * Drops unreferenced vertices and renumbers.
     *
     * Extrude and delete both leave orphaned vertices behind; removing them keeps the
     * mesh small and stops later operators from wasting work on geometry nobody sees.
     */
    fun compact(mesh: EditMesh): EditMesh {
        val remap = IntArray(mesh.vertexCount) { -1 }
        var n = 0
        for (i in mesh.indices.indices) {
            val v = mesh.indices[i]
            if (remap[v] < 0) remap[v] = n++
        }
        if (n == mesh.vertexCount) return mesh
        return remapVertices(mesh, remap, n)
    }

    private fun remapVertices(mesh: EditMesh, remap: IntArray, newCount: Int): EditMesh {
        val positions = FloatArray(newCount * 3)
        val srcUv = mesh.uvs
        val uvs = if (srcUv != null) FloatArray(newCount * 2) else null
        for (v in 0 until mesh.vertexCount) {
            val t = remap[v]
            if (t < 0) continue
            positions[t * 3] = mesh.positions[v * 3]
            positions[t * 3 + 1] = mesh.positions[v * 3 + 1]
            positions[t * 3 + 2] = mesh.positions[v * 3 + 2]
            if (uvs != null && srcUv != null) {
                uvs[t * 2] = srcUv[v * 2]
                uvs[t * 2 + 1] = srcUv[v * 2 + 1]
            }
        }
        val indices = IntArray(mesh.indices.size)
        for (i in mesh.indices.indices) indices[i] = remap[mesh.indices[i]]
        return EditMesh(positions, indices, null, uvs).refreshNormals()
    }

    // =================================================================== weld

    /**
     * Merges vertices closer than [tolerance], which is what makes a mesh topologically
     * connected after import or after a subset subdivision.
     *
     * Implemented with a uniform spatial hash: each vertex is quantised to a cell of
     * side [tolerance] and compared against representatives in the 3x3x3 neighbourhood,
     * so pairs straddling a cell boundary are still found. Degenerate triangles produced
     * by a merge are dropped.
     */
    fun weldVertices(mesh: EditMesh, tolerance: Float = 1e-5f): EditMesh {
        if (mesh.vertexCount == 0) return mesh.clone()
        val cell = if (tolerance > 0f) tolerance else 1e-5f
        val grid = HashMap<Long, Int>()
        val remap = IntArray(mesh.vertexCount)

        for (v in 0 until mesh.vertexCount) {
            val x = mesh.positions[v * 3]
            val y = mesh.positions[v * 3 + 1]
            val z = mesh.positions[v * 3 + 2]
            val cx = kotlin.math.floor(x / cell).toInt()
            val cy = kotlin.math.floor(y / cell).toInt()
            val cz = kotlin.math.floor(z / cell).toInt()
            var merged = -1
            for (dx in -1..1) {
                if (merged >= 0) break
                for (dy in -1..1) {
                    if (merged >= 0) break
                    for (dz in -1..1) {
                        val key = packCell(cx + dx, cy + dy, cz + dz)
                        val candidate = grid[key] ?: continue
                        val co = candidate * 3
                        if (MeshMath.distance(x, y, z, mesh.positions[co], mesh.positions[co + 1], mesh.positions[co + 2]) <= cell) {
                            merged = candidate
                            break
                        }
                    }
                }
            }
            if (merged >= 0) {
                remap[v] = merged
            } else {
                grid[packCell(cx, cy, cz)] = v
                remap[v] = v
            }
        }

        // Flatten chains: a merged vertex may itself have been merged.
        for (v in remap.indices) {
            var r = remap[v]
            var guard = 0
            while (remap[r] != r && guard++ < remap.size) r = remap[r]
            remap[v] = r
        }

        val kept = IntArray(mesh.vertexCount) { -1 }
        var n = 0
        for (v in remap.indices) if (remap[v] == v) kept[v] = n++

        val finalRemap = IntArray(mesh.vertexCount)
        for (v in remap.indices) finalRemap[v] = kept[remap[v]]

        val positions = FloatArray(n * 3)
        for (v in remap.indices) {
            if (remap[v] != v) continue
            val dst = kept[v] * 3
            val src = v * 3
            positions[dst] = mesh.positions[src]
            positions[dst + 1] = mesh.positions[src + 1]
            positions[dst + 2] = mesh.positions[src + 2]
        }

        val srcUv = mesh.uvs
        val uvs = if (srcUv != null) FloatArray(n * 2) else null
        if (srcUv != null && uvs != null) {
            for (v in remap.indices) {
                if (remap[v] != v) continue
                val dst = kept[v] * 2
                uvs[dst] = srcUv[v * 2]
                uvs[dst + 1] = srcUv[v * 2 + 1]
            }
        }

        // Drop triangles that collapsed when two of their corners merged.
        val out = IntArray(mesh.indices.size)
        var w = 0
        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            val a = finalRemap[mesh.indices[o]]
            val b = finalRemap[mesh.indices[o + 1]]
            val c = finalRemap[mesh.indices[o + 2]]
            if (a == b || b == c || a == c) continue
            out[w++] = a; out[w++] = b; out[w++] = c
        }
        return EditMesh(positions, out.copyOf(w), null, uvs).refreshNormals()
    }

    private fun packCell(x: Int, y: Int, z: Int): Long {
        var key = 0L
        key = key or ((x.toLong() and 0xFFFFF) shl 42)
        key = key or ((y.toLong() and 0xFFFFF) shl 21)
        key = key or (z.toLong() and 0xFFFFF)
        return key
    }

    // ============================================================= transforms

    /** Selection centroid — Blender's default "median point" pivot. */
    fun selectionCentroid(mesh: EditMesh, vertices: Set<Int>): FloatArray {
        val out = floatArrayOf(0f, 0f, 0f)
        if (vertices.isEmpty()) return out
        var n = 0
        for (v in vertices) {
            if (v !in 0 until mesh.vertexCount) continue
            val o = v * 3
            out[0] += mesh.positions[o]
            out[1] += mesh.positions[o + 1]
            out[2] += mesh.positions[o + 2]
            n++
        }
        if (n > 0) { out[0] /= n; out[1] /= n; out[2] /= n }
        return out
    }

    fun translateVertices(mesh: EditMesh, vertices: Set<Int>, dx: Float, dy: Float, dz: Float): EditMesh {
        val positions = mesh.positions.copyOf()
        for (v in vertices) {
            if (v !in 0 until mesh.vertexCount) continue
            val o = v * 3
            positions[o] += dx; positions[o + 1] += dy; positions[o + 2] += dz
        }
        return EditMesh(positions, mesh.indices.copyOf(), null, mesh.uvs?.copyOf()).refreshNormals()
    }

    /** Per-axis scale about [pivot]. Pass equal factors for a uniform scale. */
    fun scaleVertices(
        mesh: EditMesh, vertices: Set<Int>,
        sx: Float, sy: Float, sz: Float, pivot: FloatArray
    ): EditMesh {
        val positions = mesh.positions.copyOf()
        for (v in vertices) {
            if (v !in 0 until mesh.vertexCount) continue
            val o = v * 3
            positions[o] = pivot[0] + (positions[o] - pivot[0]) * sx
            positions[o + 1] = pivot[1] + (positions[o + 1] - pivot[1]) * sy
            positions[o + 2] = pivot[2] + (positions[o + 2] - pivot[2]) * sz
        }
        return EditMesh(positions, mesh.indices.copyOf(), null, mesh.uvs?.copyOf()).refreshNormals()
    }

    fun scaleVerticesUniform(mesh: EditMesh, vertices: Set<Int>, scale: Float, pivot: FloatArray): EditMesh =
        scaleVertices(mesh, vertices, scale, scale, scale, pivot)

    /** Rodrigues rotation of the selected vertices about [axis] through [pivot]. */
    fun rotateVertices(
        mesh: EditMesh, vertices: Set<Int>,
        axisX: Float, axisY: Float, axisZ: Float, angleRadians: Float, pivot: FloatArray
    ): EditMesh {
        var kx = axisX; var ky = axisY; var kz = axisZ
        val len = MeshMath.length(kx, ky, kz)
        if (len < 1e-20f) return mesh.clone()
        kx /= len; ky /= len; kz /= len
        val c = kotlin.math.cos(angleRadians)
        val s = kotlin.math.sin(angleRadians)
        val positions = mesh.positions.copyOf()
        for (v in vertices) {
            if (v !in 0 until mesh.vertexCount) continue
            val o = v * 3
            val px = positions[o] - pivot[0]
            val py = positions[o + 1] - pivot[1]
            val pz = positions[o + 2] - pivot[2]
            // k x p
            val cx = ky * pz - kz * py
            val cy = kz * px - kx * pz
            val cz = kx * py - ky * px
            val dot = kx * px + ky * py + kz * pz
            positions[o] = pivot[0] + px * c + cx * s + kx * dot * (1f - c)
            positions[o + 1] = pivot[1] + py * c + cy * s + ky * dot * (1f - c)
            positions[o + 2] = pivot[2] + pz * c + cz * s + kz * dot * (1f - c)
        }
        return EditMesh(positions, mesh.indices.copyOf(), null, mesh.uvs?.copyOf()).refreshNormals()
    }

    // ================================================================== knife

    /**
     * Cuts the mesh with an infinite plane, splitting every triangle it crosses.
     *
     * This is the knife: a one-finger drag defines a cut line in the viewport, which
     * becomes a plane through the camera ray. Faces are clipped against it and the new
     * vertices along the cut are welded, so the two halves share the cut edge instead of
     * coming apart.
     *
     * Running it repeatedly with different planes is what "two-finger scroll adds cuts"
     * maps to — each scroll step contributes another plane.
     */
    fun cutWithPlane(mesh: EditMesh, planePoint: FloatArray, planeNormal: FloatArray): EditMesh {
        val n = planeNormal.copyOf()
        if (MeshMath.normalize(n) < 1e-20f) return mesh.clone()
        val px = planePoint[0]; val py = planePoint[1]; val pz = planePoint[2]

        val builder = MeshBuilder(mesh.vertexCount + mesh.faceCount * 3, mesh.faceCount * 2)
        for (v in 0 until mesh.vertexCount) {
            builder.addVertex(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
        }
        for (f in 0 until mesh.faceCount) {
            val clipped = clipTriangleByPlane(mesh, f, px, py, pz, n)
            if (clipped == null) {
                val o = f * 3
                builder.addTriangle(mesh.indices[o], mesh.indices[o + 1], mesh.indices[o + 2])
            } else {
                emitPolygon(builder, clipped.first)
                emitPolygon(builder, clipped.second)
            }
        }
        // Vertices created on the cut line are duplicated per face; welding stitches the
        // two halves back into one shell.
        return weldVertices(builder.build(), 1e-4f)
    }

    /** Splits triangle [f] by a plane. Returns (positive side, negative side) or null. */
    private fun clipTriangleByPlane(
        mesh: EditMesh, f: Int,
        px: Float, py: Float, pz: Float, n: FloatArray,
        eps: Float = 1e-7f
    ): Pair<List<FloatArray>, List<FloatArray>>? {
        val o = f * 3
        val idx = intArrayOf(mesh.indices[o], mesh.indices[o + 1], mesh.indices[o + 2])
        val pts = Array(3) { i ->
            val b = idx[i] * 3
            floatArrayOf(mesh.positions[b], mesh.positions[b + 1], mesh.positions[b + 2])
        }
        val d = FloatArray(3) { i ->
            MeshMath.dot(pts[i][0] - px, pts[i][1] - py, pts[i][2] - pz, n[0], n[1], n[2])
        }
        var above = 0
        var below = 0
        for (i in 0..2) {
            if (d[i] > eps) above++
            else if (d[i] < -eps) below++
        }
        if (above == 0 || below == 0) return null

        fun clip(keepPositive: Boolean): List<FloatArray> {
            val out = mutableListOf<FloatArray>()
            for (i in 0..2) {
                val j = (i + 1) % 3
                val di = if (keepPositive) d[i] else -d[i]
                val dj = if (keepPositive) d[j] else -d[j]
                if (di >= -eps) out += pts[i]
                if ((di > eps && dj < -eps) || (di < -eps && dj > eps)) {
                    val t = di / (di - dj)
                    out += floatArrayOf(
                        pts[i][0] + (pts[j][0] - pts[i][0]) * t,
                        pts[i][1] + (pts[j][1] - pts[i][1]) * t,
                        pts[i][2] + (pts[j][2] - pts[i][2]) * t
                    )
                }
            }
            return out
        }
        return Pair(clip(true), clip(false))
    }

    /** Fan-triangulates a convex polygon of 3D points into [builder]. */
    private fun emitPolygon(builder: MeshBuilder, polygon: List<FloatArray>) {
        if (polygon.size < 3) return
        val first = builder.addVertex(polygon[0])
        var prev = builder.addVertex(polygon[1])
        for (i in 2 until polygon.size) {
            val next = builder.addVertex(polygon[i])
            builder.addTriangle(first, prev, next)
            prev = next
        }
    }

    // ============================================================== loop cut

    /** A loop cut result: the new mesh, plus the edge (as a vertex pair) to cut again. */
    class LoopCutResult(val mesh: EditMesh, val nextStartV0: Int, val nextStartV1: Int)

    /**
     * Inserts an edge loop running lengthwise along the strip of faces that starts at
     * [startEdge] — Blender's Ctrl+R.
     *
     * ## The problem with triangle meshes
     *
     * On a quad mesh the next edge in a ring is simply the opposite edge of the face. A
     * triangle has no opposite edge, so the walk has to advance a corner at a time and
     * decide *which* corner. Advancing blindly makes the loop wander across the triangle
     * fan, which on a triangulated quad grid produces a visible zigzag instead of a loop.
     *
     * The rule used here tracks the strip's two rails. A triangle strip made from quads
     * alternates: one triangle steps onto the shared diagonal, the next onto the opposite
     * side. So the walk alternates which endpoint of the entry edge the cut separates,
     * seeded by taking the longer of the two candidate exit edges at the tapped edge — on
     * a triangulated quad that is always the diagonal. The cut point on every edge the
     * loop crosses is then placed at [t] measured from the same rail, which is what keeps
     * the result a single smooth loop.
     *
     * [count] loops are inserted in one pass, evenly spaced and centred on [t], so the
     * two-finger scroll that adds cuts works on a closed mesh too — re-walking the strip for
     * a second cut would stop short of closing and crack the shell.
     *
     * [nextStartV0]/[nextStartV1] identify the sub-edge of [startEdge] left on the far side
     * of the first cut, for callers that want to trim the strip further.
     */
    fun loopCut(
        mesh: EditMesh, topo: MeshTopology, startEdge: Int, t: Float, count: Int = 1
    ): LoopCutResult {
        val cuts = count.coerceIn(1, 32)
        val step = 1f / (cuts + 1)
        val middle = (cuts - 1) / 2f
        val fractions = FloatArray(cuts) { j ->
            (t + (j - middle) * step).coerceIn(0.01f, 0.99f)
        }
        if (startEdge !in 0 until topo.edgeCount) return LoopCutResult(mesh.clone(), -1, -1)
        val faces = topo.facesOfEdge(startEdge)
        if (faces.isEmpty()) return LoopCutResult(mesh.clone(), -1, -1)

        val a = topo.edgeV0(startEdge)
        val b = topo.edgeV1(startEdge)

        // Walk one way from the tapped edge. If the strip closes into a ring we are done;
        // otherwise walk the other way too and join the two halves into one strip.
        val forward = walkStrip(mesh, topo, faces[0], a, b, startEdge, null)
        val closedRing = forward.isNotEmpty() &&
            edgeBetween(topo, forward.last().exitFrom, forward.last().exitTo) == startEdge
        val forwardFaces = HashSet<Int>()
        for (step in forward) forwardFaces += step.face
        val backward = if (closedRing || faces.size < 2) emptyList()
        else walkStrip(mesh, topo, faces[1], a, b, startEdge, forwardFaces)

        val steps = backward.map { it.reversed() }.reversed() + forward
        if (steps.isEmpty()) return LoopCutResult(mesh.clone(), -1, -1)

        // Safety net. A cut that stops in the middle of an edge would crack the shell,
        // because the face on the far side of that edge is not being cut there. So an open
        // strip is only safe when both of its ends land on the mesh boundary; a strip that
        // neither closes nor reaches the boundary is refused rather than damaged.
        if (!closedRing) {
            val firstRung = edgeBetween(topo, steps.first().p, steps.first().q)
            val lastStep = steps.last()
            val lastRung = edgeBetween(topo, lastStep.exitFrom, lastStep.exitTo)
            val endsAreSafe = firstRung != null && lastRung != null &&
                topo.isBoundaryEdge(firstRung) && topo.isBoundaryEdge(lastRung)
            if (!endsAreSafe) return LoopCutResult(mesh.clone(), -1, -1)
        }

        // Rung 0 is the entry edge of step 0; rung k+1 is the exit edge of step k.
        val n = steps.size
        val builder = MeshBuilder(mesh.vertexCount + n + 1, mesh.faceCount + n * 3)
        for (v in 0 until mesh.vertexCount) {
            builder.addVertex(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
        }
        fun rungVertices(from: Int, to: Int): IntArray {
            val fo = from * 3
            val to3 = to * 3
            return IntArray(cuts) { j ->
                val u = fractions[j]
                builder.addVertex(
                    mesh.positions[fo] + (mesh.positions[to3] - mesh.positions[fo]) * u,
                    mesh.positions[fo + 1] + (mesh.positions[to3 + 1] - mesh.positions[fo + 1]) * u,
                    mesh.positions[fo + 2] + (mesh.positions[to3 + 2] - mesh.positions[fo + 2]) * u
                )
            }
        }
        val cutVertex = arrayOfNulls<IntArray>(n + 1)
        for (k in 0 until n) cutVertex[k] = rungVertices(steps[k].p, steps[k].q)
        // A strip that closes into a ring comes back to the edge it started from, so the last
        // rung is the first one: reusing those vertices is what makes the loop seamless.
        cutVertex[n] = if (closedRing) cutVertex[0]
                       else rungVertices(steps[n - 1].exitFrom, steps[n - 1].exitTo)

        val stepOfFace = HashMap<Int, Int>()
        for (k in steps.indices) stepOfFace[steps[k].face] = k

        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            val stepIndex = stepOfFace[f]
            if (stepIndex == null) {
                builder.addTriangle(mesh.indices[o], mesh.indices[o + 1], mesh.indices[o + 2])
                continue
            }
            val step = steps[stepIndex]
            val p = step.p
            val q = step.q
            val r = step.r
            val e = cutVertex[stepIndex]!!
            val x = cutVertex[stepIndex + 1]!!
            // Every region is written in the face's own cyclic order, then flipped if the
            // walk's (p, q, r) happens to run against the stored winding.
            val flip = !isCyclicOrder(mesh, f, p, q, r)
            if (step.useP) {
                // The cut separates p: a triangle at p, slabs between the loops, then the
                // remainder as a quad.
                addTriangle(builder, flip, p, e[0], x[0])
                for (j in 0 until cuts - 1) {
                    addTriangle(builder, flip, e[j], e[j + 1], x[j + 1])
                    addTriangle(builder, flip, e[j], x[j + 1], x[j])
                }
                addTriangle(builder, flip, e[cuts - 1], q, r)
                addTriangle(builder, flip, e[cuts - 1], r, x[cuts - 1])
            } else {
                // The cut separates q: the remainder as a quad, slabs between the loops,
                // then a triangle at q.
                addTriangle(builder, flip, p, e[0], x[0])
                addTriangle(builder, flip, p, x[0], r)
                for (j in 0 until cuts - 1) {
                    addTriangle(builder, flip, e[j], e[j + 1], x[j + 1])
                    addTriangle(builder, flip, e[j], x[j + 1], x[j])
                }
                addTriangle(builder, flip, e[cuts - 1], q, x[cuts - 1])
            }
        }

        // The next cut continues on the sub-edge of the start rung nearest its first endpoint.
        return LoopCutResult(builder.build().refreshNormals(), steps[0].p, cutVertex[0]!![0])
    }

    /** Inserts [count] loops across the strip at [startEdge], centred on [t]. */
    fun loopCuts(mesh: EditMesh, topo: MeshTopology, startEdge: Int, t: Float, count: Int): EditMesh =
        loopCut(mesh, topo, startEdge, t, count).mesh

    private fun addTriangle(builder: MeshBuilder, flip: Boolean, a: Int, b: Int, c: Int) {
        if (flip) builder.addTriangle(a, c, b) else builder.addTriangle(a, b, c)
    }

    /**
     * One triangle crossed by the loop.
     *
     * [p] -> [q] is the entry edge, ordered so the cut point sits at fraction `t` from [p].
     * [r] is the third corner. [useP] says which end the cut separates, which sets the exit
     * edge: `(p, r)` when true, `(r, q)` when false — always written with [q] last so the
     * next rung inherits the same rail as its "from" endpoint.
     */
    private class Step(val face: Int, val p: Int, val q: Int, val r: Int, val useP: Boolean) {
        val exitFrom: Int get() = if (useP) p else r
        val exitTo: Int get() = if (useP) r else q
        val separated: Int get() = if (useP) p else q

        /** The same triangle traversed from the far side, for joining the two half-strips. */
        fun reversed(): Step = Step(face, exitFrom, exitTo, if (useP) q else p, useP)
    }

    /**
     * Walks the strip from ([startFace], entry edge ordered p->q). Stops at the mesh
     * boundary, at [stopEdge] (a closed ring), or on entering a face in [blockFaces].
     */
    private fun walkStrip(
        mesh: EditMesh, topo: MeshTopology, startFace: Int, p0: Int, q0: Int,
        stopEdge: Int, blockFaces: Set<Int>?
    ): List<Step> {
        val firstThird = thirdVertex(mesh, startFace, p0, q0)
        if (firstThird < 0) return emptyList()
        // On a triangulated quad the diagonal is the longer candidate, and stepping onto it
        // first is what keeps the walk following the quad rows.
        val dp = vertexDistance(mesh, p0, firstThird)
        val dq = vertexDistance(mesh, q0, firstThird)
        var useP = dp >= dq

        val steps = mutableListOf<Step>()
        val seen = HashSet<Int>()
        var face = startFace
        var p = p0
        var q = q0
        while (face >= 0 && face < mesh.faceCount &&
            seen.add(face) && steps.size < MAX_STRIP_FACES &&
            (blockFaces == null || face !in blockFaces)
        ) {
            val r = thirdVertex(mesh, face, p, q)
            if (r < 0) break
            useP = chooseExit(mesh, topo, p, q, r, useP)
            val step = Step(face, p, q, r, useP)
            steps += step
            val exitEdge = edgeBetween(topo, step.exitFrom, step.exitTo) ?: break
            if (exitEdge == stopEdge) break
            val nextFace = otherFaceOfEdge(topo, exitEdge, face)
            if (nextFace < 0) break
            face = nextFace
            p = step.exitFrom
            q = step.exitTo
            useP = !useP
        }
        return steps
    }

    /**
     * Decides which of the two candidate exit edges the loop should leave through.
     *
     * A triangulated quad crossed by a loop is entered through one side, leaves via the
     * shared diagonal, then leaves the far triangle via the opposite side. Where the
     * diagonal is identifiable — the two triangles of a quad are coplanar, while a real
     * side belongs to two different faces — that decides it outright. Where it is not
     * (a perfectly flat surface, where everything is coplanar), fall back to alternating,
     * which is exact on a regular grid.
     *
     * [fallback] is the previous step's choice inverted.
     */
    private fun chooseExit(
        mesh: EditMesh, topo: MeshTopology, p: Int, q: Int, r: Int, fallback: Boolean
    ): Boolean {
        val entryEdge = edgeBetween(topo, p, q) ?: return fallback
        val viaQ = edgeBetween(topo, q, r)
        val viaP = edgeBetween(topo, p, r)
        if (viaQ == null || viaP == null) return fallback
        // A boundary candidate carries no quad information, so the test is meaningless.
        if (!topo.isManifoldEdge(viaQ) || !topo.isManifoldEdge(viaP)) return fallback
        val entryIsDiagonal = isCoplanarEdge(mesh, topo, entryEdge)
        val qIsDiagonal = isCoplanarEdge(mesh, topo, viaQ)
        val pIsDiagonal = isCoplanarEdge(mesh, topo, viaP)
        if (qIsDiagonal == pIsDiagonal) return fallback
        // Entering through a side: leave via the diagonal. Entering via the diagonal: leave
        // through the opposite side.
        return if (entryIsDiagonal) !pIsDiagonal else pIsDiagonal
    }

    private fun vertexDistance(mesh: EditMesh, a: Int, b: Int): Float {
        val ao = a * 3
        val bo = b * 3
        return MeshMath.distance(
            mesh.positions[ao], mesh.positions[ao + 1], mesh.positions[ao + 2],
            mesh.positions[bo], mesh.positions[bo + 1], mesh.positions[bo + 2]
        )
    }

    /** The corner of [face] that is neither [p] nor [q], or -1. */
    private fun thirdVertex(mesh: EditMesh, face: Int, p: Int, q: Int): Int {
        val o = face * 3
        for (k in 0..2) {
            val v = mesh.indices[o + k]
            if (v != p && v != q) return v
        }
        return -1
    }

    private fun edgeBetween(topo: MeshTopology, a: Int, b: Int): Int? {
        for (i in topo.vertexEdgeStart[a] until topo.vertexEdgeStart[a + 1]) {
            val e = topo.vertexEdges[i]
            val v0 = topo.edgeV0(e)
            val v1 = topo.edgeV1(e)
            if ((v0 == a && v1 == b) || (v0 == b && v1 == a)) return e
        }
        return null
    }

    private fun otherFaceOfEdge(topo: MeshTopology, edge: Int, face: Int): Int {
        for (i in topo.edgeFaceStart[edge] until topo.edgeFaceStart[edge + 1]) {
            val f = topo.edgeFaces[i]
            if (f != face) return f
        }
        return -1
    }

    /** True when (p, q, r) follows face [f]'s stored winding. */
    private fun isCyclicOrder(mesh: EditMesh, f: Int, p: Int, q: Int, r: Int): Boolean {
        val o = f * 3
        for (k in 0..2) {
            if (mesh.indices[o + k] == p &&
                mesh.indices[o + (k + 1) % 3] == q &&
                mesh.indices[o + (k + 2) % 3] == r
            ) return true
        }
        return false
    }

    // ================================================================== bevel

    /**
     * Bevels the selected edges — Blender's Ctrl+B.
     *
     * ## How it is built
     *
     * A bevel removes a wedge of material along the edge. What is left of each adjacent face
     * is the part on the far side of the cut, so that face's two corners slide *along* their
     * edges, into the face, by [width]. The vacated wedge is then closed by the chamfer
     * strip and one end cap per end of the edge.
     *
     * The subtle part is that a corner usually belongs to faces that were never beveled. If
     * a slid corner were inserted into only the beveled face, the neighbour across that edge
     * would still run the full length of it and the shell would crack. So every slid corner
     * is registered as a split point on the edge it slides along, and *every* face using
     * that edge picks it up. Faces therefore gain corners they never asked for, which is
     * exactly what a real bevel does, and the result stays watertight.
     *
     * The [segments] strips wrapping the corner follow a quadratic Bezier whose control
     * point is the original corner, so the strip leaves one face tangentially and arrives at
     * the other tangentially. That is what makes a multi-segment bevel read as a rounded
     * fillet rather than a faceted chamfer.
     *
     * [width] is a fraction of the shortest edge available at either end of either face, so
     * it is scale independent and cannot overshoot for any value below 1. Edges whose two
     * faces are coplanar (triangulation diagonals) are skipped: there is no corner to round.
     */
    fun bevelEdges(
        mesh: EditMesh, topo: MeshTopology, edges: Set<Int>, segments: Int = 1, width: Float = 0.25f
    ): EditMesh {
        val seg = segments.coerceIn(1, 8)
        val w = width.coerceIn(0f, 0.9f)
        val targets = edges.filter {
            it in 0 until topo.edgeCount && topo.isManifoldEdge(it) && !isCoplanarEdge(mesh, topo, it)
        }.toSet()
        if (targets.isEmpty()) return mesh.clone()

        class Bevel(
            val u: Int, val v: Int,
            /** The edge as face [f0] traverses it — the order the strip is wound in. */
            val a: Int, val b: Int,
            val f0: Int, val f1: Int, val dist: Float
        )
        class Split(var t: Float, val x: Float, val y: Float, val z: Float)

        // How far each (corner, face) slides, and the points that introduces on each edge.
        val slide = HashMap<Long, FloatArray>()
        val splits = HashMap<Int, MutableList<Split>>()
        val beveled = ArrayList<Bevel>()

        // Pass 1: validate each edge and bound its width by the tightest corner it touches.
        val plan = HashMap<Int, ArrayList<Triple<Int, Int, Int>>>() // edge -> (end, face, far)
        for (e in targets) {
            val incident = topo.facesOfEdge(e)
            if (incident.size != 2) continue
            val f0 = incident[0]
            val f1 = incident[1]
            val u = topo.edgeV0(e)
            val v = topo.edgeV1(e)
            val steps = ArrayList<Triple<Int, Int, Int>>()
            var dist = Float.MAX_VALUE
            var ok = true
            for (f in intArrayOf(f0, f1)) {
                for (end in intArrayOf(u, v)) {
                    val far = farVertexOfOtherEdge(mesh, topo, f, end, e)
                    if (far == null) { ok = false; break }
                    steps += Triple(end, f, far)
                    val len = vertexDistance(mesh, end, far)
                    if (len < dist) dist = len
                }
                if (!ok) break
            }
            if (!ok || dist == Float.MAX_VALUE || dist <= 1e-9f) continue
            // Wind the strip the way f0 traverses the edge, so the strip and its caps come
            // out facing the same way as the faces they replace.
            val corner = topo.cornerOfEdgeInFace(f0, e)
            val o = f0 * 3
            val a = mesh.indices[o + corner]
            val b = mesh.indices[o + (corner + 1) % 3]
            beveled += Bevel(u, v, a, b, f0, f1, dist * w)
            plan[e] = steps
        }
        if (beveled.isEmpty()) return mesh.clone()

        // How many beveled edges meet at each (corner, face). Two of them means the corner
        // is cut from both sides and slides diagonally, landing inside the face rather than
        // on one of its edges — in which case no split point belongs on either edge.
        val cutsAtCorner = HashMap<Long, Int>()
        for (b in beveled) {
            for (f in intArrayOf(b.f0, b.f1)) {
                for (end in intArrayOf(b.u, b.v)) cutsAtCorner[keyOf(end, f, 0)] =
                    (cutsAtCorner[keyOf(end, f, 0)] ?: 0) + 1
            }
        }

        // Pass 2: accumulate the slides and register the splits they introduce.
        for ((e, steps) in plan) {
            val d = beveled.first { (it.u == topo.edgeV0(e) && it.v == topo.edgeV1(e)) }.dist
            for ((end, f, far) in steps) {
                val dir = vertexDirection(mesh, end, far)
                val acc = slide.getOrPut(keyOf(end, f, 0)) { floatArrayOf(0f, 0f, 0f) }
                acc[0] += dir[0] * d
                acc[1] += dir[1] * d
                acc[2] += dir[2] * d
                if ((cutsAtCorner[keyOf(end, f, 0)] ?: 0) != 1) continue
                val edge = edgeBetween(topo, end, far) ?: continue
                val len = vertexDistance(mesh, end, far)
                val eo = end * 3
                splits.getOrPut(edge) { mutableListOf() } += Split(
                    if (topo.edgeV0(edge) == end) d / len else 1f - d / len,
                    mesh.positions[eo] + dir[0] * d,
                    mesh.positions[eo + 1] + dir[1] * d,
                    mesh.positions[eo + 2] + dir[2] * d
                )
            }
        }

        val builder = MeshBuilder(
            mesh.vertexCount + splits.values.sumOf { it.size } + beveled.size * 2 * seg,
            mesh.faceCount * 2 + beveled.size * seg * 4
        )
        for (vi in 0 until mesh.vertexCount) {
            builder.addVertex(mesh.positions[vi * 3], mesh.positions[vi * 3 + 1], mesh.positions[vi * 3 + 2])
        }
        fun vertexAt(x: Float, y: Float, z: Float): Int {
            val probe = FloatArray(3)
            for (i in 0 until builder.vertexCount) {
                builder.positionOf(i, probe)
                if (MeshMath.distance(probe[0], probe[1], probe[2], x, y, z) < 1e-6f) return i
            }
            return builder.addVertex(x, y, z)
        }

        // Resolve every split point to a vertex id, ordered along its edge.
        val splitVertex = HashMap<Int, IntArray>()
        val splitOrder = HashMap<Int, FloatArray>()
        for ((edge, list) in splits) {
            val order = list.sortedBy { it.t }
            val idx = IntArray(order.size)
            val tt = FloatArray(order.size)
            for (i in order.indices) {
                idx[i] = vertexAt(order[i].x, order[i].y, order[i].z)
                tt[i] = order[i].t
            }
            splitVertex[edge] = idx
            splitOrder[edge] = tt
        }

        /** Corner [vertex] of [face] after sliding, as a vertex id. */
        fun corner(vertex: Int, face: Int): Int {
            val off = slide[keyOf(vertex, face, 0)] ?: return vertex
            val o = vertex * 3
            return vertexAt(mesh.positions[o] + off[0], mesh.positions[o + 1] + off[1], mesh.positions[o + 2] + off[2])
        }

        // Rebuild every face: the corners it kept, plus any splits introduced on its edges.
        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            val corners = intArrayOf(
                corner(mesh.indices[o], f),
                corner(mesh.indices[o + 1], f),
                corner(mesh.indices[o + 2], f)
            )
            val polygon = ArrayList<Int>(8)
            for (k in 0..2) {
                val from = mesh.indices[o + k]
                val to = mesh.indices[o + (k + 1) % 3]
                polygon += corners[k]
                val edge = edgeBetween(topo, from, to) ?: continue
                val idx = splitVertex[edge] ?: continue
                val tt = splitOrder[edge]!!
                val forward = topo.edgeV0(edge) == from
                val steps = if (forward) tt.indices else tt.indices.reversed()
                for (i in steps) {
                    val id = idx[i]
                    if (id != corners[k] && id != corners[(k + 1) % 3]) polygon += id
                }
            }
            emitIndexPolygonDeduplicated(builder, polygon)
        }

        // Close the ends and wrap the corner.
        val normalA = FloatArray(3)
        val normalB = FloatArray(3)
        val p0 = FloatArray(3)
        val p1 = FloatArray(3)
        val p2 = FloatArray(3)
        for (b in beveled) {
            val aArc = IntArray(seg + 1)
            val bArc = IntArray(seg + 1)
            aArc[0] = corner(b.a, b.f0)
            aArc[seg] = corner(b.a, b.f1)
            bArc[0] = corner(b.b, b.f0)
            bArc[seg] = corner(b.b, b.f1)
            for (k in 1 until seg) {
                aArc[k] = arcVertex(builder, mesh, b.a, aArc[0], aArc[seg], k, seg)
                bArc[k] = arcVertex(builder, mesh, b.b, bArc[0], bArc[seg], k, seg)
            }
            // The outward direction at the corner is the bisector of the two faces. Winding
            // the strip against it keeps the fillet facing the same way as the material it
            // replaces, whichever order the topology happens to list the edge in.
            mesh.faceNormal(b.f0, normalA)
            mesh.faceNormal(b.f1, normalB)
            val refX = normalA[0] + normalB[0]
            val refY = normalA[1] + normalB[1]
            val refZ = normalA[2] + normalB[2]
            builder.positionOf(aArc[0], p0)
            builder.positionOf(bArc[0], p1)
            builder.positionOf(bArc[1], p2)
            val ux = p1[0] - p0[0]; val uy = p1[1] - p0[1]; val uz = p1[2] - p0[2]
            val vx = p2[0] - p1[0]; val vy = p2[1] - p1[1]; val vz = p2[2] - p1[2]
            val flip = MeshMath.dot(
                uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx,
                refX, refY, refZ
            ) < 0f
            for (k in 0 until seg) {
                if (flip) {
                    builder.addTriangle(aArc[k], bArc[k + 1], bArc[k])
                    builder.addTriangle(aArc[k], aArc[k + 1], bArc[k + 1])
                    addTriangleSkipDegenerate(builder, b.a, aArc[k + 1], aArc[k])
                    addTriangleSkipDegenerate(builder, b.b, bArc[k], bArc[k + 1])
                } else {
                    builder.addTriangle(aArc[k], bArc[k], bArc[k + 1])
                    builder.addTriangle(aArc[k], bArc[k + 1], aArc[k + 1])
                    addTriangleSkipDegenerate(builder, b.a, aArc[k], aArc[k + 1])
                    addTriangleSkipDegenerate(builder, b.b, bArc[k + 1], bArc[k])
                }
            }
        }

        return compact(builder.build().refreshNormals())
    }

    private fun addTriangleSkipDegenerate(builder: MeshBuilder, a: Int, b: Int, c: Int) {
        if (a == b || b == c || a == c) return
        builder.addTriangle(a, b, c)
    }

    /**
     * Fans a polygon, choosing the corner to fan from.
     *
     * A face that picks up a split point along one of its edges gains a vertex that is
     * collinear with that edge's two ends. Fanning from the wrong corner then emits a
     * zero-area sliver. Rotating the fan origin picks the other diagonal of the quad, which
     * covers the same boundary with triangles that all have area.
     */
    private fun emitIndexPolygonDeduplicated(builder: MeshBuilder, polygon: List<Int>) {
        val clean = ArrayList<Int>(polygon.size)
        for (v in polygon) if (clean.isEmpty() || clean.last() != v) clean += v
        if (clean.size > 1 && clean.first() == clean.last()) clean.removeAt(clean.size - 1)
        if (clean.size < 3) return
        var start = 0
        for (candidate in clean.indices) {
            if (fanHasArea(builder, clean, candidate)) { start = candidate; break }
        }
        for (i in 2 until clean.size) {
            val a = clean[start]
            val b = clean[(start + i - 1) % clean.size]
            val c = clean[(start + i) % clean.size]
            addTriangleSkipDegenerate(builder, a, b, c)
        }
    }

    private fun fanHasArea(builder: MeshBuilder, polygon: List<Int>, start: Int): Boolean {
        val p = FloatArray(3)
        val q = FloatArray(3)
        val r = FloatArray(3)
        for (i in 2 until polygon.size) {
            builder.positionOf(polygon[start], p)
            builder.positionOf(polygon[(start + i - 1) % polygon.size], q)
            builder.positionOf(polygon[(start + i) % polygon.size], r)
            val ux = q[0] - p[0]; val uy = q[1] - p[1]; val uz = q[2] - p[2]
            val vx = r[0] - p[0]; val vy = r[1] - p[1]; val vz = r[2] - p[2]
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            if (MeshMath.length(cx, cy, cz) * 0.5f < 1e-7f) return false
        }
        return true
    }

    private fun arcVertex(
        builder: MeshBuilder, mesh: EditMesh, corner: Int, from: Int, to: Int, k: Int, seg: Int
    ): Int {
        val start = FloatArray(3)
        val end = FloatArray(3)
        val mid = FloatArray(3)
        builder.positionOf(from, start)
        builder.positionOf(to, end)
        val o = corner * 3
        mid[0] = mesh.positions[o]
        mid[1] = mesh.positions[o + 1]
        mid[2] = mesh.positions[o + 2]
        val point = bezier3(start, mid, end, k.toFloat() / seg)
        return builder.addVertex(point[0], point[1], point[2])
    }

    /** A point along the arc, using the original corner as the Bezier control point. */
    private fun bezier3(start: FloatArray, mid: FloatArray, end: FloatArray, s: Float): FloatArray {
        val a = (1f - s) * (1f - s)
        val b = 2f * s * (1f - s)
        val c = s * s
        return floatArrayOf(
            a * start[0] + b * mid[0] + c * end[0],
            a * start[1] + b * mid[1] + c * end[1],
            a * start[2] + b * mid[2] + c * end[2]
        )
    }

    /** Unit direction from [from] to [to]. */
    private fun vertexDirection(mesh: EditMesh, from: Int, to: Int): FloatArray {
        val a = from * 3
        val b = to * 3
        val dir = floatArrayOf(
            mesh.positions[b] - mesh.positions[a],
            mesh.positions[b + 1] - mesh.positions[a + 1],
            mesh.positions[b + 2] - mesh.positions[a + 2]
        )
        MeshMath.normalize(dir)
        return dir
    }

    /**
     * In face [f], the vertex at the far end of the edge at [v] that is *not* [bevelEdge] —
     * the edge the corner slides along.
     */
    private fun farVertexOfOtherEdge(
        mesh: EditMesh, topo: MeshTopology, f: Int, v: Int, bevelEdge: Int
    ): Int? {
        val o = f * 3
        for (k in 0..2) {
            if (mesh.indices[o + k] != v) continue
            val next = topo.faceEdge[o + k]
            val prev = topo.faceEdge[o + (k + 2) % 3]
            return when {
                next == bevelEdge -> mesh.indices[o + (k + 2) % 3]
                prev == bevelEdge -> mesh.indices[o + (k + 1) % 3]
                else -> null
            }
        }
        return null
    }

    /** True when an edge's two faces lie in the same plane — a triangulation diagonal. */
    private fun isCoplanarEdge(mesh: EditMesh, topo: MeshTopology, edge: Int): Boolean {
        val faces = topo.facesOfEdge(edge)
        if (faces.size != 2) return false
        val a = FloatArray(3)
        val b = FloatArray(3)
        mesh.faceNormal(faces[0], a)
        mesh.faceNormal(faces[1], b)
        return MeshMath.dot(a[0], a[1], a[2], b[0], b[1], b[2]) > 0.999f
    }

    // ================================================================== relab

    /**
     * Inserts [rings] concentric cuts inside the selected face region.
     *
     * This is "mesh relab": after a region has been beveled, it adds further cuts *inside*
     * that region without beveling or extending it. Each ring is the previous one pulled
     * toward the region's centre, and consecutive rings are bridged, so the result reads as
     * a set of nested outlines rather than a new bevel.
     */
    fun insertRings(mesh: EditMesh, topo: MeshTopology, faces: Set<Int>, rings: Int): EditMesh {
        if (faces.isEmpty() || rings < 1) return mesh.clone()
        val count = rings.coerceIn(1, 16)
        val regionVertices = LinkedHashSet<Int>()
        for (f in faces) {
            if (f !in 0 until mesh.faceCount) continue
            val o = f * 3
            regionVertices += mesh.indices[o]
            regionVertices += mesh.indices[o + 1]
            regionVertices += mesh.indices[o + 2]
        }
        if (regionVertices.isEmpty()) return mesh.clone()

        // Pull each vertex toward the mean centroid of its selected faces.
        val targetX = FloatArray(mesh.vertexCount)
        val targetY = FloatArray(mesh.vertexCount)
        val targetZ = FloatArray(mesh.vertexCount)
        val weight = IntArray(mesh.vertexCount)
        val centroid = FloatArray(3)
        for (f in faces) {
            if (f !in 0 until mesh.faceCount) continue
            mesh.faceCentroid(f, centroid)
            val o = f * 3
            for (k in 0..2) {
                val v = mesh.indices[o + k]
                targetX[v] += centroid[0]
                targetY[v] += centroid[1]
                targetZ[v] += centroid[2]
                weight[v]++
            }
        }

        val ordered = regionVertices.toIntArray()
        val builder = MeshBuilder(mesh.vertexCount * (count + 1), mesh.faceCount + faces.size * count * 2)
        for (v in 0 until mesh.vertexCount) {
            builder.addVertex(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
        }
        val ringStart = IntArray(count)
        for (r in 1..count) {
            val fraction = r.toFloat() / (count + 1)
            ringStart[r - 1] = builder.vertexCount
            for (v in ordered) {
                val o = v * 3
                val w = weight[v].coerceAtLeast(1).toFloat()
                val tx = targetX[v] / w
                val ty = targetY[v] / w
                val tz = targetZ[v] / w
                builder.addVertex(
                    mesh.positions[o] + (tx - mesh.positions[o]) * fraction,
                    mesh.positions[o + 1] + (ty - mesh.positions[o + 1]) * fraction,
                    mesh.positions[o + 2] + (tz - mesh.positions[o + 2]) * fraction
                )
            }
        }

        // The outline of the region. Note this deliberately includes edges that sit on the
        // mesh boundary: a region in the corner of an open surface still needs its rings run
        // round that side, and MeshSelection.regionBoundaryEdges leaves those out because it
        // only returns edges with a face on both sides.
        val boundary = regionOutline(topo, faces)
        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            if (f !in faces) {
                builder.addTriangle(mesh.indices[o], mesh.indices[o + 1], mesh.indices[o + 2])
                continue
            }
            // Innermost ring closes the region.
            val inner = ringStart[count - 1]
            val idx = ordered.indexOf(mesh.indices[o])
            val idy = ordered.indexOf(mesh.indices[o + 1])
            val idz = ordered.indexOf(mesh.indices[o + 2])
            if (idx < 0 || idy < 0 || idz < 0) {
                builder.addTriangle(mesh.indices[o], mesh.indices[o + 1], mesh.indices[o + 2])
            } else {
                builder.addTriangle(inner + idx, inner + idy, inner + idz)
            }
        }
        for (e in boundary) {
            var selectedFace = -1
            for (i in topo.edgeFaceStart[e] until topo.edgeFaceStart[e + 1]) {
                if (topo.edgeFaces[i] in faces) { selectedFace = topo.edgeFaces[i]; break }
            }
            if (selectedFace < 0) continue
            val k = topo.cornerOfEdgeInFace(selectedFace, e)
            if (k < 0) continue
            val so = selectedFace * 3
            val a = mesh.indices[so + k]
            val b = mesh.indices[so + (k + 1) % 3]
            val ia = ordered.indexOf(a)
            val ib = ordered.indexOf(b)
            if (ia < 0 || ib < 0) continue
            // Bridge the original boundary, then each ring to the next.
            for (r in 0..count) {
                val outerA = if (r == 0) a else ringStart[r - 1] + ia
                val outerB = if (r == 0) b else ringStart[r - 1] + ib
                if (r == count) break
                val innerA = ringStart[r] + ia
                val innerB = ringStart[r] + ib
                builder.addTriangle(outerA, outerB, innerB)
                builder.addTriangle(outerA, innerB, innerA)
            }
        }
        return compact(builder.build().refreshNormals())
    }

    /**
     * Unit direction from [end] into the face, perpendicular to the edge (end, other),
     * plus the available distance in element [3].
     */
    private fun inFaceOffsetDirection(mesh: EditMesh, end: Int, other: Int, opposite: Int): FloatArray {
        val eo = end * 3
        val oo = other * 3
        val po = opposite * 3
        var ex = mesh.positions[oo] - mesh.positions[eo]
        var ey = mesh.positions[oo + 1] - mesh.positions[eo + 1]
        var ez = mesh.positions[oo + 2] - mesh.positions[eo + 2]
        val el = MeshMath.length(ex, ey, ez)
        if (el > 1e-20f) { ex /= el; ey /= el; ez /= el } else { ex = 1f; ey = 0f; ez = 0f }

        var vx = mesh.positions[po] - mesh.positions[eo]
        var vy = mesh.positions[po + 1] - mesh.positions[eo + 1]
        var vz = mesh.positions[po + 2] - mesh.positions[eo + 2]
        // Remove the component along the edge: what remains points into the face.
        val along = MeshMath.dot(vx, vy, vz, ex, ey, ez)
        vx -= ex * along
        vy -= ey * along
        vz -= ez * along
        val dir = floatArrayOf(vx, vy, vz)
        val len = MeshMath.normalize(dir)
        return floatArrayOf(dir[0], dir[1], dir[2], len.coerceAtLeast(1e-6f))
    }

    /** Packs three small non-negative ints into one Long for use as a map key. */
    private fun keyOf(a: Int, b: Int, c: Int): Long {
        var key = (a.toLong() and 0xFFFFF) shl 42
        key = key or ((b.toLong() and 0xFFFFF) shl 21)
        key = key or (c.toLong() and 0xFFFFF)
        return key
    }

    private fun emitIndexPolygon(builder: MeshBuilder, polygon: List<Int>) {
        if (polygon.size < 3) return
        for (i in 2 until polygon.size) builder.addTriangle(polygon[0], polygon[i - 1], polygon[i])
    }

    // ================================================================= mirror

    /**
     * Appends a mirrored copy of the mesh across the plane [axis] = 0.
     *
     * The copy's winding is flipped, because mirroring reverses handedness: without that
     * the copy's faces point inwards and light the wrong way. The original is kept, so this
     * is the "duplicate and mirror" that a modelling tool expects rather than a destructive
     * reflection.
     */
    fun mirror(mesh: EditMesh, axis: Axis = Axis.X): EditMesh {
        val flip = when (axis) { Axis.X -> 0; Axis.Y -> 1; Axis.Z -> 2 }
        val builder = MeshBuilder(mesh.vertexCount * 2, mesh.faceCount * 2)
        for (v in 0 until mesh.vertexCount) {
            builder.addVertex(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
        }
        for (v in 0 until mesh.vertexCount) {
            val p = floatArrayOf(
                mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]
            )
            p[flip] = -p[flip]
            builder.addVertex(p[0], p[1], p[2])
        }
        val base = mesh.vertexCount
        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            builder.addTriangle(mesh.indices[o], mesh.indices[o + 1], mesh.indices[o + 2])
            // Reversed order: the mirror turns the surface inside out.
            builder.addTriangle(
                base + mesh.indices[o + 2], base + mesh.indices[o + 1], base + mesh.indices[o]
            )
        }
        return builder.build().refreshNormals()
    }

    enum class Axis { X, Y, Z }

    /** Every edge touched by a face of [faces] that is not shared with another of them. */
    private fun regionOutline(topo: MeshTopology, faces: Set<Int>): IntArray {
        val out = mutableListOf<Int>()
        for (e in 0 until topo.edgeCount) {
            var selected = 0
            for (i in topo.edgeFaceStart[e] until topo.edgeFaceStart[e + 1]) {
                if (topo.edgeFaces[i] in faces) selected++
            }
            if (selected == 0) continue
            val total = topo.edgeFaceStart[e + 1] - topo.edgeFaceStart[e]
            if (selected < total || total < 2) out += e
        }
        return out.toIntArray()
    }

    /** Guards against pathological meshes sending a strip walk into an infinite loop. */
    private const val MAX_STRIP_FACES = 20000

}
