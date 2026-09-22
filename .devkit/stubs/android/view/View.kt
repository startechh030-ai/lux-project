package android.view

import android.content.Context

object Gravity {
    const val CENTER = 17
    const val CENTER_VERTICAL = 16
    const val CENTER_HORIZONTAL = 1
    const val TOP = 48
    const val BOTTOM = 80
    const val START = 8388611
    const val END = 8388613
    const val LEFT = 3
    const val RIGHT = 5
}

fun interface OnClickListener { fun onClick(view: View) }

open class View(val context: Context) {
    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
    open var visibility: Int = VISIBLE
    open var alpha: Float = 1f
    open var translationX: Float = 0f
    open var translationY: Float = 0f
    open var isEnabled: Boolean = true
    var id: Int = 0
    open fun setBackgroundResource(resId: Int) {}
    open fun setBackgroundColor(color: Int) {}
    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    open fun setOnClickListener(listener: OnClickListener?) {}
    open fun setOnLongClickListener(listener: () -> Boolean) {}
    open fun invalidate() {}
    open fun requestLayout() {}
    open fun post(action: Runnable): Boolean { action.run(); return true }
    open fun postDelayed(action: Runnable, delayMillis: Long): Boolean { action.run(); return true }
    open fun removeCallbacks(action: Runnable) {}
}

open class ViewGroup(context: Context) : View(context) {
    open fun addView(child: View) {}
    open fun addView(child: View, params: LayoutParams) {}
    open fun removeView(child: View) {}
    open fun removeAllViews() {}

    open class LayoutParams(width: Int, height: Int) {
        @JvmField var width: Int = width
        @JvmField var height: Int = height
        @JvmField var leftMargin: Int = 0
        @JvmField var topMargin: Int = 0
        @JvmField var rightMargin: Int = 0
        @JvmField var bottomMargin: Int = 0
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
}
