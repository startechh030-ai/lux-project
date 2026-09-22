package android.content

import android.content.res.Resources

open class Context {
    open val resources: Resources = Resources()
    open val packageName: String = "luxe.texture3d.app"
    open fun getString(resId: Int): String = ""
    open fun getSystemService(name: String): Any? = null
}
