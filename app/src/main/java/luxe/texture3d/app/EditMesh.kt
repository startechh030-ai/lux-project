package luxe.texture3d.app

import kotlin.math.sqrt

/**
 * Small allocation-free 3D vector helpers used by the mesh kernel.
 *
 * Every routine writes into a caller-supplied [FloatArray] instead of returning a new
 * one. Edit operators touch every vertex several times per call; on a mid-range phone
 * that difference is the gap between a snappy operator and a GC stutter.
 */
object MeshMath {
    fun cross(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        out: FloatArray, off: Int = 0
    ) {
        out[off] = ay * bz - az * by
        out[off + 1] = az * bx - ax * bz
        out[off + 2] = ax * by - ay * bx
    }

    fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /** Normalises the 3 floats starting at [off]. Returns the original length. */
    fun normalize(out: FloatArray, off: Int = 0): Float {
        val len = length(out[off], out[off + 1], out[off + 2])
        if (len > 1e-20f) {
            val inv = 1f / len
            out[off] *= inv; out[off + 1] *= inv; out[off + 2] *= inv
        }
        return len
    }

    fun dot(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float
    ): Float = ax * bx + ay * by + az * bz

    fun distance(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float
    ): Float {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Inverts a column-major 4x4 matrix (the `android.opengl.Matrix` / Filament layout).
     *
     * Kept here rather than delegating to `android.opengl.Matrix.invertM` so the mesh
     * kernel stays free of Android imports and remains runnable on a plain JVM.
     *
     * @return false when the matrix is singular.
     */
    fun invert4(m: FloatArray, out: FloatArray): Boolean {
        require(m.size == 16 && out.size == 16) { "expected 4x4 matrices" }
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]; val a03 = m[3]
        val a10 = m[4]; val a11 = m[5]; val a12 = m[6]; val a13 = m[7]
        val a20 = m[8]; val a21 = m[9]; val a22 = m[10]; val a23 = m[11]
        val a30 = m[12]; val a31 = m[13]; val a32 = m[14]; val a33 = m[15]

        val b00 = a00 * a11 - a01 * a10
        val b01 = a00 * a12 - a02 * a10
        val b02 = a00 * a13 - a03 * a10
        val b03 = a01 * a12 - a02 * a11
        val b04 = a01 * a13 - a03 * a11
        val b05 = a02 * a13 - a03 * a12
        val b06 = a20 * a31 - a21 * a30
        val b07 = a20 * a32 - a22 * a30
        val b08 = a20 * a33 - a23 * a30
        val b09 = a21 * a32 - a22 * a31
        val b10 = a21 * a33 - a23 * a31
        val b11 = a22 * a33 - a23 * a32

        val det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06
        if (det == 0f) return false
        val inv = 1f / det

        out[0] = (a11 * b11 - a12 * b10 + a13 * b09) * inv
        out[1] = (a02 * b10 - a01 * b11 - a03 * b09) * inv
        out[2] = (a31 * b05 - a32 * b04 + a33 * b03) * inv
        out[3] = (a22 * b04 - a21 * b05 - a23 * b03) * inv
        out[4] = (a12 * b08 - a10 * b11 - a13 * b07) * inv
        out[5] = (a00 * b11 - a02 * b08 + a03 * b07) * inv
        out[6] = (a32 * b02 - a30 * b05 - a33 * b01) * inv
        out[7] = (a20 * b05 - a22 * b02 + a23 * b01) * inv
        out[8] = (a10 * b10 - a11 * b08 + a13 * b06) * inv
        out[9] = (a01 * b08 - a00 * b10 - a03 * b06) * inv
        out[10] = (a30 * b04 - a31 * b02 + a33 * b00) * inv
        out[11] = (a21 * b02 - a20 * b04 - a23 * b00) * inv
        out[12] = (a11 * b07 - a10 * b09 - a12 * b06) * inv
        out[13] = (a00 * b09 - a01 * b07 + a02 * b06) * inv
        out[14] = (a31 * b01 - a30 * b03 - a32 * b00) * inv
        out[15] = (a20 * b03 - a21 * b01 + a22 * b00) * inv
        return true
    }

    /** Column-major 4x4 multiply: `out = a * b`. May alias either input. */
    fun multiply4(a: FloatArray, b: FloatArray, out: FloatArray) {
        require(a.size == 16 && b.size == 16 && out.size == 16) { "expected 4x4 matrices" }
        val tmp = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                tmp[col * 4 + row] = sum
            }
        }
        tmp.copyInto(out)
    }

    /** Transforms a point by a column-major 4x4 matrix (w division applied). */
    fun transformPoint4(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, off: Int = 0) {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        val iw = if (w != 0f) 1f / w else 1f
        out[off] = (m[0] * x + m[4] * y + m[8] * z + m[12]) * iw
        out[off + 1] = (m[1] * x + m[5] * y + m[9] * z + m[13]) * iw
        out[off + 2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) * iw
    }

    /** Transforms a direction by a column-major 4x4 matrix (translation ignored). */
    fun transformDirection3(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, off: Int = 0) {
        out[off] = m[0] * x + m[4] * y + m[8] * z
        out[off + 1] = m[1] * x + m[5] * y + m[9] * z
        out[off + 2] = m[2] * x + m[6] * y + m[10] * z
    }
}

