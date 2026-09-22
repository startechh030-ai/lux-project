package luxe.texture3d.verification

import luxe.texture3d.app.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Standalone JVM verification harness for the Edit-mode mesh kernel.
 *
 * ## Running it
 *
 * The kernel is deliberately free of Android imports so it can be exercised off-device:
 *
 * ```
 * kotlinc app/src/main/java/luxe/texture3d/app/{EditMesh,MeshTopology,MeshSelection,
 *         MeshOperators,MeshRaycast,MeshHistory}.kt \
 *         app/src/test/java/luxe/texture3d/verification/MeshKernelTest.kt \
 *         -include-runtime -d /tmp/meshkernel.jar
 * java -jar /tmp/meshkernel.jar
 * ```
 *
 * It is a plain `main()` rather than a JUnit suite on purpose: it runs with nothing but
 * a Kotlin compiler, so it can be used in CI long before the Android test runner is
 * configured. Converting each `check` to a `@Test` later is mechanical.
 *
 * ## What it protects
 *
 * The invariants that are easy to break and hard to see in a viewport:
 * Euler characteristic (operators must not silently change topology), winding
 * consistency (a flipped wall is invisible until it is lit), index validity, and the
 * Loop/linear subdivision vertex-count identities.
 */
object MeshKernelTest {

    private var passed = 0
    private val failures = mutableListOf<String>()
    private var group = ""

