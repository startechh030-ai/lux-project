package android.content.res

class Resources {
    val displayMetrics: DisplayMetrics = DisplayMetrics()
    fun getDimensionPixelSize(id: Int): Int = 0
    fun getDrawable(id: Int): Any? = null
}

class DisplayMetrics {
    var density: Float = 1f
    var widthPixels: Int = 1600
    var heightPixels: Int = 720
}
