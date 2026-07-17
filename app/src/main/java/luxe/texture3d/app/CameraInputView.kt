package luxe.texture3d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Touch input for the Luxe C++ camera. No Filament Manipulator is involved. */
class CameraInputView(context: Context) : View(context) {
    lateinit var camera: NativeCamera
    var inputEnabled = false
    var onDoubleTap: ((Float, Float) -> Unit)? = null
    var onGesture: ((String) -> Unit)? = null

    private var lastX=0f; private var lastY=0f; private var lastSpan=0f
    private var filteredX=0f; private var filteredY=0f
    private var orbitStartX=0f; private var orbitStartY=0f
    private var pinchStartSpan=0f
    private var count=0
    private var reportedGesture=""
    private var moved=false
    private var lastTapTime=0L
    private val tapSlop=12f*resources.displayMetrics.density
    private val panFilter=0.58f

    private var pivotX=0f; private var pivotY=0f; private var pivotVisible=false
    private val pivotPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xff38bdf8.toInt(); style=Paint.Style.STROKE
        strokeWidth=resources.displayMetrics.density*1.5f
    }
    private val pivotFillPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xff38bdf8.toInt(); style=Paint.Style.FILL
    }
    private val hidePivot=Runnable { pivotVisible=false; invalidate() }

    init {
        isClickable=true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setWillNotDraw(false)
    }

    fun showPivotFeedback(x:Float,y:Float) {
        pivotX=x; pivotY=y; pivotVisible=true
        removeCallbacks(hidePivot); postDelayed(hidePivot,900L); invalidate()
    }
    fun clearPivotFeedback() { removeCallbacks(hidePivot); pivotVisible=false; invalidate() }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(pivotVisible) {
            val r=resources.displayMetrics.density*5f
            canvas.drawCircle(pivotX,pivotY,r,pivotPaint)
            canvas.drawCircle(pivotX,pivotY,resources.displayMetrics.density*1.2f,pivotFillPaint)
        }
    }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if (::camera.isInitialized) camera.nativeSetViewport(w.coerceAtLeast(1),h.coerceAtLeast(1))
    }

    override fun onTouchEvent(e:MotionEvent):Boolean {
        if (!inputEnabled || !::camera.isInitialized) return true
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                count=1; lastX=e.x; lastY=e.y
                orbitStartX=e.x; orbitStartY=e.y; reportedGesture=""; moved=false
                camera.nativeBeginOrbit()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved=true; camera.nativeEndOrbit(); baseline(e)
                pinchStartSpan=span(e); camera.nativeBeginPinch()
            }
            MotionEvent.ACTION_MOVE -> {
                if(hypot(e.x-orbitStartX,e.y-orbitStartY)>tapSlop) moved=true
                if(e.pointerCount==1 && count==1) {
                    camera.nativeOrbitTo(e.x-orbitStartX,e.y-orbitStartY)
                    report("ORBIT"); lastX=e.x;lastY=e.y
                } else if(e.pointerCount>=2) {
                    val rawX=midX(e); val rawY=midY(e); val rawSpan=span(e)
                    if(count==e.pointerCount) {
                        filteredX+=(rawX-filteredX)*panFilter
                        filteredY+=(rawY-filteredY)*panFilter
                        camera.nativePan(filteredX-lastX,filteredY-lastY)
                        if(pinchStartSpan>1f&&rawSpan>1f) camera.nativeZoomTo(rawSpan/pinchStartSpan)
                        report("PAN / ZOOM")
                        lastX=filteredX;lastY=filteredY;lastSpan=rawSpan
                    } else baseline(e)
                    count=e.pointerCount
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                camera.nativeEndPinch()
                if (e.pointerCount-1==1) {
                    val remaining=if(e.actionIndex==0) 1 else 0
                    count=1; lastX=e.getX(remaining);lastY=e.getY(remaining)
                    orbitStartX=lastX;orbitStartY=lastY;camera.nativeBeginOrbit()
                } else count=0
            }
            MotionEvent.ACTION_UP -> {
                camera.nativeEndOrbit();camera.nativeEndPinch();count=0
                if(!moved) {
                    val now=e.eventTime
                    if(now-lastTapTime in 1..350) { onDoubleTap?.invoke(e.x,e.y);lastTapTime=0L }
                    else lastTapTime=now
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                camera.nativeEndOrbit();camera.nativeEndPinch();count=0
            }
        }
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
    private fun baseline(e:MotionEvent) {
        count=e.pointerCount
        filteredX=midX(e);filteredY=midY(e)
        lastX=filteredX;lastY=filteredY;lastSpan=span(e)
    }
    private fun midX(e:MotionEvent)=(e.getX(0)+e.getX(1))*0.5f
    private fun midY(e:MotionEvent)=(e.getY(0)+e.getY(1))*0.5f
    private fun span(e:MotionEvent)=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0))
    private fun report(name:String) {
        if(reportedGesture!=name) { reportedGesture=name;onGesture?.invoke(name) }
    }
}
