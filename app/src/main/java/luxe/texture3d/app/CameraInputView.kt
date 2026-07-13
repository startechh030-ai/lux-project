package luxe.texture3d.app

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Transparent input layer placed above Filament's SurfaceView. */
class CameraInputView(context: Context) : View(context) {
    lateinit var cameraController: NativeCamera
    var onGesture: ((String) -> Unit)? = null

    private var previousX = 0f
    private var previousY = 0f
    private var previousSpan = 0f
    private var previousCount = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private var reportedGesture = ""
    private val slop = 12f * resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        // The view remains visually transparent but participates in hit testing.
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (::cameraController.isInitialized) {
            cameraController.nativeSetViewport(w.coerceAtLeast(1), h.coerceAtLeast(1))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::cameraController.isInitialized) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                moved = false
                reportedGesture = ""
                setBaseline(event)
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Pointer indices are unstable on these events. The next MOVE
                // establishes a clean baseline and produces no camera jump.
                previousCount = 0
                reportedGesture = ""
            }

            MotionEvent.ACTION_MOVE -> {
                val count = event.pointerCount
                val x = centroidX(event)
                val y = centroidY(event)
                if (hypot(event.x - downX, event.y - downY) > slop) moved = true

                if (previousCount == count) {
                    if (count == 1) {
                        cameraController.nativeOrbit(x - previousX, y - previousY)
                        report("ORBIT")
                    } else if (count >= 2) {
                        val span = span(event)
                        cameraController.nativePan(x - previousX, y - previousY)
                        report("PAN / ZOOM")
                        if (previousSpan > 1f && span > 1f) {
                            cameraController.nativeZoom(span / previousSpan)
                        }
                        previousSpan = span
                    }
                } else {
                    previousSpan = if (count >= 2) span(event) else 0f
                }
                previousX = x
                previousY = y
                previousCount = count
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val now = event.eventTime
                    if (now - lastTapTime in 1..350) {
                        cameraController.nativeReset()
                        report("RESET")
                        lastTapTime = 0L
                    } else lastTapTime = now
                    performClick()
                }
                previousCount = 0
            }

            MotionEvent.ACTION_CANCEL -> previousCount = 0
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun report(name: String) {
        if (reportedGesture != name) {
            reportedGesture = name
            onGesture?.invoke(name)
        }
    }

    private fun setBaseline(event: MotionEvent) {
        previousX = centroidX(event)
        previousY = centroidY(event)
        previousSpan = if (event.pointerCount >= 2) span(event) else 0f
        previousCount = event.pointerCount
    }

    private fun centroidX(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getX(i)
        return total / event.pointerCount.coerceAtLeast(1)
    }

    private fun centroidY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getY(i)
        return total / event.pointerCount.coerceAtLeast(1)
    }

    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }
}
