package luxe.texture3d.app

import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class ModelOrbitController {
    var yaw: Float = 0f
        private set
    var pitch: Float = 0f
        private set
    var zoom: Float = 1f
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var lastPinch = 0f
    private var lastPointerCount = 0

    fun reset() {
        yaw = 0f
        pitch = 0f
        zoom = 1f
        lastPinch = 0f
        lastPointerCount = 0
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.getX(0)
                lastY = event.getY(0)
                lastPointerCount = 1
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                lastPointerCount = event.pointerCount
                lastPinch = pinchDistance(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY

                    yaw += dx * 0.008f
                    pitch += dy * 0.008f
                    val limit = PI.toFloat() * 0.48f
                    pitch = pitch.coerceIn(-limit, limit)

                    lastX = x
                    lastY = y
                } else if (event.pointerCount >= 2) {
                    val p = pinchDistance(event)
                    if (lastPointerCount >= 2 && lastPinch > 1f && p > 1f) {
                        val factor = (p / lastPinch).coerceIn(0.85f, 1.18f)
                        zoom = (zoom * factor).coerceIn(0.25f, 5.0f)
                    }
                    lastPinch = p
                }
                lastPointerCount = event.pointerCount
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastPointerCount = event.pointerCount - 1
                lastPinch = 0f
                if (lastPointerCount == 1) {
                    val keep = if (event.actionIndex == 0) 1 else 0
                    if (keep < event.pointerCount) {
                        lastX = event.getX(keep)
                        lastY = event.getY(keep)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPointerCount = 0
                lastPinch = 0f
                return true
            }
        }
        return true
    }

    fun transformMatrix(): FloatArray {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val s = zoom

        // Column-major matrix. Rotation around model center only. No translation.
        return floatArrayOf(
            cy * s,        0f,      -sy * s,       0f,
            sy * sp * s,   cp * s,   cy * sp * s,  0f,
            sy * cp * s,  -sp * s,   cy * cp * s,  0f,
            0f,            0f,       0f,           1f
        )
    }

    private fun pinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}
