package com.manhwalens.app.data

/**
 * Pure-JVM type-checked readers for SharedPreferences values (unit-testable).
 *
 * SharedPreferences stores typed primitives; reading a key with the WRONG getter
 * (e.g. getInt() on a value stored as String) throws ClassCastException at
 * runtime. These helpers accept any stored value and fall back to the default
 * instead of throwing, and they tolerate numeric widening/narrowing
 * (Int↔Long↔Float↔Double) because that is a safe, lossless-enough conversion.
 */
object SafePrefs {

    fun asBoolean(value: Any?, default: Boolean): Boolean = value as? Boolean ?: default

    fun asInt(value: Any?, default: Int): Int = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else default
        is Short -> value.toInt()
        is Float -> value.toInt()
        is Double -> value.toInt()
        else -> default
    }

    fun asLong(value: Any?, default: Long): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Float -> value.toLong()
        is Double -> value.toLong()
        else -> default
    }

    fun asFloat(value: Any?, default: Float): Float = when (value) {
        is Float -> value
        is Double -> value.toFloat()
        is Int -> value.toFloat()
        is Long -> value.toFloat()
        else -> default
    }

    fun asString(value: Any?, default: String): String = value as? String ?: default
}
