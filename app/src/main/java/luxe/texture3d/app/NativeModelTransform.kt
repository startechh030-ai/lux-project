package luxe.texture3d.app

import kotlin.math.cos
import kotlin.math.sin

object NativeModelTransform {
    /**
     * Converts native camera orbit state into a visible model transform.
     * This is intentional while we still use Filament ModelViewer, because ModelViewer
     * can overwrite camera matrices internally during render().
     */
    fun fromCameraState(c: FloatArray): FloatArray {
        val tx = c[3]
        val ty = c[4]
        val tz = c[5]
        val yaw = c[9]
        val pitch = c[10]
        val distance = c[11].coerceIn(0.45f, 80f)

        // Smaller distance = bigger model, bigger distance = smaller model.
        val s = (3.2f / distance).coerceIn(0.05f, 5.0f)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)

        // Column-major matrix: T * Ry * Rx * S
        return floatArrayOf(
            cy * s,        0f,      -sy * s,       0f,
            sy * sp * s,   cp * s,   cy * sp * s,  0f,
            sy * cp * s,  -sp * s,   cy * cp * s,  0f,
            tx,            ty,       tz,           1f
        )
    }

    fun multiplyColumnMajor(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                out[col * 4 + row] =
                    a[0 * 4 + row] * b[col * 4 + 0] +
                    a[1 * 4 + row] * b[col * 4 + 1] +
                    a[2 * 4 + row] * b[col * 4 + 2] +
                    a[3 * 4 + row] * b[col * 4 + 3]
            }
        }
        return out
    }
}
