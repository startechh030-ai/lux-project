package luxe.texture3d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class PivotAxisView(context: Context) : View(context) {
    var offsetXFromCenter: Float = 0f
        set(value) { field = value; invalidate() }
    var offsetYFromCenter: Float = 0f
        set(value) { field = value; invalidate() }

    private val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val lightBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(186, 230, 253)
        style = Paint.Style.FILL
    }
    private val softBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 56, 189, 248)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val px = width * 0.5f + offsetXFromCenter
        val py = height * 0.5f + offsetYFromCenter
        val len = 34f

        // X and Y graph stuck to model pivot.
        canvas.drawLine(px - len, py, px + len, py, blue)
        canvas.drawLine(px, py - len, px, py + len, blue)

        // Z hint as a small blue circle coming out of the model.
        canvas.drawCircle(px + 20f, py - 20f, 8f, softBlue)
        canvas.drawCircle(px + 20f, py - 20f, 3.5f, lightBlue)

        // Pivot bug/dot.
        canvas.drawCircle(px, py, 8f, softBlue)
        canvas.drawCircle(px, py, 3.2f, lightBlue)
    }
}
