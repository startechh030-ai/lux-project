package com.arena.simpleglbviewer

import android.view.MotionEvent
import com.google.android.filament.Camera
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class CameraController {
    var yaw = 0.65f
        private set
    var pitch = -0.35f
        private set
    var distance = 3.0f
        private set

    private var targetX = 0f
    private var targetY = 0f
    private var targetZ = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastPinchDistance = 0f
    private var lastPointerCount = 0

    fun reset() {
        yaw = 0.65f
        pitch = -0.35f
        distance = 3.0f
        targetX = 0f
        targetY = 0f
        targetZ = 0f
        lastPointerCount = 0
    }

    fun onTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val pointerCount = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                capture(event)
                lastPointerCount = pointerCount
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    if (lastPointerCount != 1) capture(event)
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY
                    rotate(dx, dy)
                    lastX = x
                    lastY = y
                } else if (pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    val pinch = distance(event)

                    if (lastPointerCount < 2 || lastPinchDistance <= 0f) {
                        capture(event)
                    } else {
                        // Pinch zoom / pull out.
                        val ratio = (lastPinchDistance / max(1f, pinch)).coerceIn(0.82f, 1.18f)
                        distance = (distance * ratio).coerceIn(0.65f, 50f)

                        // Two-finger drag: move/pull model on screen, no rotation.
                        val dx = midX - lastMidX
                        val dy = midY - lastMidY
                        pan(dx, dy, viewWidth, viewHeight)
                    }

                    lastMidX = midX
                    lastMidY = midY
                    lastPinchDistance = pinch
                }
                lastPointerCount = pointerCount
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPointerCount = 0
                return true
            }
        }
        return true
    }

    fun apply(camera: Camera, aspect: Double) {
        val cp = cos(pitch)
        val eyeX = targetX + distance * cp * sin(yaw)
        val eyeY = targetY + distance * sin(pitch)
        val eyeZ = targetZ + distance * cp * cos(yaw)

        camera.setProjection(45.0, aspect, 0.05, 1000.0, Camera.Fov.VERTICAL)
        camera.lookAt(
            eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(),
            targetX.toDouble(), targetY.toDouble(), targetZ.toDouble(),
            0.0, 1.0, 0.0
        )
    }

    private fun rotate(dx: Float, dy: Float) {
        yaw -= dx * 0.008f
        pitch += dy * 0.008f
        val limit = (PI.toFloat() * 0.49f)
        pitch = pitch.coerceIn(-limit, limit)
    }

    private fun pan(dx: Float, dy: Float, viewWidth: Int, viewHeight: Int) {
        val scale = distance * 1.45f / max(1, viewHeight)

        // Camera right vector from yaw.
        val rightX = cos(yaw)
        val rightZ = -sin(yaw)

        // Approx camera up vector from yaw/pitch.
        val forwardX = -cos(pitch) * sin(yaw)
        val forwardY = -sin(pitch)
        val forwardZ = -cos(pitch) * cos(yaw)
        val up = cross(rightX, 0f, rightZ, forwardX, forwardY, forwardZ)

        targetX -= rightX * dx * scale
        targetZ -= rightZ * dx * scale
        targetX += up.x * dy * scale
        targetY += up.y * dy * scale
        targetZ += up.z * dy * scale
    }

    private fun capture(event: MotionEvent) {
        lastX = event.getX(0)
        lastY = event.getY(0)
        if (event.pointerCount >= 2) {
            lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
            lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
            lastPinchDistance = distance(event)
        }
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun cross(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Vec3 {
        return Vec3(
            ay * bz - az * by,
            az * bx - ax * bz,
            ax * by - ay * bx
        ).normalized()
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        fun normalized(): Vec3 {
            val len = sqrt(x * x + y * y + z * z)
            if (len <= 0.00001f) return Vec3(0f, 1f, 0f)
            return Vec3(x / len, y / len, z / len)
        }
    }
}
