package luxe.texture3d.verification

import luxe.texture3d.app.*
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
        testHistory()
        testMatricesAndCamera()

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
}
