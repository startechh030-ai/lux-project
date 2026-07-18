package luxe.texture3d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Warm-black editor panel with clipped diagonal ends. */
class CutPanelView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xee191715.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5538bdf8; style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cut = height * 0.32f
        path.reset()
        path.moveTo(cut, 0f)
        path.lineTo(width-cut, 0f)
        path.lineTo(width.toFloat(), cut)
        path.lineTo(width-cut, height.toFloat())
        path.lineTo(cut, height.toFloat())
        path.lineTo(0f, height-cut)
        path.lineTo(0f, cut)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)
    }
}
