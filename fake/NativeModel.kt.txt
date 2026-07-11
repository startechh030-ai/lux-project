package luxe.texture3d.app

object NativeModel {
    init { System.loadLibrary("luxe-model-native") }

    external fun nativeReset()
    external fun nativeSetViewport(width: Int, height: Int)
    external fun nativeTouchDown(x: Float, y: Float)
    external fun nativeTouchMove(x: Float, y: Float)
    external fun nativeTouchUp()
    external fun nativeDoubleTap()
    external fun nativeSetScale(scale: Float)
    external fun nativeUpdate(deltaTime: Float)
    external fun nativeGetMatrix(): FloatArray
    external fun nativeGetPivotScreenOffset(): FloatArray
}
