package com.arena.simpleglbviewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class AxisGizmoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Axis { X, Y, Z }

    private var currentYaw = 0f
    private var currentPitch = 0f

    private val paintX = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val paintY = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FF44")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val paintZ = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4444FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var onGizmoClickListener: ((Axis) -> Unit)? = null

    fun setOnGizmoClickListener(listener: (Axis) -> Unit) {
        onGizmoClickListener = listener
    }

    fun updateRotation(yaw: Float, pitch: Float) {
        currentYaw = yaw
        currentPitch = pitch
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        cy = height / 2f
        val radius = min(cx, cy) - 20f

        // Background
        canvas.drawCircle(cx, cy, radius + 10f, bgPaint)
        canvas.drawCircle(cx, cy, radius + 10f, borderPaint)

        // Project axes based on camera orientation
        // Camera yaw/pitch determine which axes are visible
        val cosP = cos(currentPitch)
        val sinP = sin(currentPitch)
        val cosY = cos(currentYaw)
        val sinY = sin(currentYaw)

        // X axis (red) - world right
        val xEnd = projectAxis(1f, 0f, 0f, cx, cy, radius)
        canvas.drawLine(cx, cy, xEnd.first, xEnd.second, paintX)
        canvas.drawText("X", xEnd.first + 10f, xEnd.second, textPaint)

        // Y axis (green) - world up
        val yEnd = projectAxis(0f, 1f, 0f, cx, cy, radius)
        canvas.drawLine(cx, cy, yEnd.first, yEnd.second, paintY)
        canvas.drawText("Y", yEnd.first, yEnd.second - 10f, textPaint)

        // Z axis (blue) - world forward
        val zEnd = projectAxis(0f, 0f, 1f, cx, cy, radius)
        canvas.drawLine(cx, cy, zEnd.first, zEnd.second, paintZ)
        canvas.drawText("Z", zEnd.first + 10f, zEnd.second + 20f, textPaint)
    }

    private fun projectAxis(x: Float, y: Float, z: Float, cx: Float, cy: Float, scale: Float): Pair<Float, Float> {
        // Apply inverse camera rotation to show axes relative to camera view
        val cosP = cos(currentPitch)
        val sinP = sin(currentPitch)
        val cosY = cos(currentYaw)
        val sinY = sin(currentYaw)

        // Rotate by inverse camera orientation
        val rx = x * cosY + z * sinY
        val ry = y * cosP - (-x * sinY + z * cosY) * sinP
        val rz = -(x * sinY - z * cosY) // depth

        // Simple perspective: scale by depth
        val depthScale = if (rz > 0) 1f else 0.6f
        val px = cx + rx * scale * 0.6f * depthScale
        val py = cy - ry * scale * 0.6f * depthScale

        return Pair(px, py)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val cx = width / 2f
            val cy = height / 2f
            val dx = event.x - cx
            val dy = event.y - cy
            // Simple axis detection based on angle
            val angle = atan2(dy, dx)
            when {
                abs(angle) < PI / 6 -> onGizmoClickListener?.invoke(Axis.X)
                abs(angle - PI / 2) < PI / 6 -> onGizmoClickListener?.invoke(Axis.Y)
                abs(angle + PI / 2) < PI / 6 -> onGizmoClickListener?.invoke(Axis.Z)
            }
        }
        return true
    }
}
