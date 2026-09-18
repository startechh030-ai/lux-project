package luxe.texture3d.app

import kotlin.math.max
import kotlin.math.sqrt

/**
 * CPU ray casting against an [EditMesh], used for element picking in Edit mode.
 *
 * ## Why not Filament picking
 *
 * `View.pick()` resolves a screen point to a **renderable entity** — one glTF primitive.
 * That is exactly right for selecting scene objects (what Phase 4D already does) and
 * useless for Edit mode, which needs the specific face, edge or vertex under the finger.
 * It also runs asynchronously on the render thread, so it cannot drive a hover highlight.
 *
 * So Edit mode does its own hit testing on the CPU. That is the conventional approach in
 * DCC tools, and it has a useful side effect: because the CPU mesh and the GPU buffers
 * are the same data, picking stays correct even while an operator is mid-flight.
 *
 * ## Cost
 *
 * This is a brute-force scan. At the ~200k triangle budget targeted for phones it is a
 * few milliseconds — acceptable for a tap, not for per-frame hover. When hover highlighting
 * lands, add a BVH over the faces (median split on the longest axis) and keep this code as
 * the leaf-level intersector and the correctness reference.
 */
object MeshRaycast {

    /** A ray/mesh intersection. [u] and [v] are barycentric weights of the hit face. */
    class Hit(
        val face: Int,
        val t: Float,
        val u: Float,
        val v: Float,
        val point: FloatArray
    ) {
        val distance: Float get() = t
        override fun toString(): String = "Hit(face=$face, t=$t, u=$u, v=$v)"
    }

    /**
     * Möller-Trumbore intersection against every triangle.
     *
     * @param origin ray origin in the mesh's local space
     * @param direction ray direction, unit length
     * @param maxT reject hits beyond this distance
     * @param cullBackFaces when true, ignore triangles facing away from the ray. Off by
     *   default: in Edit mode an artist frequently wants to click through to a back face
     *   in wireframe or x-ray shading.
     * @return the nearest hit, or null.
     */
    fun raycast(
        mesh: EditMesh,
        origin: FloatArray,
        direction: FloatArray,
        maxT: Float = Float.MAX_VALUE,
        cullBackFaces: Boolean = false
    ): Hit? {
        val ox = origin[0]; val oy = origin[1]; val oz = origin[2]
        val dx = direction[0]; val dy = direction[1]; val dz = direction[2]
        var bestT = maxT
        var bestFace = -1
        var bestU = 0f
        var bestV = 0f

        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            val ia = mesh.indices[o] * 3
            val ib = mesh.indices[o + 1] * 3
            val ic = mesh.indices[o + 2] * 3

            val ax = mesh.positions[ia]; val ay = mesh.positions[ia + 1]; val az = mesh.positions[ia + 2]
            val e1x = mesh.positions[ib] - ax
            val e1y = mesh.positions[ib + 1] - ay
            val e1z = mesh.positions[ib + 2] - az
            val e2x = mesh.positions[ic] - ax
            val e2y = mesh.positions[ic + 1] - ay
            val e2z = mesh.positions[ic + 2] - az

            // p = d x e2
            val px = dy * e2z - dz * e2y
            val py = dz * e2x - dx * e2z
            val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz

            if (cullBackFaces) {
                if (det < 1e-12f) continue
            } else {
                if (det > -1e-12f && det < 1e-12f) continue // parallel
            }

            val invDet = 1f / det
            val tx = ox - ax
            val ty = oy - ay
            val tz = oz - az

            val u = (tx * px + ty * py + tz * pz) * invDet
            if (u < -1e-6f || u > 1f + 1e-6f) continue

            // q = t x e1
            val qx = ty * e1z - tz * e1y
            val qy = tz * e1x - tx * e1z
            val qz = tx * e1y - ty * e1x
            val v = (dx * qx + dy * qy + dz * qz) * invDet
            if (v < -1e-6f || u + v > 1f + 1e-6f) continue

            val t = (e2x * qx + e2y * qy + e2z * qz) * invDet
            if (t <= 1e-6f || t >= bestT) continue

            bestT = t
            bestFace = f
            bestU = u
            bestV = v
        }