    private fun section(name: String) {
        group = name
        println("\n── $name")
    }

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
            println("   PASS  $name")
        } else {
            failures += "[$group] $name${if (detail.isNotBlank()) " — $detail" else ""}"
            println("   FAIL  $name${if (detail.isNotBlank()) " — $detail" else ""}")
        }
    }

    private fun checkEquals(name: String, expected: Int, actual: Int) =
        check(name, expected == actual, "expected $expected, got $actual")

    private fun checkApprox(name: String, expected: Float, actual: Float, tol: Float = 1e-5f) =
        check(name, abs(expected - actual) <= tol, "expected $expected, got $actual (tol $tol)")

    // ------------------------------------------------------------- helpers

    /** Every directed edge should appear at most once in a consistently wound surface. */
    private fun misorientedEdgeCount(mesh: EditMesh): Int {
        val seen = HashSet<Long>(mesh.faceCount * 3)
        var duplicates = 0
        for (f in 0 until mesh.faceCount) {
            val o = f * 3
            for (k in 0..2) {
                val a = mesh.indices[o + k]
                val b = mesh.indices[o + (k + 1) % 3]
                val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
                if (!seen.add(key)) duplicates++
            }
        }
        return duplicates
    }

    private fun triangleArea(mesh: EditMesh, f: Int): Float {
        val n = FloatArray(3)
        mesh.faceNormalWeighted(f, n)
        return MeshMath.length(n[0], n[1], n[2]) * 0.5f
    }

    private fun meshArea(mesh: EditMesh): Float {
        var total = 0f
        for (f in 0 until mesh.faceCount) total += triangleArea(mesh, f)
        return total
    }

    private fun maxY(mesh: EditMesh): Float {
        var best = -Float.MAX_VALUE
        var i = 1
        while (i < mesh.positions.size) {
            if (mesh.positions[i] > best) best = mesh.positions[i]
            i += 3
        }
        return best
    }

    /** True when some vertex sits within [tol] of an analytically predicted position. */
    private fun hasVertexNear(mesh: EditMesh, x: Float, y: Float, z: Float, tol: Float = 1e-5f): Boolean {
        var i = 0
        while (i < mesh.positions.size) {
            if (MeshMath.distance(mesh.positions[i], mesh.positions[i + 1], mesh.positions[i + 2], x, y, z) <= tol) {
                return true
            }
            i += 3
        }
        return false
    }

    private fun maxRadius(mesh: EditMesh): Float {
        var best = 0f
        for (i in mesh.positions.indices step 3) {
            val r = MeshMath.length(mesh.positions[i], mesh.positions[i + 1], mesh.positions[i + 2])
            if (r > best) best = r
        }
        return best
    }

    /**
     * Total length of the boundary curve.
     *
     * Stronger than counting boundary edges, because splitting a boundary edge into two
     * legitimately raises the count while leaving the outline identical. This measures the
     * outline itself, so an operator that opens a crack in the interior shows up as extra
     * length.
     */
    private fun boundaryLength(mesh: EditMesh): Float {
        val topo = MeshTopology.of(mesh)
        var total = 0f
        for (e in 0 until topo.edgeCount) {
            if (!topo.isBoundaryEdge(e)) continue
            val a = topo.edgeV0(e) * 3
            val b = topo.edgeV1(e) * 3
            total += MeshMath.distance(
                mesh.positions[a], mesh.positions[a + 1], mesh.positions[a + 2],
                mesh.positions[b], mesh.positions[b + 1], mesh.positions[b + 2]
            )
        }
        return total
    }

    private fun assertValidMesh(name: String, mesh: EditMesh) {
        val problems = mesh.validate()
        check("$name is well formed", problems.isEmpty(), problems.joinToString("; "))
        check("$name has unit-length normals", normalsAreUnit(mesh))
        check("$name is consistently wound", misorientedEdgeCount(mesh) == 0,
            "${misorientedEdgeCount(mesh)} misoriented directed edge(s)")
    }

    private fun normalsAreUnit(mesh: EditMesh): Boolean {
        val n = mesh.normals ?: return false
        for (i in n.indices step 3) {
            val len = MeshMath.length(n[i], n[i + 1], n[i + 2])
            if (abs(len - 1f) > 1e-4f) return false
        }
        return true
    }

    // ================================================================= tests

    @JvmStatic
    fun main(args: Array<String>) {
        testTopology()
        testSubdivision()
        testExtrude()
        testInset()
        testDelete()
        testWeld()
        testRaycast()
        testSelection()
        testTransforms()
        testKnife()
        testLoopCut()
        testBevel()
        testRelab()
        testHistory()
        testMatricesAndCamera()
        testGltfExport()

        println("\n" + "═".repeat(58))
        if (failures.isEmpty()) {
            println("ALL GREEN — $passed checks passed")
        } else {
            println("${failures.size} FAILURE(S), $passed passed")
            failures.forEach { println("  • $it") }
        }
        println("═".repeat(58))
        if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
    }

    private fun testTopology() {
        section("Topology")
        val cube = EditMesh.cube()
        val topo = MeshTopology.of(cube)
        checkEquals("cube vertex count", 8, cube.vertexCount)
        checkEquals("cube face count", 12, cube.faceCount)
        checkEquals("cube edge count (12 cube edges + 6 face diagonals)", 18, topo.edgeCount)
        checkEquals("cube Euler characteristic (closed genus-0 surface)", 2, topo.eulerCharacteristic())
        checkEquals("closed cube has no boundary edges", 0, topo.boundaryEdgeCount())
        // A triangulated cube corner has valence 6, not 3: three cube edges plus the
        // three face diagonals that the 2-triangle-per-quad split introduces.
        check("cube vertex 0 has valence 6 (3 cube edges + 3 face diagonals)",
            topo.valence(0) == 6, "valence ${topo.valence(0)}")
        check("cube vertex 0 has 6 distinct neighbours", topo.neighborsOfVertex(0).size == 6)
        check("cube vertex 0 is not a boundary vertex", !topo.isBoundaryVertex(0))
        check("cube has no non-manifold edges", topo.nonManifoldEdges().isEmpty())
        check("topology reports a positive footprint", topo.estimatedBytes() > 0)

        val grid = EditMesh.grid(4)
        val gtopo = MeshTopology.of(grid)
        checkEquals("grid vertex count (5x5)", 25, grid.vertexCount)
        checkEquals("grid face count (16 quads)", 32, grid.faceCount)
        checkEquals("grid edge count", 56, gtopo.edgeCount)
        checkEquals("grid Euler characteristic (open disk)", 1, gtopo.eulerCharacteristic())
        checkEquals("grid boundary edges (4x4 perimeter)", 16, gtopo.boundaryEdgeCount())
        check("grid corners are boundary vertices", gtopo.isBoundaryVertex(0))

        val tri = EditMesh.singleTriangle()
        val ttopo = MeshTopology.of(tri)
        checkEquals("triangle edge count", 3, ttopo.edgeCount)
        check("every triangle edge is a boundary edge", ttopo.boundaryEdgeCount() == 3)
        check("corner lookup finds the edge", ttopo.cornerOfEdgeInFace(0, ttopo.faceEdge[0]) == 0)
    }

    private fun testSubdivision() {
        section("Subdivision")
        val cube = EditMesh.cube()
        val linear = MeshOperators.subdivide(cube, MeshOperators.Scheme.LINEAR)
        checkEquals("linear subdivide quadruples faces", 48, linear.faceCount)
        checkEquals("linear subdivide adds one vertex per edge", 26, linear.vertexCount)
        checkEquals("linear subdivide preserves Euler characteristic", 2, MeshTopology.of(linear).eulerCharacteristic())
        assertValidMesh("linear subdivided cube", linear)
        checkApprox("linear subdivision leaves corners untouched", 0.8660254f, maxRadius(linear), 1e-4f)

        val loop = MeshOperators.subdivide(cube, MeshOperators.Scheme.LOOP)
        checkEquals("loop subdivide quadruples faces", 48, loop.faceCount)
        checkEquals("loop subdivide adds one vertex per edge", 26, loop.vertexCount)
        assertValidMesh("loop subdivided cube", loop)
        val r = maxRadius(loop)
        check("loop subdivision shrinks the cube toward a sphere", r < 0.866f && r > 0.4f, "maxRadius=$r")
        // Analytic check. With 6-valence corners, Loop's beta is 1/16 and the neighbour
        // sum at a cube corner cancels to zero, so a corner lands at 0.625 * (-0.5,-0.5,-0.5).
        check("loop corners land on their analytic position",
            hasVertexNear(loop, -0.3125f, -0.3125f, -0.3125f))
        // The overall maximum is an odd (edge) vertex: 3/8(v0+v1) + 1/8(v2+v3) on a cube
        // edge, whose analytic radius is sqrt(0.015625 + 2 * 0.140625).
        checkApprox("loop edge vertices land on their analytic position", 0.5448624f, r, 1e-4f)

        val smooth = MeshOperators.subdivide(cube, MeshOperators.Scheme.LOOP, iterations = 3)
        assertValidMesh("3x loop subdivided cube", smooth)
        check("3 iterations keep the surface closed", MeshTopology.of(smooth).boundaryEdgeCount() == 0)
        check("3 iterations converge toward a sphere", maxRadius(smooth) < 0.75f, "maxRadius=${maxRadius(smooth)}")

        val grid = EditMesh.grid(4)
        val gl = MeshOperators.subdivide(grid, MeshOperators.Scheme.LINEAR)
        checkEquals("grid linear subdivide faces", 128, gl.faceCount)
        checkEquals("grid linear subdivide vertices", 81, gl.vertexCount)
        checkEquals("grid linear subdivide Euler characteristic", 1, MeshTopology.of(gl).eulerCharacteristic())

        // Area must be preserved by the linear scheme: it only splits triangles.
        checkApprox("linear subdivision preserves surface area", meshArea(grid), meshArea(gl), 1e-5f)

        val partial = MeshOperators.subdivide(grid, MeshOperators.Scheme.LINEAR, faces = setOf(0, 1))
        checkEquals("subset subdivide adds only the selected faces' children", 38, partial.faceCount)
        assertValidMesh("subset subdivided grid", partial)
    }

    private fun testExtrude() {
        section("Extrude")
        // A loose, fully selected island has no boundary to build walls along, so
        // extruding it simply moves it — the same behaviour as Blender.
        val tri = EditMesh.singleTriangle()
        val topo = MeshTopology.of(tri)
        val moved = MeshOperators.extrudeFaces(tri, topo, setOf(0), 0.5f)
        checkEquals("extruding an isolated triangle keeps its vertex count", 3, moved.vertexCount)
        checkEquals("extruding an isolated triangle keeps its face count", 1, moved.faceCount)
        checkApprox("isolated extrusion translates along the normal", 0.5f, moved.positions[1], 1e-6f)
        assertValidMesh("extruded island", moved)

        // The real prism case: a region inside a larger surface. Faces 20 and 21 are the
        // two triangles of the centre quad of a 4x4 grid.
        val grid = EditMesh.grid(4)
        val gt = MeshTopology.of(grid)
        val bumped = MeshOperators.extrudeFaces(grid, gt, setOf(20, 21), 0.5f)
        checkEquals("region extrusion duplicates only the 4 region vertices", 29, bumped.vertexCount)
        checkEquals("region extrusion adds 2 wall triangles per boundary edge", 40, bumped.faceCount)
        checkEquals("region extrusion preserves Euler characteristic", 1, MeshTopology.of(bumped).eulerCharacteristic())
        assertValidMesh("extruded grid region", bumped)
        checkApprox("region extrusion rises by the requested distance", 0.5f, maxY(bumped), 1e-6f)

        val cube = EditMesh.cube()
        val ct = MeshTopology.of(cube)
        val bump = MeshOperators.extrudeFaces(cube, ct, setOf(6, 7), 0.5f)
        checkEquals("cube top extrusion keeps all originals plus 4 copies", 12, bump.vertexCount)
        checkEquals("cube top extrusion face count", 20, bump.faceCount)
        assertValidMesh("extruded cube top", bump)
        checkApprox("extruded top rises by the requested distance", 1.0f, maxY(bump), 1e-6f)

        val spike = MeshOperators.extrudeFaces(
            cube, ct, setOf(6, 7), 0.5f, MeshOperators.ExtrudeMode.INDIVIDUAL
        )
        checkEquals("individual extrusion duplicates 3 verts per face", 14, spike.vertexCount)
        assertValidMesh("individually extruded faces", spike)

        // Extruding a whole closed mesh would be meaningless; the kernel must not crash
        // and must keep the surface closed.
        val all = MeshOperators.extrudeFaces(cube, ct, (0 until 12).toSet(), 0.25f)
        check("extruding every face still produces a valid mesh", all.validate().isEmpty())
    }

    private fun testInset() {
        section("Inset")
        val tri = EditMesh.singleTriangle()
        val before = meshArea(tri)
        val inset = MeshOperators.insetFaces(
            tri, MeshTopology.of(tri), setOf(0), 0.5f, MeshOperators.ExtrudeMode.INDIVIDUAL
        )
        checkApprox("inset 0.5 scales a triangle to a quarter of its area", before * 0.25f, meshArea(inset), 1e-6f)
        assertValidMesh("inset triangle", inset)

        val grid = EditMesh.grid(4)
        val gt = MeshTopology.of(grid)
        val region = MeshOperators.insetFaces(grid, gt, setOf(0, 1), 0.3f)
        assertValidMesh("inset grid region", region)
        check("region inset keeps the vertex count or grows it", region.vertexCount >= grid.vertexCount)
    }

    private fun testDelete() {
        section("Delete")
        val cube = EditMesh.cube()
        val holed = MeshOperators.deleteFaces(cube, setOf(6, 7))
        checkEquals("deleting the top face leaves 10 triangles", 10, holed.faceCount)
        checkEquals("all 8 cube vertices are still referenced", 8, holed.vertexCount)
        assertValidMesh("cube with its top deleted", holed)
        check("deleted top opens a boundary", MeshTopology.of(holed).boundaryEdgeCount() > 0)

        val topo = MeshTopology.of(cube)
        val edgeDeleted = MeshOperators.deleteEdges(cube, topo, setOf(topo.faceEdge[18]))
        check("deleting an edge removes its faces", edgeDeleted.faceCount < cube.faceCount)
        assertValidMesh("cube after edge delete", edgeDeleted)

        // A cube corner is shared by 3 quads, each split into 2 triangles — 6 faces.
        val vertDeleted = MeshOperators.deleteVertices(cube, setOf(0))
        checkEquals("deleting a cube corner removes its 6 faces", 6, vertDeleted.faceCount)
        assertValidMesh("cube after vertex delete", vertDeleted)

        // Orphans must not survive a compact.
        val withOrphan = EditMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 9f, 9f, 9f),
            intArrayOf(0, 1, 2)
        )
        val compacted = MeshOperators.compact(withOrphan)
        checkEquals("compact drops unreferenced vertices", 3, compacted.vertexCount)
    }

    private fun testWeld() {
        section("Weld")
        // Two triangles sharing an edge, but with that edge's vertices duplicated —
        // exactly what a UV seam or an Assimp import leaves behind.
        val split = EditMesh(
            floatArrayOf(
                0f, 0f, 0f,   // 0
                1f, 0f, 0f,   // 1
                0f, 0f, 1f,   // 2
                1f, 0f, 1f,   // 3
                1f, 0f, 0f,   // 4 duplicate of 1
                0f, 0f, 1f    // 5 duplicate of 2
            ),
            intArrayOf(0, 1, 2, 4, 3, 5)
        )
        val welded = MeshOperators.weldVertices(split, 1e-4f)
        checkEquals("weld merges the duplicate pair", 4, welded.vertexCount)
        checkEquals("weld keeps both triangles", 2, welded.faceCount)
        assertValidMesh("welded quad", welded)
        check("welded quad is topologically a disk", MeshTopology.of(welded).eulerCharacteristic() == 1)

        val cube = EditMesh.cube()
        checkEquals("welding an already-clean cube changes nothing", cube.vertexCount,
            MeshOperators.weldVertices(cube, 1e-5f).vertexCount)
    }

    private fun testRaycast() {
        section("Raycast picking")
        val grid = EditMesh.grid(4)
        val down = floatArrayOf(0f, -1f, 0f)

        val hit = MeshRaycast.raycast(grid, floatArrayOf(0f, 1f, 0f), down)
        check("ray straight down hits the grid", hit != null)
        if (hit != null) {
            checkApprox("hit distance is the camera height", 1f, hit.t, 1e-5f)
            checkApprox("hit point sits on the plane", 0f, hit.point[1], 1e-5f)
        }
        check("a ray pointing away hits nothing",
            MeshRaycast.raycast(grid, floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f)) == null)
        check("a ray off to the side misses",
            MeshRaycast.raycast(grid, floatArrayOf(10f, 1f, 0f), down) == null)

        // Vertex 12 is the grid centre at the origin.
        val pickedVertex = MeshRaycast.pickVertex(grid, floatArrayOf(0f, 1f, 0f), down)
        checkEquals("picking straight down finds the centre vertex", 12, pickedVertex)
        checkEquals("picking into empty space finds no vertex", -1,
            MeshRaycast.pickVertex(grid, floatArrayOf(10f, 1f, 0f), down))

        // Aim between the centre and its +X neighbour: the connecting edge is the closest.
        val topo = MeshTopology.of(grid)
        val pickedEdge = MeshRaycast.pickEdge(grid, topo, floatArrayOf(0.125f, 1f, 0f), down)
        check("an edge is picked between two vertices", pickedEdge >= 0)
        if (pickedEdge >= 0) {
            val pair = setOf(topo.edgeV0(pickedEdge), topo.edgeV1(pickedEdge))
            check("the picked edge joins vertices 12 and 13", pair == setOf(12, 13), "got $pair")
        }

        val cube = EditMesh.cube()
        val faceHit = MeshRaycast.pickFace(cube, floatArrayOf(0f, 0f, 4f), floatArrayOf(0f, 0f, -1f))
        check("a cube is hit from outside", faceHit >= 0)
        val culled = MeshRaycast.pickFace(
            cube, floatArrayOf(0f, 0f, 4f), floatArrayOf(0f, 0f, -1f), cullBackFaces = true
        )
        check("back-face culling still finds the front face", culled >= 0)
    }

    private fun testSelection() {
        section("Selection")
        val cube = EditMesh.cube()
        val topo = MeshTopology.of(cube)
        val sel = MeshSelection()
        sel.select(0)
        checkEquals("one face selected", 1, sel.count())

        sel.syncDerived(cube, topo)
        checkEquals("a face converts to 3 vertices", 3, sel.vertices.size)
        checkEquals("a face converts to 3 edges", 3, sel.edges.size)

        sel.switchMode(ElementMode.VERTEX, cube, topo)
        check("mode switched to vertex", sel.mode == ElementMode.VERTEX)
        checkEquals("carrying 3 vertices across the switch", 3, sel.count())

        sel.switchMode(ElementMode.EDGE, cube, topo)
        checkEquals("3 selected vertices form 3 edges", 3, sel.count())

        // Edge -> face selects every face touching a selected edge, so the three edges
        // around a cube corner pull in the corner's three adjacent faces plus the
        // original. The conversion is intentionally lossy in the same way Blender's is.
        sel.switchMode(ElementMode.FACE, cube, topo)
        checkEquals("3 selected edges select every face touching them", 4, sel.count())

        sel.selectAll(cube.faceCount)
        checkEquals("select all faces", 12, sel.count())
        sel.invert(cube.faceCount)
        checkEquals("inverting a full selection clears it", 0, sel.count())

        sel.select(0)
        sel.grow(cube, topo)
        check("grow expands the face selection", sel.count() > 1, "count=${sel.count()}")

        val regionSel = MeshSelection()
        regionSel.select(6); regionSel.select(7)
        val boundary = regionSel.regionBoundaryEdges(topo)
        checkEquals("a two-face cube corner region has 4 boundary edges", 4, boundary.size)

        check("describe() reports the active mode", sel.describe().contains("faces"))
    }

    /** Column-major 4x4 multiply, used to verify [MeshMath.invert4]. */
    private fun matMul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    private fun testMatricesAndCamera() {
        section("Matrices and camera rays")

        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val inv = FloatArray(16)
        check("identity inverts", MeshMath.invert4(identity, inv))
        check("inverse of identity is identity", inv.contentEquals(identity))

        // Filament / android.opengl layout: translation lives in elements 12..14.
        val translate = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 5f, 7f, -3f, 1f)
        check("translation inverts", MeshMath.invert4(translate, inv))
        checkApprox("inverse translation x", -5f, inv[12])
        checkApprox("inverse translation y", -7f, inv[13])
        checkApprox("inverse translation z", 3f, inv[14])

        val product = matMul(translate, inv)
        var maxError = 0f
        for (i in 0..15) maxError = maxOf(maxError, abs(product[i] - identity[i]))
        check("m * inverse(m) is the identity", maxError < 1e-5f, "max error $maxError")

        val singular = FloatArray(16) // all zeros
        check("a singular matrix reports failure", !MeshMath.invert4(singular, FloatArray(16)))

        // A world ray must arrive in local space scaled by the mesh transform, otherwise
        // picking silently misses on any non-uniformly scaled object.
        val scaled = floatArrayOf(2f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 1f)
        val local = MeshRaycast.toLocalRay(scaled, floatArrayOf(0f, 0f, 4f), floatArrayOf(0f, 0f, -1f))
        check("world ray converts to local space", local != null)
        if (local != null) {
            checkApprox("local ray origin is scaled down", 2f, local[2], 1e-5f)
            checkApprox("local ray direction stays unit length", 1f,
                MeshMath.length(local[3], local[4], local[5]), 1e-5f)
            checkApprox("local ray keeps its direction", -1f, local[5], 1e-5f)
        }

        // Screen rays: centre looks straight at the target, corners fan outwards.
        val eye = floatArrayOf(0f, 0f, 5f)
        val target = floatArrayOf(0f, 0f, 0f)
        val up = floatArrayOf(0f, 1f, 0f)
        val tanHalfFov = 0.4142f

        val centre = MeshRaycast.rayFromScreen(eye, target, up, 500f, 250f, 1000, 500, tanHalfFov)
        checkApprox("centre ray starts at the eye", 5f, centre[2], 1e-5f)
        checkApprox("centre ray points at the target", -1f, centre[5], 1e-4f)
        checkApprox("centre ray has no lateral offset", 0f, centre[3], 1e-4f)

        val topLeft = MeshRaycast.rayFromScreen(eye, target, up, 0f, 0f, 1000, 500, tanHalfFov)
        check("top-left ray goes up", topLeft[4] > 0f, "dy=${topLeft[4]}")
        check("top-left ray goes left", topLeft[3] < 0f, "dx=${topLeft[3]}")

        val bottomRight = MeshRaycast.rayFromScreen(eye, target, up, 1000f, 500f, 1000, 500, tanHalfFov)
        check("bottom-right ray goes down", bottomRight[4] < 0f)
        check("bottom-right ray goes right", bottomRight[3] > 0f)

        // A wider aspect spreads x more than y.
        val wide = MeshRaycast.rayFromScreen(eye, target, up, 1000f, 250f, 1000, 250, tanHalfFov)
        check("aspect ratio widens the horizontal spread", abs(wide[3]) > abs(centre[3]))

        // End-to-end: pick a cube face through a screen ray in local space.
        val cube = EditMesh.cube()
        val ray = MeshRaycast.rayFromScreen(
            floatArrayOf(0f, 0f, 4f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            250f, 125f, 500, 250, tanHalfFov
        )
        val origin = floatArrayOf(ray[0], ray[1], ray[2])
        val dir = floatArrayOf(ray[3], ray[4], ray[5])
        check("a screen ray hits the cube dead centre",
            MeshRaycast.pickFace(cube, origin, dir) >= 0)
    }

    /**
     * Verifies the GLB container byte-for-byte where it matters: header, chunk framing,
     * padding, and that the binary payload actually contains the mesh we exported.
     */
    private fun testGltfExport() {
        section("GLB export")
        val cube = EditMesh.cube()
        val glb = MeshGltfWriter.toGlb(cube, "TestCube")
        val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)

        checkEquals("GLB magic spells glTF", 0x46546C67, buffer.int)
        checkEquals("GLB version is 2", 2, buffer.int)
        val total = buffer.int
        checkEquals("declared length matches the actual size", glb.size, total)

        val jsonLength = buffer.int
        checkEquals("first chunk type is JSON", 0x4E4F534A, buffer.int)
        check("JSON chunk is 4-byte aligned", jsonLength % 4 == 0)
        val jsonBytes = ByteArray(jsonLength)
        buffer.get(jsonBytes)
        val json = String(jsonBytes, Charsets.UTF_8)
        check("JSON declares glTF 2.0", json.contains("\"version\":\"2.0\""))
        check("JSON exposes POSITION", json.contains("POSITION"))
        check("JSON exposes NORMAL", json.contains("NORMAL"))
        check("JSON declares the mesh name", json.contains("TestCube"))

        val binLength = buffer.int
        checkEquals("second chunk type is BIN", 0x004E4942, buffer.int)
        check("BIN chunk is 4-byte aligned", binLength % 4 == 0)
        check("BIN payload follows its chunk header", buffer.remaining() == binLength,
            "${buffer.remaining()} bytes left, expected $binLength")

        // 8 verts x 12 bytes positions + 12 bytes normals + 36 indices x 2 bytes.
        checkEquals("BIN holds positions, normals and short indices", 264, binLength)

        // POSITION bounds are mandatory in glTF and Luxe's validator checks them.
        check("POSITION accessor carries min", json.contains("\"min\":["))
        check("POSITION accessor carries max", json.contains("\"max\":["))

        // Read the geometry back out of the binary chunk and compare.
        // Skip the container header, the JSON chunk, and the BIN chunk's own 8-byte header.
        val binStart = 12 + 8 + jsonLength + 8
        val bin = ByteBuffer.wrap(glb, binStart, binLength).order(ByteOrder.LITTLE_ENDIAN)
        var maxPositionError = 0f
        for (i in cube.positions.indices) {
            maxPositionError = maxOf(maxPositionError, abs(bin.float - cube.positions[i]))
        }
        checkApprox("exported positions match the source mesh", 0f, maxPositionError, 1e-6f)

        // Skip the normals block that sits between positions and indices.
        bin.position(binStart + cube.vertexCount * 12 * 2)
        var indicesMatch = true
        for (i in cube.indices.indices) {
            if ((bin.short.toInt() and 0xFFFF) != cube.indices[i]) indicesMatch = false
        }
        check("exported indices match the source mesh", indicesMatch)

        // Above 65535 vertices the exporter must widen indices to 32-bit or they wrap.
        val wide = EditMesh(FloatArray(65536 * 3), intArrayOf(0, 1, 2))
        val cubeText = String(glb, Charsets.ISO_8859_1)
        check("small meshes use 16-bit indices", cubeText.contains("\"componentType\":5123"))
        val wideText = String(MeshGltfWriter.toGlb(wide, "Wide"), Charsets.ISO_8859_1)
        check("meshes over 65535 vertices use 32-bit indices",
            wideText.contains("\"componentType\":5125"))

        check("exporting an empty mesh is rejected",
            runCatching { MeshGltfWriter.toGlb(EditMesh.empty()) }.isFailure)
    }

    private fun testHistory() {
        section("History")
        val history = MeshHistory(limit = 8)
        val m0 = EditMesh.cube()
        check("nothing to undo initially", !history.canUndo)

        history.record("Subdivide", m0)
        val m1 = MeshOperators.subdivide(m0, MeshOperators.Scheme.LINEAR)
        check("undo becomes available after recording", history.canUndo)
        check("undo label names the operation", history.undoLabel == "Undo Subdivide",
            "got '${history.undoLabel}'")

        val undone = history.undo(m1)
        check("undo returns a state", undone != null)
        if (undone != null) checkEquals("undo restores the original face count", 12, undone.mesh.faceCount)
        check("redo becomes available", history.canRedo)

        val redone = history.redo(undone!!.mesh)
        check("redo returns a state", redone != null)
        if (redone != null) checkEquals("redo restores the subdivided face count", 48, redone.mesh.faceCount)

        check("history tracks retained bytes", history.memoryBytes() > 0)

        // A fresh edit must invalidate the redo branch.
        history.record("Extrude", m1)
        check("recording clears the redo stack", !history.canRedo)

        // The limit must bound retained memory.
        val bounded = MeshHistory(limit = 3)
        repeat(10) { bounded.record("Op $it", EditMesh.cube()) }
        checkEquals("history respects its depth limit", 3, bounded.depth)

        // Snapshot independence: editing the live mesh must not corrupt history.
        val live = EditMesh.cube()
        val h2 = MeshHistory()
        h2.record("Edit", live)
        live.setVertex(0, 99f, 99f, 99f)
        val restored = h2.undo(live)
        checkApprox("history snapshots are independent of later edits", -0.5f, restored!!.mesh.positions[0], 1e-6f)
    }

    // ------------------------------------------------------- modelling tools

    private fun testTransforms() {
        section("Transforms")
        val cube = EditMesh.cube()
        val all = (0 until cube.vertexCount).toSet()
        val origin = floatArrayOf(0f, 0f, 0f)

        val moved = MeshOperators.translateVertices(cube, all, 1f, 0f, 0f)
        checkApprox("translate shifts x", 0.5f, moved.positions[0])
        checkApprox("translate leaves y alone", -0.5f, moved.positions[1])
        checkEquals("translate keeps the face count", 12, moved.faceCount)
        assertValidMesh("translated cube", moved)

        val uniform = MeshOperators.scaleVerticesUniform(cube, all, 2f, origin)
        checkApprox("uniform scale doubles the radius", sqrt(3f), maxRadius(uniform), 1e-5f)
        checkApprox("uniform scale keeps x and y in step", 1f, uniform.positions[3], 1e-5f)
        checkApprox("uniform scale keeps y and z in step", 1f, uniform.positions[6], 1e-5f)
        assertValidMesh("uniformly scaled cube", uniform)

        val perAxis = MeshOperators.scaleVertices(cube, all, 2f, 1f, 1f, origin)
        checkApprox("per-axis scale stretches x", 1f, perAxis.positions[3], 1e-5f)
        checkApprox("per-axis scale leaves y alone", -0.5f, perAxis.positions[4], 1e-5f)
        checkApprox("per-axis scale leaves z alone", -0.5f, perAxis.positions[5], 1e-5f)
        assertValidMesh("per-axis scaled cube", perAxis)

        // Offsetting the pivot must translate the result, not just resize it.
        val offPivot = MeshOperators.scaleVerticesUniform(cube, all, 2f, floatArrayOf(1f, 0f, 0f))
        // x' = pivot + (x - pivot) * s  ->  1 + (-0.5 - 1) * 2 = -2
        checkApprox("scaling about an offset pivot shifts the result", -2f, offPivot.positions[0], 1e-5f)

        // A quarter turn about +Y maps (-.5,-.5,-.5) to (-.5,-.5,.5) and is rigid.
        val rotated = MeshOperators.rotateVertices(cube, all, 0f, 1f, 0f, (Math.PI / 2).toFloat(), origin)
        checkApprox("rotate maps x", -0.5f, rotated.positions[0], 1e-5f)
        checkApprox("rotate preserves the axis component", -0.5f, rotated.positions[1], 1e-5f)
        checkApprox("rotate maps z", 0.5f, rotated.positions[2], 1e-5f)
        checkApprox("rotation is rigid", sqrt(3f) * 0.5f, maxRadius(rotated), 1e-5f)
        assertValidMesh("rotated cube", rotated)
        val spun = MeshOperators.rotateVertices(rotated, all, 0f, 1f, 0f, (Math.PI / 2).toFloat(), origin)
        checkApprox("two quarter turns is a half turn", 0.5f, spun.positions[0], 1e-5f)

        checkApprox("centroid of a single vertex is that vertex", -0.5f,
            MeshOperators.selectionCentroid(cube, setOf(0))[0])
        val centre = MeshOperators.selectionCentroid(cube, all)
        checkApprox("cube centroid sits at the origin", 0f, centre[0], 1e-6f)
        checkApprox("cube centroid sits at the origin (z)", 0f, centre[2], 1e-6f)

        // An empty selection must be a no-op rather than a crash.
        checkEquals("translating nothing changes nothing", 12,
            MeshOperators.translateVertices(cube, emptySet(), 5f, 5f, 5f).faceCount)
    }

    private fun testKnife() {
        section("Knife")
        val cube = EditMesh.cube()

        // A plane through the middle of the cube must slice it, not break it.
        val sliced = MeshOperators.cutWithPlane(cube, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        check("knife splits faces", sliced.faceCount > 12, "got ${sliced.faceCount}")
        assertValidMesh("knife-cut cube", sliced)
        val slicedTopo = MeshTopology.of(sliced)
        checkEquals("knife keeps the shell closed", 2, slicedTopo.eulerCharacteristic())
        checkEquals("knife leaves no boundary", 0, slicedTopo.boundaryEdgeCount())
        check("knife adds vertices along the cut", sliced.vertexCount > 8, "got ${sliced.vertexCount}")
        check("cut vertices sit on the plane", run {
            var worst = 0f
            for (v in 8 until sliced.vertexCount) {
                worst = maxOf(worst, abs(sliced.positions[v * 3 + 1]))
            }
            worst < 1e-5f
        })
        check("the two halves keep the original volume", run {
            val before = meshArea(cube)
            val after = meshArea(sliced)
            abs(before - after) < 1e-4f
        })

        // A plane that misses, and a plane coplanar with the surface, must do nothing.
        checkEquals("a plane that misses the mesh is a no-op", 12,
            MeshOperators.cutWithPlane(cube, floatArrayOf(0f, 5f, 0f), floatArrayOf(0f, 1f, 0f)).faceCount)
        checkEquals("a coplanar plane is a no-op", 1,
            MeshOperators.cutWithPlane(EditMesh.singleTriangle(), floatArrayOf(0f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f)).faceCount)

        // Cutting twice must stack: each pass adds its own ring of vertices.
        val cutX = MeshOperators.cutWithPlane(cube, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f))
        val cutXY = MeshOperators.cutWithPlane(cutX, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
        assertValidMesh("cube cut on two planes", cutXY)
        checkEquals("two cuts still leave a closed shell", 0, MeshTopology.of(cutXY).boundaryEdgeCount())

        checkEquals("an invalid knife plane is rejected gracefully", 12,
            MeshOperators.cutWithPlane(cube, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f)).faceCount)
    }

    private fun testLoopCut() {
        section("Loop cut")
        val cube = EditMesh.cube()
        val cubeTopo = MeshTopology.of(cube)
        // Topology edge 2 is the real cube edge (0,1); edge 0 is a face diagonal.
        val edge = 2

        val cut = MeshOperators.loopCut(cube, cubeTopo, edge, 0.5f)
        check("loop cut splits the strip's faces", cut.mesh.faceCount > 12, "got ${cut.mesh.faceCount}")
        check("loop cut adds one vertex per crossed edge", cut.mesh.vertexCount > 8,
            "got ${cut.mesh.vertexCount}")
        assertValidMesh("loop-cut cube", cut.mesh)
        val cutTopo = MeshTopology.of(cut.mesh)
        checkEquals("loop cut keeps the cube closed", 2, cutTopo.eulerCharacteristic())
        checkEquals("loop cut leaves no boundary", 0, cutTopo.boundaryEdgeCount())

        // The headline case: on a regular triangulated grid the loop must run dead straight.
        val grid = EditMesh.grid(4)
        val gridTopo = MeshTopology.of(grid)
        val gridEdge = (0 until gridTopo.edgeCount).first {
            (gridTopo.edgeV0(it) == 0 && gridTopo.edgeV1(it) == 1) ||
            (gridTopo.edgeV0(it) == 1 && gridTopo.edgeV1(it) == 0)
        }
        val straight = MeshOperators.loopCut(grid, gridTopo, gridEdge, 0.5f).mesh
        assertValidMesh("loop-cut grid", straight)
        check("a loop cut on a grid is straight", run {
            var worst = 0f
            for (v in grid.vertexCount until straight.vertexCount) {
                worst = maxOf(worst, abs(straight.positions[v * 3] - (-0.375f)))
            }
            worst < 1e-5f
        })
        check("a loop cut spans the strip rather than stopping short", run {
            var lowest = Float.MAX_VALUE
            var highest = -Float.MAX_VALUE
            for (v in grid.vertexCount until straight.vertexCount) {
                lowest = minOf(lowest, straight.positions[v * 3 + 2])
                highest = maxOf(highest, straight.positions[v * 3 + 2])
            }
            lowest < -0.4f && highest > 0.4f
        })
        // Cutting an open surface must leave its outline alone: the two ends of the strip
        // split a boundary edge each, which is a subdivision, not a new hole.
        checkApprox("a loop cut leaves the outline the same length",
            boundaryLength(grid), boundaryLength(straight), 1e-5f)

        // Multi-cut: the two-finger scroll. It has to work on a closed mesh too, where
        // re-walking the strip for a second pass would stop short of closing and crack it.
        val many = MeshOperators.loopCuts(cube, cubeTopo, edge, 0.5f, 3)
        assertValidMesh("cube after three loop cuts", many)
        check("repeated loop cuts keep adding geometry", many.faceCount > cut.mesh.faceCount,
            "${many.faceCount} vs ${cut.mesh.faceCount}")
        checkEquals("repeated loop cuts keep the cube closed", 0,
            MeshTopology.of(many).boundaryEdgeCount())
        checkEquals("repeated loop cuts preserve the Euler characteristic", 2,
            MeshTopology.of(many).eulerCharacteristic())
        check("three loops add three times the vertices of one",
            many.vertexCount == 8 + 3 * (cut.mesh.vertexCount - 8), "got ${many.vertexCount}")

        check("an out-of-range edge is rejected gracefully",
            MeshOperators.loopCut(cube, cubeTopo, 9999, 0.5f).mesh.faceCount == 12)
    }

    private fun testBevel() {
        section("Bevel")
        val cube = EditMesh.cube()
        val topo = MeshTopology.of(cube)
        val edge = 2
        val cubeEuler = topo.eulerCharacteristic()

        val one = MeshOperators.bevelEdges(cube, topo, setOf(edge), segments = 1, width = 0.25f)
        check("bevel adds geometry", one.faceCount > 12, "got ${one.faceCount}")
        assertValidMesh("cube with one beveled edge", one)
        checkEquals("beveling an edge keeps the shell closed", 0,
            MeshTopology.of(one).boundaryEdgeCount())
        checkEquals("beveling an edge preserves the Euler characteristic", cubeEuler,
            MeshTopology.of(one).eulerCharacteristic())

        // More segments must produce more faces along the same edge.
        val three = MeshOperators.bevelEdges(cube, topo, setOf(edge), segments = 3, width = 0.25f)
        check("more segments means more faces", three.faceCount > one.faceCount,
            "${three.faceCount} vs ${one.faceCount}")
        assertValidMesh("cube with a three-segment bevel", three)
        checkEquals("a three-segment bevel stays closed", 0,
            MeshTopology.of(three).boundaryEdgeCount())

        // Two edges meeting at a corner: the caps have to share it without a crack.
        val corner = MeshOperators.bevelEdges(cube, topo, setOf(edge, 3), segments = 1, width = 0.2f)
        assertValidMesh("cube with two beveled edges meeting at a corner", corner)
        checkEquals("a corner bevel stays closed", 0, MeshTopology.of(corner).boundaryEdgeCount())
        checkEquals("a corner bevel preserves the Euler characteristic", cubeEuler,
            MeshTopology.of(corner).eulerCharacteristic())

        // Beveling every real edge at once is the rounded-cube case.
        val every = MeshOperators.bevelEdges(cube, topo, realEdges(cube, topo), segments = 2, width = 0.2f)
        assertValidMesh("cube with every edge beveled", every)
        checkEquals("an all-edge bevel stays closed", 0, MeshTopology.of(every).boundaryEdgeCount())
        checkEquals("an all-edge bevel preserves the Euler characteristic", cubeEuler,
            MeshTopology.of(every).eulerCharacteristic())

        // A bevel cuts material away, so it can never grow the silhouette.
        check("a bevel never grows the mesh", maxRadius(every) <= maxRadius(cube) + 1e-5f,
            "${maxRadius(every)} vs ${maxRadius(cube)}")
        check("a single bevel never grows the mesh either", maxRadius(one) <= maxRadius(cube) + 1e-5f,
            "${maxRadius(one)} vs ${maxRadius(cube)}")

        // Surface diagonals are skipped rather than collapsed to zero width.
        checkEquals("beveling a face diagonal is a no-op", 12,
            MeshOperators.bevelEdges(cube, topo, setOf(0)).faceCount)
        checkEquals("beveling nothing is a no-op", 12,
            MeshOperators.bevelEdges(cube, topo, emptySet()).faceCount)
        checkEquals("an out-of-range edge is ignored", 12,
            MeshOperators.bevelEdges(cube, topo, setOf(9999)).faceCount)
    }

    private fun testRelab() {
        section("Mesh relab")
        val grid = EditMesh.grid(4)
        val topo = MeshTopology.of(grid)
        // The two triangles of one quad: the smallest region that has an inside.
        val quad = setOf(0, 1)

        val oneRing = MeshOperators.insertRings(grid, topo, quad, 1)
        check("relab adds geometry to the region", oneRing.faceCount > grid.faceCount,
            "got ${oneRing.faceCount}")
        assertValidMesh("grid after one relab ring", oneRing)

        val threeRings = MeshOperators.insertRings(grid, topo, quad, 3)
        check("more rings means more faces", threeRings.faceCount > oneRing.faceCount,
            "${threeRings.faceCount} vs ${oneRing.faceCount}")
        assertValidMesh("grid after three relab rings", threeRings)

        // Relab adds cuts inside the region; it must not open up the surrounding surface.
        checkApprox("relab leaves the outline the same length",
            boundaryLength(grid), boundaryLength(threeRings), 1e-5f)
        check("relab keeps every new vertex inside the original region", run {
            var worst = 0f
            for (v in grid.vertexCount until threeRings.vertexCount) {
                worst = maxOf(worst, maxRadiusOf(threeRings, v))
            }
            worst <= 0.5f * sqrt(2f) + 1e-5f
        })

        checkEquals("relabbing nothing is a no-op", grid.faceCount,
            MeshOperators.insertRings(grid, topo, emptySet(), 2).faceCount)
        checkEquals("zero rings is a no-op", grid.faceCount,
            MeshOperators.insertRings(grid, topo, quad, 0).faceCount)
    }

    /** Edges that are not triangulation diagonals — the ones a bevel can actually cut. */
    private fun realEdges(mesh: EditMesh, topo: MeshTopology): Set<Int> {
        val a = FloatArray(3)
        val b = FloatArray(3)
        val out = LinkedHashSet<Int>()
        for (e in 0 until topo.edgeCount) {
            val faces = topo.facesOfEdge(e)
            if (faces.size != 2) continue
            mesh.faceNormal(faces[0], a)
            mesh.faceNormal(faces[1], b)
            if (MeshMath.dot(a[0], a[1], a[2], b[0], b[1], b[2]) < 0.999f) out += e
        }
        return out
    }

    private fun maxRadiusOf(mesh: EditMesh, vertex: Int): Float = MeshMath.length(
        mesh.positions[vertex * 3], mesh.positions[vertex * 3 + 1], mesh.positions[vertex * 3 + 2]
    )
}
