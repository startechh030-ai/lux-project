package luxe.texture3d.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes an [EditMesh] to a GLB (binary glTF) container.
 *
 * ## Why this is part of the first edit-mode iteration
 *
 * Without it, Edit mode is a dead end: edits live only in a CPU `EditMesh` that is
 * discarded when the artist leaves Edit mode, so the work is lost. This closes the loop —
 * the edited mesh is written back out as a GLB that the existing import pipeline can read,
 * and that a ULX project can carry.
 *
 * ## Why hand-rolled
 *
 * `org.json` is an Android dependency, and keeping the writer free of it means the export
 * path is unit-testable on a plain JVM like the rest of the kernel. The JSON emitted here
 * is small and fixed-shape, so a `StringBuilder` is both simpler and faster than pulling in
 * a serialiser.
 *
 * The output deliberately matches the shape Luxe's own validator expects: one buffer, one
 * mesh, one primitive, POSITION/NORMAL/TEXCOORD_0 accessors with `min`/`max` on POSITION.
 *
 * ## Limits
 *
 * Positions and normals only — no vertex colours, tangents, or morph targets. Materials and
 * textures are not carried across, because `EditableMeshRenderer` does not yet own a
 * material graph. A mesh exported this way round-trips its geometry, not its appearance.
 */
object MeshGltfWriter {

    private const val GLB_MAGIC = 0x46546C67
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private const val COMPONENT_FLOAT = 5126
    private const val COMPONENT_UNSIGNED_SHORT = 5123
    private const val COMPONENT_UNSIGNED_INT = 5125

