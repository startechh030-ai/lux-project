package luxe.texture3d.app

import android.view.MotionEvent
import com.google.android.filament.Camera
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class CameraController {
    var yaw = 0f
        private set
    var pitch = 0f
        private set
    var distance = 3.0f
        private set

    private var targetX = 0f
    private var targetY = 0f
    private var targetZ = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastPinchDistance = 0f
    private var lastPointerCount = 0

    fun reset() {
        yaw = 0f
        pitch = 0f
        distance = 3.0f
        targetX = 0f
        targetY = 0f
        targetZ = 0f
        lastPointerCount = 0
    }

    fun onTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val pointerCount = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                capture(event)
                lastPointerCount = pointerCount
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    if (lastPointerCount != 1) capture(event)
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY
                    rotate(dx, dy)
                    lastX = x
                    lastY = y
                } else if (pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    val pinch = distance(event)

                    if (lastPointerCount < 2 || lastPinchDistance <= 0f) {
                        capture(event)
                    } else {
                        // Pinch zoom / pull out.
                        val ratio = (lastPinchDistance / max(1f, pinch)).coerceIn(0.82f, 1.18f)
                        distance = (distance * ratio).coerceIn(0.65f, 50f)

                        // Two-finger drag: move/pull model on screen, no rotation.
                        val dx = midX - lastMidX
                        val dy = midY - lastMidY
                        pan(dx, dy, viewWidth, viewHeight)
                    }

                    lastMidX = midX
                    lastMidY = midY
                    lastPinchDistance = pinch
                }
                lastPointerCount = pointerCount
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPointerCount = 0
                return true
            }
        }
        return true
    }

    fun apply(camera: Camera, aspect: Double) {
        // Keep the camera stable. We apply touch movement to the loaded model root
        // because Filament's ModelViewer utility can overwrite custom camera changes.
        camera.setProjection(45.0, aspect, 0.05, 1000.0, Camera.Fov.VERTICAL)
        camera.lookAt(
            0.0, 0.0, 3.0,
            0.0, 0.0, 0.0,
            0.0, 1.0, 0.0
        )
    }

    fun modelTransform(): FloatArray {
        val safeDistance = distance.coerceIn(0.65f, 50f)
        val s = (3.0f / safeDistance).coerceIn(0.06f, 4.5f)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)

        // Column-major matrix: T * Ry * Rx * S
        return floatArrayOf(
            cy * s,        0f,      -sy * s,       0f,
            sy * sp * s,   cp * s,   cy * sp * s,  0f,
            sy * cp * s,  -sp * s,   cy * cp * s,  0f,
            targetX,       targetY,  targetZ,      1f
        )
    }

    private fun rotate(dx: Float, dy: Float) {
        yaw -= dx * 0.008f
        pitch += dy * 0.008f
        val limit = (PI.toFloat() * 0.49f)
        pitch = pitch.coerceIn(-limit, limit)
    }

    private fun pan(dx: Float, dy: Float, viewWidth: Int, viewHeight: Int) {
        val scale = distance * 1.45f / max(1, viewHeight)
        // Two-finger drag should move the model in screen-space only. No rotation.
        targetX += dx * scale
        targetY -= dy * scale
    }

    private fun capture(event: MotionEvent) {
        lastX = event.getX(0)
        lastY = event.getY(0)
        if (event.pointerCount >= 2) {
            lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
            lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
            lastPinchDistance = distance(event)
        }
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

}
