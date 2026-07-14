package luxe.texture3d.app

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Transparent viewport input layer. Every unmodified MotionEvent is forwarded
 * to Filament's gesture detector; this view only adds diagnostic labels.
 */
class CameraInputView(context: Context) : View(context) {
    var eventSink: ((MotionEvent) -> Unit)? = null
    var onGesture: ((String) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private var reportedGesture = ""
    private val slop = 12f * resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)

        // Forward the original event synchronously. Filament needs the complete
        // DOWN / POINTER_DOWN / MOVE / POINTER_UP / UP stream with pointer IDs.
        eventSink?.invoke(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                reportedGesture = ""
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                reportedGesture = ""
                report("PAN / ZOOM")
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > slop) moved = true
                if (event.pointerCount >= 2) report("PAN / ZOOM") else report("ORBIT")
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val now = event.eventTime
                    if (now - lastTapTime in 1..350) {
                        report("FOCUS / RESET")
                        lastTapTime = 0L
                    } else lastTapTime = now
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> reportedGesture = ""
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
}
