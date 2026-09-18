package luxe.texture3d.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads a glTF asset into an [EditMesh] — the bridge that lets Edit mode see geometry.
 *
 * ## Why this is needed
 *
 * Luxe's import pipeline writes validated glTF 2.0 (a `model.gltf` plus external `.bin`
 * and texture files) and its metadata extractors read that JSON, but **nothing in the app
 * today reads actual vertex bytes**. Filament's gltfio consumes the buffer on load and
 * `EditorSceneManager.begin()` then calls `asset.releaseSourceData()`. Edit mode therefore
 * cannot get geometry from the live asset; it has to come from the source file, which is
 * exactly what [EditorSceneManager.Record.source] already points at.
 *
 * ## What it handles
 *
 * - JSON glTF folders (the converted-asset case) and GLB containers (the template case)
 * - External buffer files, `data:` URIs, and the GLB binary chunk
 * - Interleaved accessors (`byteStride`), all glTF component types, and normalised
 *   integer attributes
 * - TRIANGLES, TRIANGLE_STRIP and TRIANGLE_FAN primitives
 * - Node hierarchies with `matrix` or translation/rotation/scale, applied so the result
 *   is one mesh in the asset's own space
 *
 * ## Deliberate limits
 *
 * Buffers are read through [ByteSource] rather than being slurped into memory, so a large
 * `.bin` does not double the app's footprint while only the POSITION accessor is needed.
 * Morph targets and sparse accessors are reported as warnings and ignored — both are rare
 * in converted content and neither belongs in the first edit-mode iteration.
 */
object GltfMeshLoader {

    class LoadedMesh(
        val mesh: EditMesh,
        val name: String,
        val primitiveCount: Int,
        val warnings: List<String>
    ) {
        val isUsable: Boolean get() = mesh.faceCount > 0
    }

    private const val MAGIC_GLTF = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    // ------------------------------------------------------------- entry points

    /** Loads a converted asset folder containing `model.gltf`. */
    fun loadFolder(root: File, name: String = root.name): LoadedMesh {
        val file = File(root, "model.gltf")
        require(file.isFile) { "model.gltf is missing from ${root.name}" }
        val json = JSONObject(file.readText())
        return build(Source(root, json, null), name)
    }

    /** Loads a GLB container. */
    fun loadGlb(bytes: ByteArray, name: String): LoadedMesh {
        require(bytes.size >= 20) { "GLB is too small to be valid" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC_GLTF) { "Not a GLB container" }
        val version = buffer.int
        require(version == 2) { "Unsupported GLB version $version" }
        val total = buffer.int
        require(total <= bytes.size) { "GLB declares $total bytes but only ${bytes.size} are available" }

        var json: JSONObject? = null
        var bin: ByteArray? = null
        while (buffer.remaining() >= 8) {
            val chunkLength = buffer.int
            val chunkType = buffer.int
            if (chunkLength < 0 || chunkLength > buffer.remaining()) break
            val start = buffer.position()
            when (chunkType) {
                CHUNK_JSON -> {
                    val raw = ByteArray(chunkLength)
                    buffer.get(raw)
                    json = JSONObject(raw.toString(Charsets.UTF_8))
                }
                CHUNK_BIN -> {
                    val chunk = ByteArray(chunkLength)
                    buffer.get(chunk)
                    bin = chunk
                }
                else -> Unit
            }
            buffer.position(start + padded(chunkLength))
        }
        require(json != null) { "GLB contains no JSON chunk" }
        return build(Source(null, json!!, bin), name)
    }

    private fun padded(length: Int): Int = (length + 3) and 3.inv()

    // ------------------------------------------------------------------ loading