/**
 * Host-side editable triangle mesh — the piece Luxe is currently missing.
 *
 * ## Why this exists
 *
 * Luxe keeps geometry exclusively inside Filament/gltfio GPU buffers.
 * `EditorSceneManager.begin()` even calls `asset.releaseSourceData()`, so after a load
 * there is nothing on the CPU that an editor could change. Every viewport feature so far
 * (placement, selection bounds, transforms) is therefore object-level: it moves whole
 * renderables and never touches a vertex.
 *
 * [EditMesh] is the host-side mirror that Edit mode needs. A mesh is loaded into one of
 * these from its glTF source, edited here, and re-uploaded to the GPU.
 *
 * ## Representation
 *
 * Flat primitive arrays rather than vertex objects. A 200k-triangle mesh as objects costs
 * hundreds of megabytes and kills the GC on Android; as `FloatArray`/`IntArray` it stays
 * compact, cache-friendly, and directly uploadable to Filament buffers.
 *
 * ## Ownership
 *
 * Operators in [MeshOperators] treat meshes as immutable: each one returns a **new**
 * [EditMesh] and leaves its input untouched. That makes undo/redo a matter of swapping
 * references (see [MeshHistory]) and keeps operators composable.
 *
 * ## Platform
 *
 * Pure Kotlin with no Android imports, so this file compiles unchanged for Android,
 * desktop JVM, and any future Windows/Linux shell. Test suite: `verification/MeshKernelTest.kt`.
 */
