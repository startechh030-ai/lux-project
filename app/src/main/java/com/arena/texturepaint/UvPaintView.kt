package com.arena.texturepaint

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class UvPaintView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var brushColor: Int = Color.rgb(255, 80, 40)
    var brushSize: Float = 48f
    var brushOpacity: Float = 1f
    var eraser: Boolean = false

    private val textureSize = 2048
    private val textureBitmap = Bitmap.createBitmap(textureSize, textureSize, Bitmap.Config.ARGB_8888)
    private val textureCanvas = Canvas(textureBitmap)
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val viewPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
    }
    private val islandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 124, 92, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    init {
        textureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(16, 18, 22))
        val side = max(1f, width.coerceAtMost(height).toFloat())
        val left = (width - side) * 0.5f
        val top = (height - side) * 0.5f
        val dst = RectF(left, top, left + side, top + side)

        drawGrid(canvas, dst)
        canvas.drawBitmap(textureBitmap, null, dst, viewPaint)

        // Temporary UV island debug outline. Replace with native UV line buffers next.
        canvas.drawRect(dst, islandPaint)
        canvas.drawOval(RectF(dst.left + side * .18f, dst.top + side * .16f, dst.left + side * .82f, dst.top + side * .84f), islandPaint)
    }

    private fun drawGrid(canvas: Canvas, dst: RectF) {
        val steps = 8
        for (i in 0..steps) {
            val x = dst.left + dst.width() * i / steps
            val y = dst.top + dst.height() * i / steps
            canvas.drawLine(x, dst.top, x, dst.bottom, gridPaint)
            canvas.drawLine(dst.left, y, dst.right, y, gridPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            paintAt(event.x, event.y)
            return true
        }
        return true
    }

    private fun paintAt(x: Float, y: Float) {
        val side = width.coerceAtMost(height).toFloat()
        val left = (width - side) * 0.5f
        val top = (height - side) * 0.5f
        val u = ((x - left) / side).coerceIn(0f, 1f)
        val v = ((y - top) / side).coerceIn(0f, 1f)
        val tx = u * textureSize
        val ty = v * textureSize

        if (eraser) {
            brushPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            brushPaint.alpha = 255
        } else {
            brushPaint.xfermode = null
            brushPaint.color = brushColor
            brushPaint.alpha = (brushOpacity.coerceIn(0f, 1f) * 255).toInt()
        }
        val radius = brushSize * (textureSize / max(1f, side))
        textureCanvas.drawCircle(tx, ty, radius, brushPaint)
        invalidate()
    }

    fun clearTexture() {
        textureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
    }

    fun savePng(file: File): Boolean = runCatching {
        FileOutputStream(file).use { out -> textureBitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }.getOrDefault(false)
}