    /** Serialises [mesh] into a complete GLB byte array. */
    fun toGlb(mesh: EditMesh, name: String = "LuxeEdit"): ByteArray {
        require(mesh.faceCount > 0) { "cannot export an empty mesh" }
        require(mesh.vertexCount > 0) { "cannot export an empty mesh" }

        val vertices = mesh.vertexCount
        val indices = mesh.indices
        val useShortIndices = vertices <= 65535
        val indexStride = if (useShortIndices) 2 else 4
        val hasUvs = mesh.uvs != null && mesh.uvs!!.size >= vertices * 2

        val positionBytes = vertices * 12
        val normalBytes = vertices * 12
        val uvBytes = if (hasUvs) vertices * 8 else 0
        val indexBytes = indices.size * indexStride
        val binaryLength = positionBytes + normalBytes + uvBytes + indexBytes

        val binary = ByteBuffer.allocate(binaryLength).order(ByteOrder.LITTLE_ENDIAN)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertices) {
            val o = i * 3
            val x = mesh.positions[o]; val y = mesh.positions[o + 1]; val z = mesh.positions[o + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
            binary.putFloat(x); binary.putFloat(y); binary.putFloat(z)
        }

        val normals = mesh.normals ?: mesh.computeVertexNormals()
        for (i in 0 until vertices) {
            val o = i * 3
            binary.putFloat(normals[o]); binary.putFloat(normals[o + 1]); binary.putFloat(normals[o + 2])
        }

        if (hasUvs) {
            val uvs = mesh.uvs!!
            for (i in 0 until vertices) {
                val o = i * 2
                binary.putFloat(uvs[o]); binary.putFloat(uvs[o + 1])
            }
        }

        if (useShortIndices) {
            for (v in indices) binary.putShort((v and 0xFFFF).toShort())
        } else {
            for (v in indices) binary.putInt(v)
        }

        val positionView = 0
        val normalView = 1
        val uvView = 2
        val indexView = if (hasUvs) 3 else 2

        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Luxe Texture3D Edit Mode\"}")
            append(",\"scene\":0")
            append(",\"scenes\":[{\"nodes\":[0]}]")
            append(",\"nodes\":[{\"mesh\":0,\"name\":").append(quote(name)).append("}]")
            append(",\"meshes\":[{\"name\":").append(quote(name))
            append(",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1")
            if (hasUvs) append(",\"TEXCOORD_0\":").append(uvView)
            append("},\"indices\":").append(indexView).append(",\"mode\":4}]}]")
            append(",\"buffers\":[{\"byteLength\":").append(binaryLength).append("}]")
            append(",\"bufferViews\":[")
            append(view(0, 0, positionBytes))
            append(',').append(view(1, positionBytes, normalBytes))
            var nextOffset = positionBytes + normalBytes
            if (hasUvs) {
                append(',').append(view(uvView, nextOffset, uvBytes))
                nextOffset += uvBytes
            }
            append(',').append(view(indexView, nextOffset, indexBytes))
            append(']')
            append(",\"accessors\":[")
            // POSITION must carry min/max per the glTF spec, and Luxe's own validator checks it.
            append(
                accessor(
                    positionView, COMPONENT_FLOAT, vertices, "VEC3",
                    floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
                )
            )
            append(',').append(accessor(normalView, COMPONENT_FLOAT, vertices, "VEC3"))
            if (hasUvs) append(',').append(accessor(uvView, COMPONENT_FLOAT, vertices, "VEC2"))
            append(',').append(
                accessor(
                    indexView,
                    if (useShortIndices) COMPONENT_UNSIGNED_SHORT else COMPONENT_UNSIGNED_INT,
                    indices.size, "SCALAR"
                )
            )
            append(']')
            append('}')
        }

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = pad(jsonBytes.size, 4)
        val binPadded = pad(binaryLength, 4)

        val total = 12 + 8 + jsonPadded + 8 + binPadded
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(GLB_MAGIC)
        out.putInt(GLB_VERSION)
        out.putInt(total)
        out.putInt(jsonPadded)
        out.putInt(CHUNK_JSON)
        out.put(jsonBytes)
        repeat(jsonPadded - jsonBytes.size) { out.put(0x20.toByte()) }   // JSON pads with spaces
        out.putInt(binPadded)
        out.putInt(CHUNK_BIN)
        out.put(binary.array())
        repeat(binPadded - binaryLength) { out.put(0.toByte()) }          // BIN pads with zeros
        return out.array()
    }

    /** Writes [mesh] to [file], creating parent directories as needed. */
    fun writeGlb(file: File, mesh: EditMesh, name: String = file.nameWithoutExtension): File {
        file.parentFile?.mkdirs()
        file.writeBytes(toGlb(mesh, name))
        return file
    }

    private fun view(index: Int, offset: Int, length: Int): String =
        "{\"buffer\":0,\"byteOffset\":$offset,\"byteLength\":$length}"

    /**
     * @param bounds when supplied, `floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)`.
     */
    private fun accessor(
        view: Int,
        componentType: Int,
        count: Int,
        type: String,
        bounds: FloatArray? = null
    ): String = buildString {
        append("{\"bufferView\":").append(view)
        append(",\"componentType\":").append(componentType)
        append(",\"count\":").append(count)
        append(",\"type\":\"").append(type).append('"')
        if (bounds != null) {
            append(",\"min\":[")
            append(num(bounds[0])).append(',').append(num(bounds[1])).append(',').append(num(bounds[2]))
            append("],\"max\":[")
            append(num(bounds[3])).append(',').append(num(bounds[4])).append(',').append(num(bounds[5]))
            append(']')
        }
        append('}')
    }

    private fun pad(size: Int, alignment: Int): Int {
        val remainder = size % alignment
        return if (remainder == 0) size else size + (alignment - remainder)
    }

    /**
     * Locale-independent float formatting. `Float.toString` can emit `NaN` and `Infinity`,
     * which are not valid JSON and would produce a file nothing can parse, so non-finite
     * values are clamped to zero.
     */
    private fun num(value: Float): String =
        if (value.isNaN() || value.isInfinite()) "0.0" else value.toString()

    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