class EditMesh(
    var positions: FloatArray,
    var indices: IntArray,
    var normals: FloatArray? = null,
    var uvs: FloatArray? = null
) {
    init {
        require(positions.size % 3 == 0) { "positions length must be a multiple of 3" }
        require(indices.size % 3 == 0) { "indices length must be a multiple of 3" }
        require(normals == null || normals!!.size == positions.size) {
            "normals must have the same length as positions"
        }
        require(uvs == null || uvs!!.size / 2 == vertexCount) {
            "uvs must hold one pair per vertex"
        }
    }

    val vertexCount: Int get() = positions.size / 3

    /** Number of triangles. Named [faceCount] to match editor vocabulary. */
    val faceCount: Int get() = indices.size / 3

    // ---------------------------------------------------------------- access

    fun vertex(v: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val i = v * 3
        out[0] = positions[i]; out[1] = positions[i + 1]; out[2] = positions[i + 2]
        return out
    }

    fun setVertex(v: Int, x: Float, y: Float, z: Float) {
        val i = v * 3
        positions[i] = x; positions[i + 1] = y; positions[i + 2] = z
    }

    fun face(f: Int, out: IntArray = IntArray(3)): IntArray {
        val i = f * 3
        out[0] = indices[i]; out[1] = indices[i + 1]; out[2] = indices[i + 2]
        return out
    }

    fun setFace(f: Int, a: Int, b: Int, c: Int) {
        val i = f * 3
        indices[i] = a; indices[i + 1] = b; indices[i + 2] = c
    }

    // ------------------------------------------------------------- geometry

    /**
     * Unit normal of face [f] from its winding (right-handed, counter-clockwise
     * when seen from outside). Returns a zero vector for degenerate faces.
     */
    fun faceNormal(f: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val i = f * 3
        val ia = indices[i] * 3; val ib = indices[i + 1] * 3; val ic = indices[i + 2] * 3
        val ax = positions[ia]; val ay = positions[ia + 1]; val az = positions[ia + 2]
        val e1x = positions[ib] - ax; val e1y = positions[ib + 1] - ay; val e1z = positions[ib + 2] - az
        val e2x = positions[ic] - ax; val e2y = positions[ic + 1] - ay; val e2z = positions[ic + 2] - az
        out[0] = 0f; out[1] = 0f; out[2] = 0f
        MeshMath.cross(e1x, e1y, e1z, e2x, e2y, e2z, out)
        MeshMath.normalize(out)
        return out
    }

    /**
     * Unnormalised face normal. Its length is twice the triangle area, which makes it the
     * correct weight for area-weighted vertex normals — this is the "unweighted" cross
     * product rather than the average-of-face-normals approximation.
     */
    fun faceNormalWeighted(f: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val i = f * 3
        val ia = indices[i] * 3; val ib = indices[i + 1] * 3; val ic = indices[i + 2] * 3
        val ax = positions[ia]; val ay = positions[ia + 1]; val az = positions[ia + 2]
        val e1x = positions[ib] - ax; val e1y = positions[ib + 1] - ay; val e1z = positions[ib + 2] - az
        val e2x = positions[ic] - ax; val e2y = positions[ic + 1] - ay; val e2z = positions[ic + 2] - az
        out[0] = 0f; out[1] = 0f; out[2] = 0f
        MeshMath.cross(e1x, e1y, e1z, e2x, e2y, e2z, out)
        return out
    }

    fun faceCentroid(f: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val i = f * 3
        val ia = indices[i] * 3; val ib = indices[i + 1] * 3; val ic = indices[i + 2] * 3
        out[0] = (positions[ia] + positions[ib] + positions[ic]) / 3f
        out[1] = (positions[ia + 1] + positions[ib + 1] + positions[ic + 1]) / 3f
        out[2] = (positions[ia + 2] + positions[ib + 2] + positions[ic + 2]) / 3f
        return out
    }

    /** Area-weighted vertex normals for the whole mesh. */
    fun computeVertexNormals(): FloatArray {
        val out = FloatArray(positions.size)
        val n = FloatArray(3)
        for (f in 0 until faceCount) {
            faceNormalWeighted(f, n)
            val i = f * 3
            for (k in 0..2) {
                val o = indices[i + k] * 3
                out[o] += n[0]; out[o + 1] += n[1]; out[o + 2] += n[2]
            }
        }
        for (v in 0 until vertexCount) {
            val o = v * 3
            if (MeshMath.normalize(out, o) == 0f) out[o + 1] = 1f
        }
        return out
    }

    fun refreshNormals(): EditMesh {
        normals = computeVertexNormals()
        return this
    }

    /**
     * Axis-aligned bounds packed as `[minX, minY, minZ, maxX, maxY, maxZ]`.
     * Returns `0,0,0,0,0,0` for an empty mesh.
     */
    fun bounds(out: FloatArray = FloatArray(6)): FloatArray {
        if (vertexCount == 0) {
            out.fill(0f)
            return out
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < positions.size) {
            val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
            i += 3
        }
        out[0] = minX; out[1] = minY; out[2] = minZ
        out[3] = maxX; out[4] = maxY; out[5] = maxZ
        return out
    }

    fun center(out: FloatArray = FloatArray(3)): FloatArray {
        val b = bounds()
        out[0] = (b[0] + b[3]) * 0.5f
        out[1] = (b[1] + b[4]) * 0.5f
        out[2] = (b[2] + b[5]) * 0.5f
        return out
    }

    /** Reverses winding of every triangle, flipping the surface inside out. */
    fun flipNormals(): EditMesh {
        var i = 0
        while (i < indices.size) {
            val t = indices[i + 1]
            indices[i + 1] = indices[i + 2]
            indices[i + 2] = t
            i += 3
        }
        normals = computeVertexNormals()
        return this
    }

    /**
     * Applies a column-major 4x4 matrix (the layout used by `android.opengl.Matrix`
     * and by Filament's transform manager) to every position.
     *
     * Implemented inline rather than with `android.opengl.Matrix` so this class stays
     * free of Android imports and remains testable on a plain JVM.
     */
    fun transform(m: FloatArray): EditMesh {
        require(m.size == 16) { "expected a 4x4 matrix" }
        var i = 0
        while (i < positions.size) {
            val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
            positions[i] = m[0] * x + m[4] * y + m[8] * z + m[12]
            positions[i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
            positions[i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            i += 3
        }
        normals?.let { n ->
            var j = 0
            while (j < n.size) {
                val x = n[j]; val y = n[j + 1]; val z = n[j + 2]
                val nx = m[0] * x + m[4] * y + m[8] * z
                val ny = m[1] * x + m[5] * y + m[9] * z
                val nz = m[2] * x + m[6] * y + m[10] * z
                n[j] = nx; n[j + 1] = ny; n[j + 2] = nz
                MeshMath.normalize(n, j)
                j += 3
            }
        }
        return this
    }

    /** Deep copy. Used by [MeshHistory] to snapshot undo states. */
    fun clone(): EditMesh = EditMesh(
        positions.copyOf(),
        indices.copyOf(),
        normals?.copyOf(),
        uvs?.copyOf()
    )

    /**
     * Human-readable sanity report. Empty when the mesh is well formed.
     *
     * Meshes reaching Luxe through Assimp are frequently non-manifold, so the editor
     * should call this after loading and surface the result instead of crashing later.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        for (i in indices.indices) {
            val v = indices[i]
            if (v < 0 || v >= vertexCount) {
                problems += "index $i references vertex $v outside 0..${vertexCount - 1}"
                break
            }
        }
        val n = FloatArray(3)
        for (f in 0 until faceCount) {
            faceNormalWeighted(f, n)
            if (MeshMath.length(n[0], n[1], n[2]) < 1e-12f) {
                problems += "face $f is degenerate (zero area)"
                break
            }
        }
        for (i in positions.indices) {
            if (positions[i].isNaN() || positions[i].isInfinite()) {
                problems += "position component $i is not finite"
                break
            }
        }
        return problems
    }

    companion object {
        fun empty(): EditMesh = EditMesh(FloatArray(0), IntArray(0))

        /**
         * A single triangle in the XZ plane, wound so its normal is +Y.
         * Winding is (0, 2, 1) rather than (0, 1, 2): with a right-handed cross product
         * the latter points at -Y, which is the classic way to end up with a face that
         * is invisible from above.
         */
        fun singleTriangle(): EditMesh = EditMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            intArrayOf(0, 2, 1)
        ).refreshNormals()

        /** A closed, manifold unit cube centred on the origin. 8 verts, 12 triangles. */
        fun cube(size: Float = 1f): EditMesh {
            val h = size * 0.5f
            val positions = floatArrayOf(
                -h, -h, -h, h, -h, -h, h, h, -h, -h, h, -h,   // 0..3 back  (z = -h)
                -h, -h, h, h, -h, h, h, h, h, -h, h, h        // 4..7 front (z = +h)
            )
            val indices = intArrayOf(
                0, 2, 1, 0, 3, 2,   // back
                4, 5, 6, 4, 6, 7,   // front
                0, 1, 5, 0, 5, 4,   // bottom
                3, 7, 6, 3, 6, 2,   // top
                0, 4, 7, 0, 7, 3,   // left
                1, 2, 6, 1, 6, 5    // right
            )
            return EditMesh(positions, indices).refreshNormals()
        }

        /**
         * A flat grid of [segments] x [segments] quads in the XZ plane, subdivided into
         * triangles, facing +Y. Useful as an edit-mode stress surface.
         */
        fun grid(segments: Int = 4, size: Float = 1f): EditMesh {
            require(segments >= 1) { "segments must be at least 1" }
            val step = size / segments
            val positions = FloatArray((segments + 1) * (segments + 1) * 3)
            var p = 0
            for (z in 0..segments) {
                for (x in 0..segments) {
                    positions[p++] = -size / 2 + x * step
                    positions[p++] = 0f
                    positions[p++] = -size / 2 + z * step
                }
            }
            val indices = IntArray(segments * segments * 6)
            var i = 0
            val row = segments + 1
            for (z in 0 until segments) {
                for (x in 0 until segments) {
                    val a = z * row + x
                    val b = a + 1
                    val c = a + row
                    val d = c + 1
                    indices[i++] = a; indices[i++] = c; indices[i++] = b
                    indices[i++] = b; indices[i++] = c; indices[i++] = d
                }
            }
            return EditMesh(positions, indices).refreshNormals()
        }
    }
}

