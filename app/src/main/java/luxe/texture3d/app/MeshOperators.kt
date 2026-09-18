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
}
