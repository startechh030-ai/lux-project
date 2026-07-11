package luxe.texture3d.app

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

class NativeModelTouchHandler(context: Context) {
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                NativeModel.nativeDoubleTap()
                return true
            }
        }
    )

    fun onTouch(view: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> NativeModel.nativeTouchDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> {
                // Static mode: one active pointer moves the model. No camera pan, no raycast.
                if (event.pointerCount == 1) NativeModel.nativeTouchMove(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeModel.nativeTouchUp()
        }
        return true
    }
}
