package android.opengl

object Matrix {
    @JvmStatic fun setIdentityM(m: FloatArray, offset: Int) {}
    @JvmStatic fun translateM(m: FloatArray, offset: Int, x: Float, y: Float, z: Float) {}
    @JvmStatic fun scaleM(m: FloatArray, offset: Int, x: Float, y: Float, z: Float) {}
    @JvmStatic fun multiplyMM(out: FloatArray, o: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {}
    @JvmStatic fun multiplyMV(out: FloatArray, o: Int, a: FloatArray, ao: Int, v: FloatArray, vo: Int) {}
    @JvmStatic fun invertM(out: FloatArray, o: Int, m: FloatArray, mo: Int): Boolean = true
}
