package luxe.texture3d.app

class NativeCamera {
    external fun nativeSetViewport(width: Int, height: Int)
    external fun nativeOrbit(dx: Float, dy: Float)
    external fun nativeZoom(scaleFactor: Float)
    external fun nativePan(dx: Float, dy: Float)
    external fun nativeSetPivot(x: Float, y: Float, z: Float)
    external fun nativeReset()
    external fun nativeUpdate(seconds: Double, outputPose: FloatArray)
    companion object { init { System.loadLibrary("luxe_camera") } }
}
