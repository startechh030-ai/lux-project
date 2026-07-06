package com.lux.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * HUD overlay rendered on top of the 3D Filament surface.
 *
 * Displays:
 *   - Speed / lap counter
 *   - Boost fuel bar
 *   - Touch-control indicators
 *
 * In production, this could be replaced with Jetpack Compose or a full
 * Canvas-based HUD system. For now it's a simple View overlay.
 */
class HUDOverlay(context: Context) : View(context) {

    // ── Paint objects ────────────────────────────────────────────────
    private val speedPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.argb(180, 255, 255, 255)
        textSize = 24f
        isAntiAlias = true
    }

    private val boostBgPaint = Paint().apply {
        color = Color.argb(100, 50, 50, 50)
    }

    private val boostFillPaint = Paint().apply {
        color = Color.argb(200, 0, 200, 255)
    }

    private val boostBorderPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // ── Game state (set from native via JNI or local tracking) ───────
    var speed: Float = 0f
    var boost: Float = 50f
    var lapsCompleted: Int = 0
    var totalLaps: Int = 3

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEnabled) return

        val w = width.toFloat()
        val h = height.toFloat()

        // ── Speed (top-left) ──────────────────────────────────────────
        val speedText = "${speed.toInt()} km/h"
        canvas.drawText(speedText, 32f, 64f, speedPaint)
        canvas.drawText("SPEED", 32f, 88f, labelPaint)

        // ── Laps (top-right) ──────────────────────────────────────────
        val lapText = "LAP ${lapsCompleted + 1}/$totalLaps"
        val lapX = w - 32f - labelPaint.measureText(lapText)
        canvas.drawText(lapText, lapX, 64f, speedPaint)

        // ── Boost bar (bottom-center) ─────────────────────────────────
        val barWidth = w * 0.6f
        val barHeight = 24f
        val barX = (w - barWidth) / 2f
        val barY = h - 64f

        // Background
        val barRect = RectF(barX, barY, barX + barWidth, barY + barHeight)
        canvas.drawRoundRect(barRect, 12f, 12f, boostBgPaint)

        // Fill
        val fillWidth = (boost / 100f) * barWidth
        val fillRect = RectF(barX, barY, barX + fillWidth, barY + barHeight)
        canvas.drawRoundRect(fillRect, 12f, 12f, boostFillPaint)

        // Border
        canvas.drawRoundRect(barRect, 12f, 12f, boostBorderPaint)

        // Label
        canvas.drawText("BOOST", barX, barY - 8f, labelPaint)
    }
}
