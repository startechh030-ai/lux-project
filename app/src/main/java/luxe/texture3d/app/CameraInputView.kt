package luxe.texture3d.app

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Touch input for the Luxe C++ camera. No Filament Manipulator is involved. */
class CameraInputView(context: Context) : View(context) {
    lateinit var camera: NativeCamera
    var inputEnabled = false
    var onOrbitTouch: ((Float, Float) -> Unit)? = null
    var onGesture: ((String) -> Unit)? = null

    private var lastX=0f; private var lastY=0f; private var lastSpan=0f
    private var count=0

    init { isClickable=true; setBackgroundColor(android.graphics.Color.TRANSPARENT) }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if (::camera.isInitialized) camera.nativeSetViewport(w.coerceAtLeast(1),h.coerceAtLeast(1))
    }

    override fun onTouchEvent(e:MotionEvent):Boolean {
        if (!inputEnabled || !::camera.isInitialized) return true
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                count=1; lastX=e.x; lastY=e.y
                onOrbitTouch?.invoke(e.x,e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { baseline(e) }
            MotionEvent.ACTION_MOVE -> {
                if(e.pointerCount==1 && count==1) {
                    camera.nativeOrbit(e.x-lastX,e.y-lastY); onGesture?.invoke("ORBIT")
                    lastX=e.x;lastY=e.y
                } else if(e.pointerCount>=2) {
                    val x=midX(e);val y=midY(e);val s=span(e)
                    if(count==e.pointerCount) {
                        camera.nativePan(x-lastX,y-lastY)
                        if(lastSpan>1f&&s>1f) camera.nativeZoom(s/lastSpan)
                        onGesture?.invoke("PAN / ZOOM")
                    }
                    lastX=x;lastY=y;lastSpan=s;count=e.pointerCount
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 == 1) {
                    val remaining = if (e.actionIndex == 0) 1 else 0
                    count = 1
                    lastX = e.getX(remaining)
                    lastY = e.getY(remaining)
                } else count = 0
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> count=0
        }
        return true
    }
    private fun baseline(e:MotionEvent){count=e.pointerCount;lastX=midX(e);lastY=midY(e);lastSpan=span(e)}
    private fun midX(e:MotionEvent)=(e.getX(0)+e.getX(1))*0.5f
    private fun midY(e:MotionEvent)=(e.getY(0)+e.getY(1))*0.5f
    private fun span(e:MotionEvent)=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0))
}
