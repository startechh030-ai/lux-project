package luxe.texture3d.app

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.hypot

/**
 * Viewport-owned gesture recognizer. It avoids gesture-detector conflicts and
 * forwards only compact deltas to the native camera controller.
 */
class CameraSurfaceView(context: Context) : SurfaceView(context) {
    lateinit var cameraController: NativeCamera

    private var previousCentroidX = 0f
    private var previousCentroidY = 0f
    private var previousSpan = 0f
    private var previousPointerCount = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L

    private val movementSlop = 18f * resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (::cameraController.isInitialized) {
            cameraController.nativeSetViewport(w.coerceAtLeast(1), h.coerceAtLeast(1))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::cameraController.isInitialized) return true
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                captureBaseline(event)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Pointer indices change after these events. Ignore their delta
                // and establish a fresh baseline on the next MOVE event.
                previousPointerCount = 0
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val count = event.pointerCount
                val centroidX = centroidX(event)
                val centroidY = centroidY(event)

                if (hypot(event.x - downX, event.y - downY) > movementSlop) moved = true

                if (previousPointerCount == count) {
                    if (count == 1) {
                        cameraController.nativeOrbit(
                            centroidX - previousCentroidX,
                            centroidY - previousCentroidY
                        )
                    } else if (count >= 2) {
                        val span = pointerSpan(event)
                        // Nomad-style simultaneous two-finger pan and pinch.
                        cameraController.nativePan(
                            centroidX - previousCentroidX,
                            centroidY - previousCentroidY
                        )
                        if (previousSpan > 1f && span > 1f) {
                            cameraController.nativeZoom(span / previousSpan)
                        }
                        previousSpan = span
                    }
                } else {
                    previousSpan = if (count >= 2) pointerSpan(event) else 0f
                }

                previousCentroidX = centroidX
                previousCentroidY = centroidY
                previousPointerCount = count
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val now = event.eventTime
                    if (now - lastTapTime in 1..320) {
                        cameraController.nativeReset()
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                    }
                    performClick()
                }
                previousPointerCount = 0
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                previousPointerCount = 0
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun captureBaseline(event: MotionEvent) {
        previousCentroidX = centroidX(event)
        previousCentroidY = centroidY(event)
        previousSpan = if (event.pointerCount >= 2) pointerSpan(event) else 0f
        previousPointerCount = event.pointerCount
    }

    private fun centroidX(event: MotionEvent): Float {
        var value = 0f
        for (index in 0 until event.pointerCount) value += event.getX(index)
        return value / event.pointerCount.coerceAtLeast(1)
    }

    private fun centroidY(event: MotionEvent): Float {
        var value = 0f
        for (index in 0 until event.pointerCount) value += event.getY(index)
        return value / event.pointerCount.coerceAtLeast(1)
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(
            event.getX(1) - event.getX(0),
            event.getY(1) - event.getY(0)
        )
    }
}
