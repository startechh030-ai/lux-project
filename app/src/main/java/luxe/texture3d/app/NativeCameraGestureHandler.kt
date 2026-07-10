package luxe.texture3d.app

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs

class NativeCameraGestureHandler(context: Context) {
    private var pointerCount = 0
    private var isPinching = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                NativeCamera.onPinch(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isPinching = false
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                NativeCamera.resetToDefault()
                return true
            }
        }
    )

    fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val centroidX = centroidX(event)
        val centroidY = centroidY(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerCount = 1
                downX = event.x
                downY = event.y
                moved = false
                NativeCamera.onTouchStart(centroidX, centroidY, pointerCount)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                NativeCamera.onTouchStart(centroidX, centroidY, pointerCount)
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 8f || abs(event.y - downY) > 8f) moved = true
                // Even during pinch, send centroid move so 2-finger pan works with pinch.
                NativeCamera.onTouchMove(centroidX, centroidY, event.pointerCount)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = event.pointerCount - 1
                NativeCamera.onTouchStart(centroidX, centroidY, pointerCount)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerCount = 0
                isPinching = false
                NativeCamera.onTouchEnd()
            }
        }
        return true
    }

    private fun centroidX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount.coerceAtLeast(1)
    }

    private fun centroidY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount.coerceAtLeast(1)
    }
}
