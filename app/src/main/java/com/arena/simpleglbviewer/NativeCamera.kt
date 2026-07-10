package com.arena.simpleglbviewer

object NativeCamera {
    init { System.loadLibrary("simple-camera-native") }

    external fun init()
    external fun destroy()
    external fun setScreenSize(width: Int, height: Int)

    external fun reset(cx: Float, cy: Float, cz: Float, radius: Float)
    external fun resetToDefault()

    external fun onTouchStart(x: Float, y: Float, pointers: Int)
    external fun onTouchMove(x: Float, y: Float, pointers: Int)
    external fun onTouchEnd()
    external fun onPinch(scale: Float)

    external fun update(deltaTime: Float)

    /**
     * Returns:
     * [0..2] eye xyz
     * [3..5] target xyz
     * [6..8] up xyz
     * [9] yaw, [10] pitch, [11] distance
     */
    external fun getCameraState(): FloatArray

    external fun setMode(mode: Int)
    external fun setAutoRotate(enabled: Boolean)
    external fun toggleAutoRotate()
    external fun isAutoRotating(): Boolean
    external fun setSensitivity(sensitivity: Float)
    external fun setDamping(damping: Float)
    external fun getYaw(): Float
    external fun getPitch(): Float
    external fun getDistance(): Float
}