    private class Source(
        val root: File?,
        val json: JSONObject,
        val glbBin: ByteArray?
    ) {
        val warnings = mutableListOf<String>()
        private val bufferCache = HashMap<Int, ByteSource>()

        fun buffer(index: Int): ByteSource? {
            bufferCache[index]?.let { return it }
            val buffers = json.optJSONArray("buffers") ?: return null
            val entry = buffers.optJSONObject(index) ?: return null
            // An absent or empty "uri" means "use the GLB binary chunk" per the spec.
            val uri = entry.optString("uri", "")

            val resolved: ByteSource? = when {
                uri.isBlank() && glbBin != null -> ByteArraySource(glbBin)
                uri.isBlank() -> {
                    warnings += "Buffer $index has no URI and no GLB binary chunk"
                    null
                }
                uri.startsWith("data:") -> decodeDataUri(uri)
                else -> openFile(uri)
            }
            if (resolved != null) bufferCache[index] = resolved
            return resolved
        }

        private fun decodeDataUri(uri: String): ByteSource? {
            val comma = uri.indexOf(',')
            if (comma < 0) {
                warnings += "Buffer data URI is malformed"
                return null
            }
            val header = uri.substring(0, comma)
            if (!header.contains(";base64")) {
                warnings += "Buffer uses a non-base64 data URI"
                return null
            }
            return runCatching {
                val decoded = android.util.Base64.decode(
                    uri.substring(comma + 1), android.util.Base64.DEFAULT
                )
                ByteArraySource(decoded)
            }.onFailure {
                warnings += "Buffer data URI could not be decoded"
            }.getOrNull()
        }

        private fun openFile(uri: String): ByteSource? {
            val root = this.root ?: return null
            // Same traversal guard the rest of the pipeline uses: a glTF must not be able
            // to reach outside its own folder through an absolute or ../ buffer URI.
            val decoded = java.net.URLDecoder.decode(uri, "UTF-8").replace('\\', '/')
            val file = File(root, decoded).canonicalFile
            if (!file.path.startsWith(root.canonicalPath + File.separator)) {
                warnings += "Buffer URI escapes the asset folder: $uri"
                return null
            }
            if (!file.isFile) {
                warnings += "Buffer file is missing: $uri"
                return null
            }
            return FileSource(file)
        }
    }

    private interface ByteSource {
        val size: Long
        fun read(position: Long, dst: ByteArray, offset: Int, length: Int)
    }

    private class ByteArraySource(private val data: ByteArray) : ByteSource {
        override val size: Long get() = data.size.toLong()
        override fun read(position: Long, dst: ByteArray, offset: Int, length: Int) {
            val p = position.toInt()
            if (p < 0 || p + length > data.size) throw IndexOutOfBoundsException("buffer overrun")
            System.arraycopy(data, p, dst, offset, length)
        }
    }

