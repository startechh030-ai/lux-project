package com.arena.simpleglbviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class EditorChromeView(context: Context) : View(context) {
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(218, 18, 25, 34)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 235, 235, 240)
        textAlign = Paint.Align.CENTER
        textSize = 20f
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        style = Paint.Style.FILL
    }

    private val topLeft = listOf("Open", "Share", "Light", "Image", "Video")
    private val topRight = listOf("Paint", "Mask", "Layers", "Settings", "Tune")
    private val leftTools = listOf("Sub", "Smooth", "Mask")
    private val rightTools = listOf("Gizmo", "Measure", "Select")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val h = 48f * d
        val radius = 12f * d

        drawPanel(canvas, 0f, 0f, width * 0.34f, h + 6f * d, radius)
        drawPanel(canvas, width * 0.73f, 0f, width.toFloat(), h + 6f * d, radius)
        drawPanel(canvas, 0f, h + 10f * d, 54f * d, height - 58f * d, radius)
        drawPanel(canvas, width - 64f * d, h + 10f * d, width.toFloat(), height - 58f * d, radius)

        drawTopLabels(canvas, topLeft, 82f * d, 31f * d, 64f * d)
        drawTopLabels(canvas, topRight, width * 0.765f, 31f * d, 58f * d)
        drawSideLabels(canvas, leftTools, 28f * d, 125f * d, 68f * d)
        drawSideLabels(canvas, rightTools, width - 32f * d, 125f * d, 75f * d)

        // Small bottom strip hint like sculpting apps.
        drawPanel(canvas, width * 0.24f, height - 46f * d, width * 0.76f, height.toFloat(), radius)
        drawTopLabels(canvas, listOf("Orbit", "Pan", "Zoom", "Reset"), width * 0.35f, height - 17f * d, 82f * d)
    }

    private fun drawPanel(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, panelPaint)
    }

    private fun drawTopLabels(canvas: Canvas, labels: List<String>, startX: Float, y: Float, step: Float) {
        labels.forEachIndexed { i, label ->
            val x = startX + i * step
            canvas.drawCircle(x, y - 11f, 5f, accentPaint)
            canvas.drawText(label, x, y + 15f, textPaint)
        }
    }

    private fun drawSideLabels(canvas: Canvas, labels: List<String>, x: Float, startY: Float, step: Float) {
        labels.forEachIndexed { i, label ->
            val y = startY + i * step
            canvas.drawCircle(x, y - 15f, 6f, accentPaint)
            canvas.drawText(label, x, y + 14f, textPaint)
        }
    }
}
