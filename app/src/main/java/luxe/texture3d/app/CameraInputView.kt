package luxe.texture3d.app

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.google.android.filament.utils.Manipulator
import kotlin.math.hypot

/**
 * Lightweight touch adapter for Filament's native Manipulator.
 *
 * Filament's stock GestureDetector waits for several samples and then chooses
 * either PAN or ZOOM. That confidence window feels like a dead zone on touch
 * screens. This adapter starts two-finger strafe immediately and allows pinch
 * zoom during the same gesture.
 */
class CameraInputView(context: Context) : View(context) {
    lateinit var manipulator: Manipulator
    var onGesture: ((String) -> Unit)? = null

    private var activePointers = 0
    private var previousSpan = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private var reportedGesture = ""
    private val tapSlop = 12f * resources.displayMetrics.density
    private val zoomScale = 0.1f

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::manipulator.isInitialized) return true
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                reportedGesture = ""
                activePointers = 1
                manipulator.grabBegin(event.x.toInt(), filamentY(event.y), false)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                manipulator.grabEnd()
                activePointers = event.pointerCount
                if (activePointers >= 2) {
                    val x = midpointX(event)
                    val y = midpointY(event)
                    previousSpan = span(event)
                    // strafe=true translates the persistent orbit target.
                    manipulator.grabBegin(x.toInt(), filamentY(y), true)
                    report("PAN / ZOOM")
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > tapSlop) moved = true
                if (event.pointerCount == 1 && activePointers == 1) {
                    manipulator.grabUpdate(event.x.toInt(), filamentY(event.y))
                    report("ORBIT")
                } else if (event.pointerCount >= 2 && activePointers >= 2) {
                    val x = midpointX(event)
                    val y = midpointY(event)
                    val currentSpan = span(event)
                    manipulator.grabUpdate(x.toInt(), filamentY(y))
                    if (previousSpan > 0f) {
                        manipulator.scroll(
                            x.toInt(), filamentY(y),
                            (previousSpan - currentSpan) * zoomScale
                        )
                    }
                    previousSpan = currentSpan
                    report("PAN / ZOOM")
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                manipulator.grabEnd()
                activePointers = event.pointerCount - 1
                previousSpan = 0f
                if (activePointers == 1) {
                    // Continue naturally from pan to orbit with the finger that
                    // remains down, matching mobile sculpting navigation.
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    manipulator.grabBegin(
                        event.getX(remaining).toInt(),
                        filamentY(event.getY(remaining)),
                        false
                    )
                }
            }

            MotionEvent.ACTION_UP -> {
                manipulator.grabEnd()
                activePointers = 0
                if (!moved) {
                    val now = event.eventTime
                    if (now - lastTapTime in 1..350) {
                        report("DOUBLE TAP")
                        lastTapTime = 0L
                    } else lastTapTime = now
                    performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                manipulator.grabEnd()
                activePointers = 0
                previousSpan = 0f
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun filamentY(androidY: Float) = (height - androidY).toInt()

    private fun midpointX(event: MotionEvent) = (event.getX(0) + event.getX(1)) * 0.5f
    private fun midpointY(event: MotionEvent) = (event.getY(0) + event.getY(1)) * 0.5f
    private fun span(event: MotionEvent) = hypot(
        event.getX(1) - event.getX(0),
        event.getY(1) - event.getY(0)
    )

    private fun report(name: String) {
        if (reportedGesture != name) {
            reportedGesture = name
            onGesture?.invoke(name)
        }
    }
}
