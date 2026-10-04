package luxe.texture3d.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The geometry behind the transform gizmo, kept free of Android and Filament imports.
 *
 * This is the part that is easy to get subtly wrong and impossible to eyeball: which handle
 * a tap landed on, how far along an axis a drag has moved, how many degrees a ring has been
 * turned. Keeping it pure means it can be exercised on a plain JVM by the verification
 * harness, which is the only honest way to check it.
 *
 * ## Conventions
 *
 * - A [Ray] carries a normalised direction.
 * - An axis is described by a point (the gizmo's centre) and a **unit** direction.
 * - [axisParameter] returns a signed distance along that direction, so a drag's effect is
 *   simply the difference between two of them. That is what makes the gizmo behave the same
 *   however the camera is orbiting.
 */
object GizmoMath {

    enum class Mode { MOVE, ROTATE, SCALE }

    /** The grabbable parts. UNIFORM scales all three axes; SCREEN moves in the view plane. */
    enum class Axis { X, Y, Z, UNIFORM, SCREEN }

    /** Colour of each handle, in the Blender order: X red, Y green, Z blue. */
    fun axisColor(axis: Axis): FloatArray = when (axis) {
        Axis.X -> floatArrayOf(0.95f, 0.22f, 0.22f)
        Axis.Y -> floatArrayOf(0.30f, 0.85f, 0.32f)
        Axis.Z -> floatArrayOf(0.28f, 0.48f, 0.98f)
        Axis.UNIFORM -> floatArrayOf(0.85f, 0.85f, 0.90f)
        Axis.SCREEN -> floatArrayOf(0.85f, 0.85f, 0.90f)
    }

    /** A world-space ray with a unit direction. */
    class Ray(
        val ox: Float, val oy: Float, val oz: Float,
        dx: Float, dy: Float, dz: Float
    ) {
        val dx: Float
        val dy: Float
        val dz: Float

        init {
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-9f) {
                this.dx = 0f; this.dy = 0f; this.dz = -1f
            } else {
                this.dx = dx / len; this.dy = dy / len; this.dz = dz / len
            }
        }
    }

    // ------------------------------------------------------------------ picking

    /**
     * Shortest distance from [ray] to the segment `a` -> `b`.
     *
     * Used for hit-testing the handles: a tap that passes within a small world distance of
     * an axis shaft counts as grabbing that axis. Working in world space rather than screen
     * space keeps the tolerance meaningful when the camera is close to or far from the
     * gizmo.
     */
    fun rayToSegment(
        ray: Ray,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float
    ): Float {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val lu2 = ux * ux + uy * uy + uz * uz
        if (lu2 < 1e-18f) return pointToRayDistance(ray, ax, ay, az)
        // Parameter of the closest point on the segment, before clamping.
        val wx = ax - ray.ox; val wy = ay - ray.oy; val wz = az - ray.oz
        val a = lu2
        val b = ux * ray.dx + uy * ray.dy + uz * ray.dz
        val d = ux * wx + uy * wy + uz * wz
        val e = ray.dx * wx + ray.dy * wy + ray.dz * wz
        var s = if (abs(a) < 1e-12f) 0f else -d / a
        // Re-solve properly for the ray being infinite in one direction.
        val denom = a * 1f - b * b
        if (abs(denom) > 1e-12f) s = (b * e - d) / denom
        s = s.coerceIn(0f, 1f)
        return pointToRayDistance(ray, ax + ux * s, ay + uy * s, az + uz * s)
    }

    /** Distance from a point to a ray, treating the ray as starting at its origin. */
    fun pointToRayDistance(ray: Ray, px: Float, py: Float, pz: Float): Float {
        val vx = px - ray.ox; val vy = py - ray.oy; val vz = pz - ray.oz
        val t = max(0f, vx * ray.dx + vy * ray.dy + vz * ray.dz)
        val cx = vx - ray.dx * t
        val cy = vy - ray.dy * t
        val cz = vz - ray.dz * t
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    /**
     * Which handle [ray] grabbed, or null when it missed them all — in which case the drag
     * belongs to the camera.
     *
     * [axes] is the three unit axis directions and [length] the handle length, so the same
     * code serves a rotated object.
     */
    fun pickAxis(
        ray: Ray,
        cx: Float, cy: Float, cz: Float,
        axes: Array<FloatArray>,
        length: Float,
        tolerance: Float
    ): Axis? {
        val order = arrayOf(Axis.X, Axis.Y, Axis.Z)
        var best = Axis.X
        var bestDistance = Float.MAX_VALUE
        for (i in 0..2) {
            val a = axes[i]
            val distance = rayToSegment(
                ray, cx, cy, cz,
                cx + a[0] * length, cy + a[1] * length, cz + a[2] * length
            )
            if (distance < bestDistance) {
                bestDistance = distance
                best = order[i]
            }
        }
        return if (bestDistance <= tolerance) best else null
    }

    /**
     * Which rotation ring [ray] grabbed, by testing each ring's circle in turn.
     *
     * A ring is sampled rather than solved analytically: the handles are small on screen, so
     * a few dozen samples are both accurate enough and far easier to verify than a
     * ray/torus intersection.
     */
    fun pickRing(
        ray: Ray,
        cx: Float, cy: Float, cz: Float,
        axes: Array<FloatArray>,
        radius: Float,
        tolerance: Float,
        samples: Int = 48
    ): Axis? {
        val order = arrayOf(Axis.X, Axis.Y, Axis.Z)
        var best = Axis.X
        var bestDistance = Float.MAX_VALUE
        for (ring in 0..2) {
            // Ring `ring` lies in the plane spanned by the other two axes.
            val u = axes[(ring + 1) % 3]
            val v = axes[(ring + 2) % 3]
            for (s in 0 until samples) {
                val a0 = (s.toFloat() / samples) * TWO_PI
                val a1 = ((s + 1).toFloat() / samples) * TWO_PI
                val d0 = rayToSegment(
                    ray,
                    cx + (u[0] * cos(a0) + v[0] * sin(a0)) * radius,
                    cy + (u[1] * cos(a0) + v[1] * sin(a0)) * radius,
                    cz + (u[2] * cos(a0) + v[2] * sin(a0)) * radius,
                    cx + (u[0] * cos(a1) + v[0] * sin(a1)) * radius,
                    cy + (u[1] * cos(a1) + v[1] * sin(a1)) * radius,
                    cz + (u[2] * cos(a1) + v[2] * sin(a1)) * radius
                )
                if (d0 < bestDistance) {
                    bestDistance = d0
                    best = order[ring]
                }
            }
        }
        return if (bestDistance <= tolerance) best else null
    }

    // ------------------------------------------------------------------- dragging

    /**
     * Signed distance along [axisDir] at which [ray] passes closest to the axis line.
     *
     * This is the heart of the move gizmo: take it once when the finger goes down and again
     * on every move, and the difference is exactly how far the object should slide — in
     * world units, regardless of how the camera is orbiting or how far away it is.
     */
    fun axisParameter(
        ray: Ray,
        cx: Float, cy: Float, cz: Float,
        axisDir: FloatArray
    ): Float {
        val ax = axisDir[0]; val ay = axisDir[1]; val az = axisDir[2]
        val wx = cx - ray.ox; val wy = cy - ray.oy; val wz = cz - ray.oz
        val b = ax * ray.dx + ay * ray.dy + az * ray.dz
        val d = ax * wx + ay * wy + az * wz
        val e = ray.dx * wx + ray.dy * wy + ray.dz * wz
        val denom = 1f - b * b
        // Looking straight down the axis: there is no meaningful answer, so hold still
        // rather than letting a near-zero denominator fling the object across the scene.
        if (abs(denom) < 1e-4f) return 0f
        return (b * e - d) / denom
    }

    /** How far a drag moved along an axis: the gizmo's translation delta, in world units. */
    fun moveDelta(
        center: FloatArray, axisDir: FloatArray, from: Ray, to: Ray
    ): Float = axisParameter(to, center[0], center[1], center[2], axisDir) -
        axisParameter(from, center[0], center[1], center[2], axisDir)

    /**
     * Where [ray] meets the plane through [center] whose normal is [axisDir].
     *
     * Returns false when the ray is parallel to the plane. Writes the hit point into [out].
     */
    fun planeHit(
        ray: Ray,
        cx: Float, cy: Float, cz: Float,
        axisDir: FloatArray,
        out: FloatArray
    ): Boolean {
        val denom = ray.dx * axisDir[0] + ray.dy * axisDir[1] + ray.dz * axisDir[2]
        if (abs(denom) < 1e-6f) return false
        val t = ((cx - ray.ox) * axisDir[0] + (cy - ray.oy) * axisDir[1] + (cz - ray.oz) * axisDir[2]) / denom
        out[0] = ray.ox + ray.dx * t
        out[1] = ray.oy + ray.dy * t
        out[2] = ray.oz + ray.dz * t
        return true
    }

    /**
     * Signed angle, in radians, a drag turned around [axisDir].
     *
     * Both rays are intersected with the ring's plane and the angle between the two radii is
     * measured about the axis, so dragging around the ring — not across it — is what turns
     * the object.
     */
    fun rotateAngle(
        center: FloatArray, axisDir: FloatArray, from: Ray, to: Ray
    ): Float {
        val a = FloatArray(3)
        val b = FloatArray(3)
        if (!planeHit(from, center[0], center[1], center[2], axisDir, a)) return 0f
        if (!planeHit(to, center[0], center[1], center[2], axisDir, b)) return 0f
        val ax = a[0] - center[0]; val ay = a[1] - center[1]; val az = a[2] - center[2]
        val bx = b[0] - center[0]; val by = b[1] - center[1]; val bz = b[2] - center[2]
        val la = sqrt(ax * ax + ay * ay + az * az)
        val lb = sqrt(bx * bx + by * by + bz * bz)
        if (la < 1e-6f || lb < 1e-6f) return 0f
        val nax = ax / la; val nay = ay / la; val naz = az / la
        val nbx = bx / lb; val nby = by / lb; val nbz = bz / lb
        val dot = (nax * nbx + nay * nby + naz * nbz).coerceIn(-1f, 1f)
        val crossX = nay * nbz - naz * nby
        val crossY = naz * nbx - nax * nbz
        val crossZ = nax * nby - nay * nbx
        val sign = crossX * axisDir[0] + crossY * axisDir[1] + crossZ * axisDir[2]
        return atan2(sign, dot)
    }

    /**
     * How much a drag scaled: the ratio of the current radius to the radius when the drag
     * began.
     *
     * Measured from the gizmo's centre, so dragging outward grows the object and dragging
     * inward shrinks it, which is the behaviour that reads naturally on a touch screen.
     */
    fun scaleFactor(
        center: FloatArray, axisDir: FloatArray, from: Ray, to: Ray
    ): Float {
        val a = FloatArray(3)
        val b = FloatArray(3)
        if (!planeHit(from, center[0], center[1], center[2], axisDir, a)) return 1f
        if (!planeHit(to, center[0], center[1], center[2], axisDir, b)) return 1f
        val da = distance(a, center)
        val db = distance(b, center)
        // Guarding the denominator keeps a drag that passes over the centre from exploding.
        if (da < 1e-5f) return 1f
        return (db / da).coerceIn(0.02f, 50f)
    }

    // ------------------------------------------------------- screen to world

    /**
     * Undo a projection: turn a point in clip space back into world space.
     *
     * [invVP] is the inverse of the view-projection matrix in Filament's column-major
     * layout, `m[col * 4 + row]`. The perspective divide is what turns the homogeneous
     * result back into a position.
     */
    fun unproject(ndcX: Float, ndcY: Float, ndcZ: Float, invVP: FloatArray, out: FloatArray) {
        val w = invVP[3] * ndcX + invVP[7] * ndcY + invVP[11] * ndcZ + invVP[15]
        val iw = if (w == 0f) 1f else 1f / w
        out[0] = (invVP[0] * ndcX + invVP[4] * ndcY + invVP[8] * ndcZ + invVP[12]) * iw
        out[1] = (invVP[1] * ndcX + invVP[5] * ndcY + invVP[9] * ndcZ + invVP[13]) * iw
        out[2] = (invVP[2] * ndcX + invVP[6] * ndcY + invVP[10] * ndcZ + invVP[14]) * iw
    }

    /**
     * The world-space ray leaving the camera under a screen position.
     *
     * [ndcX]/[ndcY] run -1..1 across the viewport, y pointing up. The ray is built by
     * unprojecting the same pixel at the near and far planes and joining them, which is
     * what makes every gizmo hit-test work from the camera's point of view rather than
     * from the object's.
     */
    fun rayFromScreen(ndcX: Float, ndcY: Float, invVP: FloatArray): Ray {
        val near = FloatArray(3)
        val far = FloatArray(3)
        unproject(ndcX, ndcY, -1f, invVP, near)
        unproject(ndcX, ndcY, 1f, invVP, far)
        return Ray(near[0], near[1], near[2], far[0] - near[0], far[1] - near[1], far[2] - near[2])
    }

    /**
     * World size that spans [pixels] on screen at [distance].
     *
     * The gizmo has to stay a comfortable size whether the camera is right on top of the
     * object or across the scene, so its geometry is rebuilt at this scale each frame rather
     * than being fixed in world units.
     */
    fun worldSizeForPixels(
        pixels: Float, distance: Float, tanHalfFovY: Float, viewportHeight: Int
    ): Float {
        val height = viewportHeight.coerceAtLeast(1)
        return 2f * tanHalfFovY * distance * (pixels / height)
    }

    // ---------------------------------------------------------------- matrices

    /**
     * The three world-space axes an object's rotation points along.
     *
     * The gizmo has to follow the object it is editing, so its handles are the object's own
     * X, Y and Z — not the world's. Returned as three unit vectors, which is the form
     * [pickAxis], [moveDelta] and friends all take.
     */
    fun quaternionToBasis(q: FloatArray): Array<FloatArray> {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        return arrayOf(
            floatArrayOf(1f - 2f * (y * y + z * z), 2f * (x * y + w * z), 2f * (x * z - w * y)),
            floatArrayOf(2f * (x * y - w * z), 1f - 2f * (x * x + z * z), 2f * (y * z + w * x)),
            floatArrayOf(2f * (x * z + w * y), 2f * (y * z - w * x), 1f - 2f * (x * x + y * y))
        )
    }

    /** Column-major 4x4 multiply: `a * b`. */
    fun multiply4x4(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) for (row in 0..3) {
            var sum = 0f
            for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
        return out
    }

    /**
     * Gauss-Jordan inverse of a column-major 4x4 matrix.
     *
     * Needed because the camera hands us a view-projection matrix and picking needs to go
     * the other way, from a pixel to a ray. Partial pivoting keeps it stable for the
     * badly-scaled matrices a distant camera produces.
     */
    fun invert4x4(m: FloatArray): FloatArray {
        val a = m.copyOf()
        val inv = FloatArray(16)
        for (i in 0..3) inv[i * 4 + i] = 1f
        for (i in 0..3) {
            var pivot = i
            for (r in i + 1..3) {
                if (abs(a[i * 4 + r]) > abs(a[i * 4 + pivot])) pivot = r
            }
            if (pivot != i) {
                for (c in 0..3) {
                    val t = a[c * 4 + i]; a[c * 4 + i] = a[c * 4 + pivot]; a[c * 4 + pivot] = t
                    val u = inv[c * 4 + i]; inv[c * 4 + i] = inv[c * 4 + pivot]; inv[c * 4 + pivot] = u
                }
            }
            val d = a[i * 4 + i]
            if (d == 0f) return FloatArray(16).also { for (k in 0..3) it[k * 4 + k] = 1f }
            for (c in 0..3) { a[c * 4 + i] /= d; inv[c * 4 + i] /= d }
            for (r in 0..3) {
                if (r == i) continue
                val f = a[i * 4 + r]
                for (c in 0..3) {
                    a[c * 4 + r] -= f * a[c * 4 + i]
                    inv[c * 4 + r] -= f * inv[c * 4 + i]
                }
            }
        }
        return inv
    }

    /** Widens Filament's double-precision camera matrices into the floats used throughout. */
    fun toFloat16(m: DoubleArray): FloatArray = FloatArray(16) { m[it].toFloat() }

    /**
     * The world-space ray under a screen position, straight from the camera's matrices.
     *
     * [ndcX]/[ndcY] run -1..1 with y up. This is the only entry point the activity needs:
     * hand it `camera.projectionMatrix` and `camera.viewMatrix` and it does the rest.
     */
    fun rayFromCameraMatrices(
        ndcX: Float, ndcY: Float, projection: FloatArray, view: FloatArray
    ): Ray {
        val invVP = invert4x4(multiply4x4(projection, view))
        return rayFromScreen(ndcX, ndcY, invVP)
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    private fun cos(angle: Float): Float = kotlin.math.cos(angle.toDouble()).toFloat()

    private fun sin(angle: Float): Float = kotlin.math.sin(angle.toDouble()).toFloat()

    private const val TWO_PI = 6.2831855f
}
