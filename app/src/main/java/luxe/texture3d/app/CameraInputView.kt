package luxe.texture3d.app

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.google.android.filament.utils.Manipulator
import kotlin.math.abs
import kotlin.math.hypot

/** Low-latency touch adapter for Filament's native orbit Manipulator. */
class CameraInputView(context: Context) : View(context) {
    lateinit var manipulator: Manipulator
    var onGesture: ((String) -> Unit)? = null
    var onTap: ((Float, Float) -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var inputEnabled: Boolean = false

    private enum class TwoFingerMode { NONE, UNDECIDED, PAN, ZOOM }

    private var activePointers = 0
    private var twoFingerMode = TwoFingerMode.NONE
    private var initialMidX = 0f
    private var initialMidY = 0f
    private var initialSpan = 0f
    private var previousSpan = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private var reportedGesture = ""

    private val tapSlop = 12f * resources.displayMetrics.density
    private val intentThreshold = 0.75f * resources.displayMetrics.density
    private val zoomBias = 1.15f
    private val zoomScale = 0.1f

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || !::manipulator.isInitialized) return true
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                reportedGesture = ""
                activePointers = 1
                twoFingerMode = TwoFingerMode.NONE
                manipulator.grabBegin(event.x.toInt(), filamentY(event.y), false)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                manipulator.grabEnd()
                activePointers = event.pointerCount
                if (activePointers >= 2) {
                    initialMidX = midpointX(event)
                    initialMidY = midpointY(event)
                    initialSpan = span(event)
                    previousSpan = initialSpan
                    twoFingerMode = TwoFingerMode.UNDECIDED
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > tapSlop) moved = true

                if (event.pointerCount == 1 && activePointers == 1) {
                    manipulator.grabUpdate(event.x.toInt(), filamentY(event.y))
                    report("ORBIT")
                } else if (event.pointerCount >= 2 && activePointers >= 2) {
                    handleTwoFingerMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFingerMode == TwoFingerMode.PAN) manipulator.grabEnd()
                activePointers = event.pointerCount - 1
                twoFingerMode = TwoFingerMode.NONE
                previousSpan = 0f

                if (activePointers == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    manipulator.grabBegin(
                        event.getX(remaining).toInt(),
                        filamentY(event.getY(remaining)),
                        false
                    )
                }
            }

            MotionEvent.ACTION_UP -> {
                // Orbit and pan own a grab session; grabEnd is harmless after zoom.
                manipulator.grabEnd()
                activePointers = 0
                twoFingerMode = TwoFingerMode.NONE
                if (!moved) {
                    onTap?.invoke(event.x,event.y)
                    val now = event.eventTime
                    if (now - lastTapTime in 1..350) {
                        report("DOUBLE TAP")
                        onDoubleTap?.invoke()
                        lastTapTime = 0L
                    } else lastTapTime = now
                    performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                manipulator.grabEnd()
                activePointers = 0
                twoFingerMode = TwoFingerMode.NONE
                previousSpan = 0f
            }
        }
        return true
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        val midX = midpointX(event)
        val midY = midpointY(event)
        val currentSpan = span(event)

        if (twoFingerMode == TwoFingerMode.UNDECIDED) {
            val panTravel = hypot(midX - initialMidX, midY - initialMidY)
            val zoomTravel = abs(currentSpan - initialSpan)

            if (zoomTravel >= intentThreshold && zoomTravel > panTravel * zoomBias) {
                twoFingerMode = TwoFingerMode.ZOOM
                previousSpan = currentSpan
                report("ZOOM")
                return
            }
            if (panTravel >= intentThreshold) {
                twoFingerMode = TwoFingerMode.PAN
                manipulator.grabBegin(
                    initialMidX.toInt(), filamentY(initialMidY), true
                )
                manipulator.grabUpdate(midX.toInt(), filamentY(midY))
                report("PAN")
                return
            }
            return
        }

        when (twoFingerMode) {
            TwoFingerMode.PAN -> {
                manipulator.grabUpdate(midX.toInt(), filamentY(midY))
                report("PAN")
            }
            TwoFingerMode.ZOOM -> {
                val delta = previousSpan - currentSpan
                if (abs(delta) >= 0.15f) {
                    manipulator.scroll(
                        midX.toInt(), filamentY(midY), delta * zoomScale
                    )
                }
                previousSpan = currentSpan
                report("ZOOM")
            }
            else -> Unit
        }
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