        if (bestFace < 0) return null
        val w = 1f - bestU - bestV
        val o = bestFace * 3
        val ia = mesh.indices[o] * 3
        val ib = mesh.indices[o + 1] * 3
        val ic = mesh.indices[o + 2] * 3
        val point = floatArrayOf(
            w * mesh.positions[ia] + bestU * mesh.positions[ib] + bestV * mesh.positions[ic],
            w * mesh.positions[ia + 1] + bestU * mesh.positions[ib + 1] + bestV * mesh.positions[ic + 1],
            w * mesh.positions[ia + 2] + bestU * mesh.positions[ib + 2] + bestV * mesh.positions[ic + 2]
        )
        return Hit(bestFace, bestT, bestU, bestV, point)
    }

    fun pickFace(
        mesh: EditMesh,
        origin: FloatArray,
        direction: FloatArray,
        cullBackFaces: Boolean = false
    ): Int = raycast(mesh, origin, direction, cullBackFaces = cullBackFaces)?.face ?: -1

    /**
     * Nearest vertex to the ray, in **angular** terms: the perpendicular distance divided
     * by distance along the ray.
     *
     * An angular measure is what makes finger picking feel right — a vertex twice as far
     * away needs to be within twice the world-space distance to feel equally "close",
     * which is exactly how it appears on screen under perspective.
     *
     * @param tolerance roughly `pixels / viewportHeight * 2 * tan(fov/2)`; ~0.02 is a
     *   comfortable fingertip at typical phone FOVs.
     */
    fun pickVertex(
        mesh: EditMesh,
        origin: FloatArray,
        direction: FloatArray,
        tolerance: Float = 0.02f
    ): Int {
        val ox = origin[0]; val oy = origin[1]; val oz = origin[2]
        val dx = direction[0]; val dy = direction[1]; val dz = direction[2]
        var best = -1
        var bestScore = Float.MAX_VALUE

        for (v in 0 until mesh.vertexCount) {
            val o = v * 3
            val wx = mesh.positions[o] - ox
            val wy = mesh.positions[o + 1] - oy
            val wz = mesh.positions[o + 2] - oz
            val t = wx * dx + wy * dy + wz * dz
            if (t <= 1e-6f) continue
            val perpSq = (wx * wx + wy * wy + wz * wz) - t * t
            val perp = sqrt(max(0f, perpSq))
            val score = perp / t
            if (score < bestScore) {
                bestScore = score
                best = v
            }
        }
        return if (bestScore <= tolerance) best else -1
    }

    /**
     * Nearest edge to the ray, using the same angular measure as [pickVertex].
     * Uses a proper segment/ray closest-point solve so long edges are not reduced to
     * their midpoint.
     */
    fun pickEdge(
        mesh: EditMesh,
        topo: MeshTopology,
        origin: FloatArray,
        direction: FloatArray,
        tolerance: Float = 0.02f
    ): Int {
        val ox = origin[0]; val oy = origin[1]; val oz = origin[2]
        val dx = direction[0]; val dy = direction[1]; val dz = direction[2]
        var best = -1
        var bestScore = Float.MAX_VALUE

        for (e in 0 until topo.edgeCount) {
            val a = topo.edgeV0(e) * 3
            val b = topo.edgeV1(e) * 3
            val result = segmentRayDistance(
                mesh.positions[a], mesh.positions[a + 1], mesh.positions[a + 2],
                mesh.positions[b], mesh.positions[b + 1], mesh.positions[b + 2],
                ox, oy, oz, dx, dy, dz
            )
            val dist = result[0]
            val t = result[1]
            if (t <= 1e-6f) continue
            val score = dist / t
            if (score < bestScore) {
                bestScore = score
                best = e
            }
        }
        return if (bestScore <= tolerance) best else -1
    }

    /**
     * Builds a world-space picking ray from a screen point.
     *
     * Derived analytically from the camera basis rather than by inverting Filament's
     * projection matrix. That is partly because the Java `Camera` binding does not expose
     * a convenient unproject, but mainly because it keeps this function pure and
     * testable, and Luxe already tracks the camera basis: `Manipulator.getLookAt(eye,
     * target, up)` is called in `EditorActivity.captureSceneState()` for session saves.
     *
     * @param x screen x in pixels, origin top-left
     * @param y screen y in pixels, origin top-left
     * @param tanHalfFovY `tan(verticalFov / 2)`; ModelViewer's default is a 45 degree
     *   vertical FOV, giving roughly 0.4142
     * @return `floatArrayOf(ox, oy, oz, dx, dy, dz)` with the direction normalised
     */
    fun rayFromScreen(
        eye: FloatArray,
        target: FloatArray,
        up: FloatArray,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        tanHalfFovY: Float
    ): FloatArray {
        val fx = target[0] - eye[0]
        val fy = target[1] - eye[1]
        val fz = target[2] - eye[2]
        var fl = MeshMath.length(fx, fy, fz)
        if (fl < 1e-20f) fl = 1f
        val fdx = fx / fl; val fdy = fy / fl; val fdz = fz / fl

        // right = forward x up
        var rx = fdy * up[2] - fdz * up[1]
        var ry = fdz * up[0] - fdx * up[2]
        var rz = fdx * up[1] - fdy * up[0]
        var rl = MeshMath.length(rx, ry, rz)
        if (rl < 1e-20f) {
            // Camera looking straight along up; pick any perpendicular.
            rx = 1f; ry = 0f; rz = 0f; rl = 1f
        }
        rx /= rl; ry /= rl; rz /= rl

        // trueUp = right x forward
        val ux = ry * fdz - rz * fdy
        val uy = rz * fdx - rx * fdz
        val uz = rx * fdy - ry * fdx

        val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
        val ndcX = (2f * x / width.coerceAtLeast(1)) - 1f
        val ndcY = 1f - (2f * y / height.coerceAtLeast(1))

        val sx = ndcX * aspect * tanHalfFovY
        val sy = ndcY * tanHalfFovY

        var dx = fdx + rx * sx + ux * sy
        var dy = fdy + ry * sx + uy * sy
        var dz = fdz + rz * sx + uz * sy
        val dl = MeshMath.length(dx, dy, dz)
        if (dl > 1e-20f) { dx /= dl; dy /= dl; dz /= dl }

        return floatArrayOf(eye[0], eye[1], eye[2], dx, dy, dz)
    }

    /**
     * Converts a world-space ray into the mesh's local space so it can be tested against
     * [EditMesh] positions, which are stored un-transformed.
     *
     * @param worldMatrix the mesh's full world transform (normalisation x TRS)
     * @return `floatArrayOf(ox, oy, oz, dx, dy, dz)` in local space, or null when the
     *   matrix cannot be inverted.
     */
    fun toLocalRay(worldMatrix: FloatArray, origin: FloatArray, direction: FloatArray): FloatArray? {
        val inverse = FloatArray(16)
        if (!MeshMath.invert4(worldMatrix, inverse)) return null
        val o = FloatArray(3)
        val d = FloatArray(3)
        MeshMath.transformPoint4(inverse, origin[0], origin[1], origin[2], o)
        MeshMath.transformDirection3(inverse, direction[0], direction[1], direction[2], d)
        val dl = MeshMath.length(d[0], d[1], d[2])
        if (dl < 1e-20f) return null
        d[0] /= dl; d[1] /= dl; d[2] /= dl
        return floatArrayOf(o[0], o[1], o[2], d[0], d[1], d[2])
    }

    /**
     * Closest distance between segment p0->p1 and the ray o + t*d.
     * Returns `floatArrayOf(distance, t)`.
     */
    private fun segmentRayDistance(
        p0x: Float, p0y: Float, p0z: Float,
        p1x: Float, p1y: Float, p1z: Float,
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float
    ): FloatArray {
        val ux = p1x - p0x; val uy = p1y - p0y; val uz = p1z - p0z
        val wx = p0x - ox; val wy = p0y - oy; val wz = p0z - oz

        val a = ux * ux + uy * uy + uz * uz
        val b = ux * dx + uy * dy + uz * dz
        val c = dx * dx + dy * dy + dz * dz   // ~1 for a unit direction
        val d1 = ux * wx + uy * wy + uz * wz
        val e = dx * wx + dy * wy + dz * wz
        val denom = a * c - b * b

        var s: Float
        var t: Float
        if (denom > 1e-12f) {
            s = (b * e - c * d1) / denom
            t = (a * e - b * d1) / denom
        } else {
            s = 0f
            t = if (c > 1e-12f) e / c else 0f
        }

        if (s < 0f) s = 0f else if (s > 1f) s = 1f
        // Recompute t for the clamped s.
        val qx = p0x + s * ux - ox
        val qy = p0y + s * uy - oy
        val qz = p0z + s * uz - oz
        t = (qx * dx + qy * dy + qz * dz) / c
        if (t < 0f) {
            t = 0f
            s = if (a > 1e-12f) ((ux * wx + uy * wy + uz * wz) * -1f + (ux * dx + uy * dy + uz * dz) * 0f) / a else 0f
            s = if (a > 1e-12f) -(ux * wx + uy * wy + uz * wz) / a else 0f
            if (s < 0f) s = 0f else if (s > 1f) s = 1f
        }

        val cx = (p0x + s * ux) - (ox + t * dx)
        val cy = (p0y + s * uy) - (oy + t * dy)
        val cz = (p0z + s * uz) - (oz + t * dz)
        return floatArrayOf(sqrt(cx * cx + cy * cy + cz * cz), t)
    }
}