/**
 * Incremental mesh construction for operators that know their output size only as they
 * go. Grows geometrically, so appending is amortised O(1).
 */
class MeshBuilder(initialVertices: Int = 256, initialFaces: Int = 256) {
    private var pos = FloatArray(initialVertices.coerceAtLeast(16) * 3)
    private var idx = IntArray(initialFaces.coerceAtLeast(16) * 3)
    private var vertCount = 0
    private var faceCount = 0

    val vertexCount: Int get() = vertCount
    val faceCountInternal: Int get() = faceCount

    fun addVertex(x: Float, y: Float, z: Float): Int {
        if ((vertCount + 1) * 3 > pos.size) {
            pos = pos.copyOf(((vertCount + 1) * 3 * 2).coerceAtLeast(pos.size * 2))
        }
        val o = vertCount * 3
        pos[o] = x; pos[o + 1] = y; pos[o + 2] = z
        return vertCount++
    }

    fun addVertex(p: FloatArray): Int = addVertex(p[0], p[1], p[2])

    fun addTriangle(a: Int, b: Int, c: Int) {
        if ((faceCount + 1) * 3 > idx.size) {
            idx = idx.copyOf(((faceCount + 1) * 3 * 2).coerceAtLeast(idx.size * 2))
        }
        val o = faceCount * 3
        idx[o] = a; idx[o + 1] = b; idx[o + 2] = c
        faceCount++
    }

    fun build(): EditMesh = EditMesh(
        pos.copyOf(vertCount * 3),
        idx.copyOf(faceCount * 3)
    )
}