    private class FileSource(private val file: File) : ByteSource {
        override val size: Long get() = file.length()
        override fun read(position: Long, dst: ByteArray, offset: Int, length: Int) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(position)
                var done = 0
                while (done < length) {
                    val n = raf.read(dst, offset + done, length - done)
                    if (n < 0) break
                    done += n
                }
                if (done < length) throw IndexOutOfBoundsException("buffer file truncated")
            }
        }
    }

    private fun build(source: Source, name: String): LoadedMesh {
        val meshes = source.json.optJSONArray("meshes") ?: JSONArray()
        val nodes = source.json.optJSONArray("nodes") ?: JSONArray()

        // Node index -> (mesh index, world matrix)
        val placements = mutableListOf<Pair<Int, FloatArray>>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val meshIndex = node.optInt("mesh", -1)
            if (meshIndex < 0) continue
            placements += meshIndex to nodeWorldMatrix(nodes, i, mutableSetOf())
        }
        // A mesh not referenced by any node still has to be editable.
        if (placements.isEmpty()) {
            for (m in 0 until meshes.length()) placements += m to identityMatrix()
        }

        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        var primitiveCount = 0
        var wantsNormals = true
        var wantsUvs = true

        for ((meshIndex, matrix) in placements) {
            val primitives = meshes.optJSONObject(meshIndex)?.optJSONArray("primitives") ?: continue
            for (p in 0 until primitives.length()) {
                val primitive = primitives.optJSONObject(p) ?: continue
                if (primitive.has("targets")) {
                    source.warnings += "Primitive has morph targets, which Edit mode ignores"
                }
                val attributes = primitive.optJSONObject("attributes") ?: JSONObject()
                val positionIndex = attributes.optInt("POSITION", -1)
                if (positionIndex < 0) {
                    source.warnings += "Primitive $p has no POSITION attribute"
                    continue
                }
                val localPositions = readFloats(source, positionIndex, 3) ?: run {
                    source.warnings += "POSITION accessor $positionIndex could not be read"
                    return@run null
                } ?: continue
                if (localPositions.isEmpty()) continue

                val base = positions.size / 3
                val transformed = FloatArray(localPositions.size)
                for (i in localPositions.indices step 3) {
                    val out = FloatArray(3)
                    MeshMath.transformPoint4(
                        matrix, localPositions[i], localPositions[i + 1], localPositions[i + 2], out
                    )
                    transformed[i] = out[0]
                    transformed[i + 1] = out[1]
                    transformed[i + 2] = out[2]
                }
                for (v in transformed) positions += v

                val normalIndex = attributes.optInt("NORMAL", -1)
                val localNormals = if (normalIndex >= 0) readFloats(source, normalIndex, 3) else null
                if (localNormals != null && localNormals.size == localPositions.size) {
                    for (i in localNormals.indices step 3) {
                        val out = FloatArray(3)
                        MeshMath.transformDirection3(matrix, localNormals[i], localNormals[i + 1], localNormals[i + 2], out)
                        MeshMath.normalize(out)
                        normals += out[0]; normals += out[1]; normals += out[2]
                    }
                } else {
                    wantsNormals = false
                }

                val uvIndex = attributes.optInt("TEXCOORD_0", -1)
                val localUvs = if (uvIndex >= 0) readFloats(source, uvIndex, 2) else null
                if (localUvs != null && localUvs.size / 2 == localPositions.size / 3) {
                    for (v in localUvs) uvs += v
                } else {
                    wantsUvs = false
                }

                val mode = primitive.optInt("mode", 4)
                val indexAccessor = primitive.optInt("indices", -1)
                val localIndices = if (indexAccessor >= 0) {
                    readIndices(source, indexAccessor) ?: IntArray(localPositions.size / 3) { it }
                } else {
                    IntArray(localPositions.size / 3) { it }
                }

                when (mode) {
                    4 -> appendTriangles(indices, localIndices, base)
                    5 -> appendTriangleStrip(indices, localIndices, base)
                    6 -> appendTriangleFan(indices, localIndices, base)
                    else -> source.warnings += "Primitive $p uses unsupported mode $mode"
                }
                primitiveCount++
            }
        }

        val mesh = EditMesh(
            positions = positions.toFloatArray(),
            indices = indices.toIntArray(),
            normals = if (wantsNormals && normals.size == positions.size) normals.toFloatArray() else null,
            uvs = if (wantsUvs && uvs.size / 2 == positions.size / 3) uvs.toFloatArray() else null
        )
        if (mesh.normals == null) mesh.refreshNormals()

        val structural = mesh.validate()
        if (structural.isNotEmpty()) {
            source.warnings += "Loaded mesh is not clean: ${structural.take(3).joinToString("; ")}"
        }
        val topology = MeshTopology.of(mesh)
        val nonManifold = topology.nonManifoldEdges()
        if (nonManifold.isNotEmpty()) {
            source.warnings += "${nonManifold.size} non-manifold edge(s); extrude may behave unevenly"
        }
        return LoadedMesh(mesh, name, primitiveCount, source.warnings.distinct())
    }

    private fun appendTriangles(out: ArrayList<Int>, idx: IntArray, base: Int) {
        for (i in 0 until (idx.size - idx.size % 3) step 3) {
            out += base + idx[i]
            out += base + idx[i + 1]
            out += base + idx[i + 2]
        }
    }

    private fun appendTriangleStrip(out: ArrayList<Int>, idx: IntArray, base: Int) {
        for (i in 0 until idx.size - 2) {
            if (i % 2 == 0) {
                out += base + idx[i]; out += base + idx[i + 1]; out += base + idx[i + 2]
            } else {
                out += base + idx[i + 1]; out += base + idx[i]; out += base + idx[i + 2]
            }
        }
    }

    private fun appendTriangleFan(out: ArrayList<Int>, idx: IntArray, base: Int) {
        for (i in 1 until idx.size - 1) {
            out += base + idx[0]; out += base + idx[i]; out += base + idx[i + 1]
        }
    }

    // ---------------------------------------------------------------- accessors

    private fun accessorOf(source: Source, accessorIndex: Int): JSONObject? {
        val accessors = source.json.optJSONArray("accessors") ?: return null
        if (accessorIndex !in 0 until accessors.length()) return null
        return accessors.optJSONObject(accessorIndex)
    }

    private fun readFloats(source: Source, accessorIndex: Int, expectedComponents: Int): FloatArray? {
        val accessor = accessorOf(source, accessorIndex) ?: return null
        if (accessor.has("sparse")) {
            source.warnings += "Accessor $accessorIndex uses sparse storage, which is ignored"
        }
        val (components, data, _) = readAccessor(source, accessor) ?: return null
        if (components.size != expectedComponents) {
            source.warnings += "Accessor $accessorIndex has ${components.size} components, expected $expectedComponents"
            return null
        }
        return data
    }

    private fun readIndices(source: Source, accessorIndex: Int): IntArray? {
        val accessor = accessorOf(source, accessorIndex) ?: return null
        val (components, data, _) = readAccessor(source, accessor) ?: return null
        if (components.isEmpty()) return null
        return IntArray(data.size) { i ->
            val v = data[i]
            if (v < 0f) 0 else v.toInt()
        }
    }

    /** Returns (componentName list size, data, elementCount). */
    private fun readAccessor(
        source: Source,
        accessor: JSONObject
    ): Triple<IntArray, FloatArray, Int>? {
        val viewIndex = accessor.optInt("bufferView", -1)
        if (viewIndex < 0) return null
        val views = source.json.optJSONArray("bufferViews") ?: return null
        if (viewIndex !in 0 until views.length()) return null
        val view = views.optJSONObject(viewIndex) ?: return null
        val bufferIndex = view.optInt("buffer", -1)
        val byteSource = source.buffer(bufferIndex) ?: return null

        val componentType = accessor.optInt("componentType", 5126)
        val componentSize = componentSize(componentType)
        val componentCount = componentCount(accessor.optString("type", "SCALAR"))
        val normalized = accessor.optBoolean("normalized", false)
        if (componentSize <= 0 || componentCount <= 0) return null

        val count = accessor.optInt("count", 0)
        if (count <= 0) return Triple(IntArray(componentCount), FloatArray(0), 0)

        val viewOffset = view.optLong("byteOffset", 0)
        val accessorOffset = accessor.optLong("byteOffset", 0)
        val start = viewOffset + accessorOffset
        val stride = view.optInt("byteStride", 0)
        val elementStride = if (stride > 0) stride else componentCount * componentSize

        val scratch = ByteArray(componentSize)
        val out = FloatArray(count * componentCount)
        val wrap = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)

        for (element in 0 until count) {
            val base = start + element.toLong() * elementStride
            if (base + componentCount * componentSize > byteSource.size) {
                source.warnings += "Accessor runs past the end of its buffer"
                return Triple(IntArray(componentCount), out.copyOf(element * componentCount), element)
            }
            for (c in 0 until componentCount) {
                byteSource.read(base + (c.toLong() * componentSize), scratch, 0, componentSize)
                wrap.position(0)
                out[element * componentCount + c] = when (componentType) {
                    5126 -> wrap.float
                    5121 -> {
                        val raw = wrap.int and 0xFF
                        if (normalized) raw / 255f else raw.toFloat()
                    }
                    5120 -> {
                        val raw = wrap.get().toInt()
                        if (normalized) (raw / 127f).coerceIn(-1f, 1f) else raw.toFloat()
                    }
                    5123 -> {
                        val raw = wrap.int and 0xFFFF
                        if (normalized) raw / 65535f else raw.toFloat()
                    }
                    5122 -> {
                        val raw = wrap.short.toInt()
                        if (normalized) (raw / 32767f).coerceIn(-1f, 1f) else raw.toFloat()
                    }
                    5125 -> (wrap.int and 0x7FFFFFFF).toFloat()
                    else -> 0f
                }
            }
        }
        return Triple(IntArray(componentCount), out, count)
    }

    private fun componentSize(componentType: Int): Int = when (componentType) {
        5120, 5121 -> 1
        5122, 5123 -> 2
        5125, 5126 -> 4
        else -> -1
    }

    private fun componentCount(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4", "MAT2" -> 4
        "MAT3" -> 9
        "MAT4" -> 16
        else -> -1
    }

    // ------------------------------------------------------------------- nodes

    private fun identityMatrix(): FloatArray =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun nodeWorldMatrix(nodes: JSONArray, index: Int, visited: MutableSet<Int>): FloatArray {
        if (!visited.add(index)) return identityMatrix() // cycle guard
        val node = nodes.optJSONObject(index) ?: return identityMatrix()
        val local = if (node.has("matrix")) {
            val a = node.optJSONArray("matrix") ?: JSONArray()
            FloatArray(16) { i -> a.optDouble(i, identityMatrix()[i].toDouble()).toFloat() }
        } else {
            val t = node.optJSONArray("translation")
            val r = node.optJSONArray("rotation")
            val s = node.optJSONArray("scale")
            composeTrs(
                if (t != null && t.length() >= 3) floatArrayOf(
                    t.optDouble(0).toFloat(), t.optDouble(1).toFloat(), t.optDouble(2).toFloat()
                ) else floatArrayOf(0f, 0f, 0f),
                if (r != null && r.length() >= 4) floatArrayOf(
                    r.optDouble(0).toFloat(), r.optDouble(1).toFloat(),
                    r.optDouble(2).toFloat(), r.optDouble(3).toFloat()
                ) else floatArrayOf(0f, 0f, 0f, 1f),
                if (s != null && s.length() >= 3) floatArrayOf(
                    s.optDouble(0).toFloat(), s.optDouble(1).toFloat(), s.optDouble(2).toFloat()
                ) else floatArrayOf(1f, 1f, 1f)
            )
        }
        val parent = node.optInt("parent", -1)
        if (parent < 0) return local
        val parentMatrix = nodeWorldMatrix(nodes, parent, visited)
        val out = FloatArray(16)
        multiply(out, parentMatrix, local)
        return out
    }

    private fun composeTrs(t: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
        val translation = identityMatrix().apply {
            this[12] = t[0]; this[13] = t[1]; this[14] = t[2]
        }
        val rotation = quaternionMatrix(q)
        val scale = identityMatrix().apply {
            this[0] = s[0]; this[5] = s[1]; this[10] = s[2]
        }
        val rs = FloatArray(16)
        val out = FloatArray(16)
        multiply(rs, rotation, scale)
        multiply(out, translation, rs)
        return out
    }

    /** Column-major 4x4 multiply: out = a * b. */
    private fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
    }

    /** Mirrors the quaternion-to-matrix conversion used by EditorSceneManager. */
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
}
