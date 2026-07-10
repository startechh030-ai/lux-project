package luxe.texture3d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class AxisGizmoView(context: Context) : View(context) {
    var yaw: Float = 0f
        set(value) { field = value; invalidate() }
    var pitch: Float = 0f
        set(value) { field = value; invalidate() }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 8, 15, 25)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = width.coerceAtMost(height) * 0.34f

        canvas.drawRoundRect(RectF(6f, 6f, width - 6f, height - 6f), 22f, 22f, shadowPaint)

        val axes = listOf(
            Axis("X", Vec3(1f, 0f, 0f), Color.rgb(245, 70, 100)),
            Axis("Y", Vec3(0f, 1f, 0f), Color.rgb(80, 210, 70)),
            Axis("Z", Vec3(0f, 0f, 1f), Color.rgb(70, 145, 255))
        ).map { axis ->
            val v = rotateForCamera(axis.vec)
            DrawAxis(axis.label, v.x, -v.y, v.z, axis.color)
        }.sortedBy { it.depth }

        for (a in axes) {
            linePaint.color = a.color
            val ex = cx + a.x * r
            val ey = cy + a.y * r
            canvas.drawLine(cx, cy, ex, ey, linePaint)
            spherePaint.color = a.color
            canvas.drawCircle(ex, ey, 16f, spherePaint)
            canvas.drawText(a.label, ex, ey + 8f, textPaint)
        }

        spherePaint.color = Color.argb(230, 230, 230, 235)
        canvas.drawCircle(cx, cy, 9f, spherePaint)
    }

    private fun rotateForCamera(v: Vec3): Vec3 {
        // Match the scene camera yaw/pitch enough for a viewport orientation widget.
        val cy = cos(-yaw)
        val sy = sin(-yaw)
        var x = v.x * cy + v.z * sy
        var z = -v.x * sy + v.z * cy
        var y = v.y

        val cp = cos(-pitch)
        val sp = sin(-pitch)
        val y2 = y * cp - z * sp
        val z2 = y * sp + z * cp
        y = y2
        z = z2
        return Vec3(x, y, z)
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private data class Axis(val label: String, val vec: Vec3, val color: Int)
    private data class DrawAxis(val label: String, val x: Float, val y: Float, val depth: Float, val color: Int)
}
