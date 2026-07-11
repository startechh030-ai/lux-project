package luxe.texture3d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class PivotDotView(context: Context) : View(context) {
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 56, 189, 248)
        style = Paint.Style.FILL
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 235, 250, 255)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        canvas.drawCircle(cx, cy, 7f, outer)
        canvas.drawCircle(cx, cy, 2.5f, inner)
    }
}
