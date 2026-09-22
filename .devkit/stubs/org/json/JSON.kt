package org.json

class JSONArray {
    constructor()
    constructor(collection: Collection<*>)
    fun length(): Int = 0
    fun optJSONObject(index: Int): JSONObject? = null
    fun optJSONArray(index: Int): JSONArray? = null
    fun optInt(index: Int, fallback: Int): Int = fallback
    fun optInt(index: Int): Int = 0
    fun optLong(index: Int, fallback: Long): Long = fallback
    fun optLong(index: Int): Long = 0L
    fun optDouble(index: Int, fallback: Double): Double = fallback
    fun optDouble(index: Int): Double = 0.0
    fun optString(index: Int, fallback: String): String = fallback
    fun optString(index: Int): String = ""
    fun optBoolean(index: Int, fallback: Boolean): Boolean = fallback
    fun opt(index: Int): Any? = null
    fun getJSONObject(index: Int): JSONObject = JSONObject()
    fun getJSONArray(index: Int): JSONArray = JSONArray()
    fun getString(index: Int): String = ""
    fun getInt(index: Int): Int = 0
    fun getLong(index: Int): Long = 0L
    fun getDouble(index: Int): Double = 0.0
    fun isNull(index: Int): Boolean = true
    fun put(value: Any?): JSONArray = this
    fun put(value: Int): JSONArray = this
    fun put(value: Double): JSONArray = this
    fun put(value: Boolean): JSONArray = this
}

class JSONObject {
    constructor()
    constructor(json: String)
    fun length(): Int = 0
    fun has(name: String): Boolean = false
    fun isNull(name: String): Boolean = true
    fun opt(name: String): Any? = null
    fun optJSONArray(name: String): JSONArray? = null
    fun optJSONObject(name: String): JSONObject? = null
    fun optInt(name: String, fallback: Int): Int = fallback
    fun optInt(name: String): Int = 0
    fun optInt(index: Int, fallback: Int): Int = fallback
    fun optLong(name: String, fallback: Long): Long = fallback
    fun optDouble(name: String, fallback: Double): Double = fallback
    fun optString(name: String, fallback: String): String = fallback
    fun optString(name: String): String = ""
    fun optBoolean(name: String, fallback: Boolean): Boolean = fallback
    fun get(name: String): Any = Any()
    fun getJSONArray(name: String): JSONArray = JSONArray()
    fun getJSONObject(name: String): JSONObject = JSONObject()
    fun getString(name: String): String = ""
    fun getInt(name: String): Int = 0
    fun put(name: String, value: Any?): JSONObject = this
    fun put(name: String, value: Int): JSONObject = this
    fun put(name: String, value: Long): JSONObject = this
    fun put(name: String, value: Double): JSONObject = this
    fun put(name: String, value: Boolean): JSONObject = this
    fun putOpt(name: String, value: Any?): JSONObject = this
    fun remove(name: String): Any? = null
    fun keys(): Iterator<String> = emptyList<String>().iterator()
    override fun toString(): String = "{}"
    companion object { @JvmField val NULL: Any = Any() }
}
