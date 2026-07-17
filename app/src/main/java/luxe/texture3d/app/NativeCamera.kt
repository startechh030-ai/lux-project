package luxe.texture3d.app

class NativeCamera {
    external fun nativeSetViewport(width: Int, height: Int)
    external fun nativeBeginOrbit()
    external fun nativeOrbitTo(totalDx: Float, totalDy: Float)
    /** Compatibility only for stale pre-Phase-3 input views. */
    external fun nativeOrbit(dx: Float, dy: Float)
    external fun nativeEndOrbit()
    external fun nativeBeginPinch()
    external fun nativeZoomTo(totalScale: Float)
    external fun nativeEndPinch()
    external fun nativeZoom(scaleFactor: Float)
    external fun nativePan(dx: Float, dy: Float)
    external fun nativeQueuePivot(x: Float, y: Float, z: Float)
    external fun nativeReset()
    external fun nativeUpdate(seconds: Double, outputPose: FloatArray)
    companion object { init { System.loadLibrary("luxe_camera") } }
}
