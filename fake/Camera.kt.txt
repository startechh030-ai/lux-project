package luxe.texture3d.app

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

/**
 * Kotlin bridge for Native Orbit Camera
 * Handles touch input, gesture detection, and JNI communication
 * Call update() every frame from your render loop
 */
class Camera(context: Context) {

    companion object {
        init {
            System.loadLibrary("native-camera")
        }
    }

    // JNI native methods
    private external fun nativeCreate()
    private external fun nativeDestroy()
    private external fun nativeSetViewport(w: Int, h: Int)
    private external fun nativeSetFov(fovDegrees: Float)
    private external fun nativeSetDamping(damping: Float)
    private external fun nativeUpdate(dt: Float)

    private external fun nativeTouchDown(x: Float, y: Float, pointers: Int)
    private external fun nativeTouchMove(x: Float, y: Float, pointerCount: Int, pointersX: FloatArray?, pointersY: FloatArray?)
    private external fun nativeTouchUp(pointers: Int)
    private external fun nativePinchStart(distance: Float)
    private external fun nativePinch(distance: Float)
    private external fun nativePinchEnd()
    private external fun nativeDoubleTap()

    private external fun nativeSetMeshBounds(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float)
    private external fun nativeFocusOnBounds()
    private external fun nativeFocusOnPoint(x: Float, y: Float, z: Float, distance: Float)
    private external fun nativeReset()

    private external fun nativeRaycastScreen(screenX: Float, screenY: Float, outHit: FloatArray?): Boolean

    external fun nativeGetViewMatrix(outMatrix: FloatArray)
    external fun nativeGetProjectionMatrix(outMatrix: FloatArray)
    external fun nativeGetCameraPosition(outPos: FloatArray)
    external fun nativeGetTarget(outTarget: FloatArray)
    external fun nativeGetYaw(): Float
    external fun nativeGetPitch(): Float
    external fun nativeGetDistance(): Float

    // Touch tracking
    private var activePointers = mutableListOf<Pointer>()
    private var isPinching = false

    private data class Pointer(val id: Int, var x: Float, var y: Float)

    // Gesture detectors
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            nativeDoubleTap()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isPinching = true
            val dist = getPinchDistance()
            nativePinchStart(dist)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isPinching) return false
            val dist = getPinchDistance()
            nativePinch(dist)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isPinching = false
            nativePinchEnd()
        }
    })

    init {
        nativeCreate()
    }

    fun destroy() {
        nativeDestroy()
    }

    /**
     * Call this every frame before rendering
     */
    fun update(dt: Float) {
        nativeUpdate(dt)
    }

    /**
     * Set screen dimensions (call on surface changed)
     */
    fun setViewport(width: Int, height: Int) {
        nativeSetViewport(width, height)
    }

    /**
     * Set field of view in degrees (default 45)
     */
    fun setFov(fovDegrees: Float) {
        nativeSetFov(fovDegrees)
    }

    /**
     * Set camera smoothing/damping (0.0 = instant, 1.0 = no movement)
     * Default 0.15 for nice Nomad-like feel
     */
    fun setDamping(damping: Float) {
        nativeSetDamping(damping)
    }

    /**
     * Set mesh bounds for auto-focus and raycasting
     */
    fun setMeshBounds(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
        nativeSetMeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * Auto-focus camera to fit mesh in view
     */
    fun focusOnBounds() {
        nativeFocusOnBounds()
    }

    /**
     * Focus on specific point with desired distance
     */
    fun focusOnPoint(x: Float, y: Float, z: Float, distance: Float) {
        nativeFocusOnPoint(x, y, z, distance)
    }

    /**
     * Reset camera to default position
     */
    fun reset() {
        nativeReset()
    }

    /**
     * Raycast from screen position to mesh bounds
     * Returns hit point in world space if intersection found
     */
    fun raycastScreen(screenX: Float, screenY: Float): FloatArray? {
        val hit = FloatArray(3)
        return if (nativeRaycastScreen(screenX, screenY, hit)) hit else null
    }

    /**
     * Get current view matrix (call after update())
     */
    fun getViewMatrix(): FloatArray {
        val mat = FloatArray(16)
        nativeGetViewMatrix(mat)
        return mat
    }

    /**
     * Get current projection matrix
     */
    fun getProjectionMatrix(): FloatArray {
        val mat = FloatArray(16)
        nativeGetProjectionMatrix(mat)
        return mat
    }

    /**
     * Get camera position in world space
     */
    fun getCameraPosition(): FloatArray {
        val pos = FloatArray(3)
        nativeGetCameraPosition(pos)
        return pos
    }

    /**
     * Get camera target/pivot point
     */
    fun getTarget(): FloatArray {
        val tgt = FloatArray(3)
        nativeGetTarget(tgt)
        return tgt
    }

    // ========================================================================
    // TOUCH HANDLING - Attach this to your SurfaceView/GLSurfaceView
    // ========================================================================

    /**
     * Attach to your view:
     * view.setOnTouchListener(camera::onTouch)
     */
    fun onTouch(view: View, event: MotionEvent): Boolean {
        // Let gesture detectors process first
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointers.clear()
                activePointers.add(Pointer(event.getPointerId(0), event.x, event.y))
                nativeTouchDown(event.x, event.y, 1)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                activePointers.add(Pointer(event.getPointerId(index), event.getX(index), event.getY(index)))
                updatePointerList(event)
                nativeTouchDown(getCenterX(), getCenterY(), activePointers.size)
            }

            MotionEvent.ACTION_MOVE -> {
                updatePointerList(event)
                if (activePointers.size == 1 && !isPinching) {
                    // Single finger orbit
                    nativeTouchMove(event.x, event.y, 1, null, null)
                } else if (activePointers.size >= 2) {
                    // Two finger pan (or pinch handled by scale detector)
                    val px = FloatArray(activePointers.size) { activePointers[it].x }
                    val py = FloatArray(activePointers.size) { activePointers[it].y }
                    nativeTouchMove(getCenterX(), getCenterY(), activePointers.size, px, py)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                activePointers.removeAll { it.id == id }
                if (activePointers.isEmpty()) {
                    nativeTouchUp(0)
                } else {
                    nativeTouchDown(getCenterX(), getCenterY(), activePointers.size)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                isPinching = false
                nativeTouchUp(0)
            }
        }

        return true
    }

    private fun updatePointerList(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val ptr = activePointers.find { it.id == id }
            if (ptr != null) {
                ptr.x = event.getX(i)
                ptr.y = event.getY(i)
            }
        }
    }

    private fun getCenterX(): Float {
        if (activePointers.isEmpty()) return 0f
        return activePointers.sumOf { it.x.toDouble() }.toFloat() / activePointers.size
    }

    private fun getCenterY(): Float {
        if (activePointers.isEmpty()) return 0f
        return activePointers.sumOf { it.y.toDouble() }.toFloat() / activePointers.size
    }

    private fun getPinchDistance(): Float {
        if (activePointers.size < 2) return 0f
        val dx = activePointers[0].x - activePointers[1].x
        val dy = activePointers[0].y - activePointers[1].y
        return hypot(dx, dy)
    }
}
