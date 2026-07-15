package luxe.texture3d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.google.android.filament.utils.Manipulator
import kotlin.math.abs
import kotlin.math.hypot

/** Low-allocation touch adapter for Filament's native orbit Manipulator. */
class CameraInputView(context: Context) : View(context) {
    lateinit var manipulator: Manipulator
    var onGesture: ((String) -> Unit)? = null

    private var activePointers = 0
    private var filteredMidX = 0f
    private var filteredMidY = 0f
    private var filteredSpan = 0f
    private var previousFilteredSpan = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private var reportedGesture = ""
    private var pivotVisible = false

    private val density = resources.displayMetrics.density
    private val tapSlop = 12f * density
    private val midpointResponse = 0.62f
    private val spanResponse = 0.48f
    private val zoomScale = 0.1f
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff38bdf8.toInt()
        style = Paint.Style.FILL
    }
    private val hidePivot = Runnable {
        pivotVisible = false
        invalidate()
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setWillNotDraw(false)
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
                    filteredMidX = midpointX(event)
                    filteredMidY = midpointY(event)
                    filteredSpan = span(event)
                    previousFilteredSpan = filteredSpan
                    // Begin screen-space target translation immediately. There
                    // is no gesture-confidence threshold or locked intent.
                    manipulator.grabBegin(
                        filteredMidX.toInt(), filamentY(filteredMidY), true
                    )
                    report("PAN / ZOOM")
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > tapSlop) moved = true

                if (event.pointerCount == 1 && activePointers == 1) {
                    // Orbit path intentionally unchanged.
                    manipulator.grabUpdate(event.x.toInt(), filamentY(event.y))
                    report("ORBIT")
                } else if (event.pointerCount >= 2 && activePointers >= 2) {
                    handleFluidTwoFingerMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                manipulator.grabEnd()
                activePointers = event.pointerCount - 1
                filteredSpan = 0f
                previousFilteredSpan = 0f

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
                manipulator.grabEnd()
                activePointers = 0
                if (!moved) {
                    val now = event.eventTime
                    if (now - lastTapTime in 1..350) {
                        manipulator.jumpToBookmark(manipulator.homeBookmark)
                        revealPivot(1_000L)
                        report("RESET VIEW")
                        lastTapTime = 0L
                    } else lastTapTime = now
                    performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                manipulator.grabEnd()
                activePointers = 0
                filteredSpan = 0f
                previousFilteredSpan = 0f
            }
        }
        return true
    }

    private fun handleFluidTwoFingerMove(event: MotionEvent) {
        val rawMidX = midpointX(event)
        val rawMidY = midpointY(event)
        val rawSpan = span(event)

        // Low-pass common-mode movement separately from separation. This
        // rejects natural pinch asymmetry without introducing a dead zone.
        filteredMidX += (rawMidX - filteredMidX) * midpointResponse
        filteredMidY += (rawMidY - filteredMidY) * midpointResponse
        filteredSpan += (rawSpan - filteredSpan) * spanResponse

        // Pan is always active during a two-finger gesture.
        manipulator.grabUpdate(filteredMidX.toInt(), filamentY(filteredMidY))

        // Zoom around the stable camera pivot (viewport center), not the noisy
        // touch midpoint. This allows simultaneous pan+zoom without vibration.
        val zoomDelta = previousFilteredSpan - filteredSpan
        if (abs(zoomDelta) > 0.01f) {
            manipulator.scroll(
                width / 2,
                height / 2,
                zoomDelta * zoomScale
            )
        }
        previousFilteredSpan = filteredSpan
        report("PAN / ZOOM")
    }

    private fun revealPivot(durationMs: Long = 700L) {
        pivotVisible = true
        removeCallbacks(hidePivot)
        postDelayed(hidePivot, durationMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pivotVisible) {
            canvas.drawCircle(width * 0.5f, height * 0.5f, 2.2f * density, pivotPaint)
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
            if (name == "ORBIT" || name == "PAN / ZOOM") revealPivot()
            onGesture?.invoke(name)
        }
    }
}
