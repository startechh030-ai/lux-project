package android.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup

open class LinearLayout(context: Context) : ViewGroup(context) {
    companion object { const val HORIZONTAL = 0; const val VERTICAL = 1 }
    var orientation: Int = HORIZONTAL
    var gravity: Int = Gravity.START or Gravity.TOP

    class LayoutParams(width: Int, height: Int) : ViewGroup.LayoutParams(width, height) {
        @JvmField var weight: Float = 0f
        @JvmField var gravity: Int = -1
        constructor(width: Int, height: Int, weight: Float) : this(width, height) { this.weight = weight }
    }
}

open class FrameLayout(context: Context) : ViewGroup(context) {
    class LayoutParams(width: Int, height: Int) : ViewGroup.LayoutParams(width, height) {
        @JvmField var gravity: Int = -1
        constructor(width: Int, height: Int, gravity: Int) : this(width, height) { this.gravity = gravity }
    }
}

open class ScrollView(context: Context) : FrameLayout(context)

open class TextView(context: Context) : View(context) {
    open var text: CharSequence = ""
    var textSize: Float = 12f
    var gravity: Int = Gravity.START
    var hint: CharSequence = ""
    fun setTextColor(color: Int) {}
    fun setTypeface(typeface: Any?, style: Int) {}
    fun setSingleLine(singleLine: Boolean) {}
    fun setLines(lines: Int) {}
}

open class ImageButton(context: Context) : View(context) {
    fun setImageResource(resId: Int) {}
    var contentDescription: CharSequence = ""
    fun setColorFilter(color: Int) {}
}

class Toast(context: Context) {
    fun show() {}
    fun setDuration(duration: Int) {}
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        @JvmStatic fun makeText(context: Context, text: CharSequence, duration: Int): Toast = Toast(context)
    }
}
