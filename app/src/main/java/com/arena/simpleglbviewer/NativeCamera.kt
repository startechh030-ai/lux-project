package com.arena.simpleglbviewer

import android.util.Log

object NativeCamera {
    init {
        try {
            System.loadLibrary("native-camera")
            Log.d("NativeCamera", "Library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeCamera", "Failed to load library: ${e.message}")
        }
    }

    external fun nativeInit()
    external fun nativeSetScreenSize(width: Int, height: Int)
    external fun nativeResetToDefault()
    external fun nativeSetTarget(x: Float, y: Float, z: Float)
    external fun nativeSetDistance(distance: Float)
    external fun nativeFocusOn(cx: Float, cy: Float, cz: Float, radius: Float)
    external fun nativeFrameModel(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float)
    external fun nativeOnTouchStart(x: Float, y: Float)
    external fun nativeOnTouchMove(dx: Float, dy: Float, pointerCount: Int)
    external fun nativeOnTouchEnd()
    external fun nativeOnPinch(scale: Float)
    external fun nativeOnDoubleTap(x: Float, y: Float)
    external fun nativeUpdate(deltaTime: Float)
    external fun nativeSetDamping(damping: Float)
    external fun nativeSetSensitivity(sensitivity: Float)
    external fun nativeSetAutoRotate(enabled: Boolean)
    external fun nativeToggleAutoRotate()
    external fun nativeIsAutoRotating(): Boolean
    external fun nativeSetDistanceRange(minDistance: Float, maxDistance: Float)

    // NEW: Get actual camera position (for Filament lookAt)
    external fun nativeGetCamX(): Float
    external fun nativeGetCamY(): Float
    external fun nativeGetCamZ(): Float

    // NEW: Get target/pivot point
    external fun nativeGetTargetX(): Float
    external fun nativeGetTargetY(): Float
    external fun nativeGetTargetZ(): Float

    // Getters for UI
    external fun nativeGetYaw(): Float
    external fun nativeGetPitch(): Float
    external fun nativeGetDistance(): Float

    // Convenience wrappers
    fun init() = nativeInit()
    fun setScreenSize(width: Int, height: Int) = nativeSetScreenSize(width, height)
    fun resetToDefault() = nativeResetToDefault()
    fun setTarget(x: Float, y: Float, z: Float) = nativeSetTarget(x, y, z)
    fun setDistance(distance: Float) = nativeSetDistance(distance)
    fun focusOn(cx: Float, cy: Float, cz: Float, radius: Float) = nativeFocusOn(cx, cy, cz, radius)
    fun frameModel(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) =
        nativeFrameModel(minX, minY, minZ, maxX, maxY, maxZ)
    fun onTouchStart(x: Float, y: Float) = nativeOnTouchStart(x, y)
    fun onTouchMove(dx: Float, dy: Float, pointerCount: Int) = nativeOnTouchMove(dx, dy, pointerCount)
    fun onTouchEnd() = nativeOnTouchEnd()
    fun onPinch(scale: Float) = nativeOnPinch(scale)
    fun onDoubleTap(x: Float, y: Float) = nativeOnDoubleTap(x, y)
    fun update(deltaTime: Float) = nativeUpdate(deltaTime)
    fun setDamping(damping: Float) = nativeSetDamping(damping)
    fun setSensitivity(sensitivity: Float) = nativeSetSensitivity(sensitivity)
    fun setAutoRotate(enabled: Boolean) = nativeSetAutoRotate(enabled)
    fun toggleAutoRotate() = nativeToggleAutoRotate()
    fun isAutoRotating(): Boolean = nativeIsAutoRotating()
    fun setDistanceRange(minDistance: Float, maxDistance: Float) = nativeSetDistanceRange(minDistance, maxDistance)

    // NEW convenience getters
    fun getCamX(): Float = nativeGetCamX()
    fun getCamY(): Float = nativeGetCamY()
    fun getCamZ(): Float = nativeGetCamZ()
    fun getTargetX(): Float = nativeGetTargetX()
    fun getTargetY(): Float = nativeGetTargetY()
    fun getTargetZ(): Float = nativeGetTargetZ()
    fun getYaw(): Float = nativeGetYaw()
    fun getPitch(): Float = nativeGetPitch()
    fun getDistance(): Float = nativeGetDistance()
    fun destroy() { /* cleanup if needed */ }
}
