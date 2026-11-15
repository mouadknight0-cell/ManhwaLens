package com.manhwalens.app.util

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The single funnel for every background→main-thread hop in overlay/UI code
 * (analysis, OCR, ML Kit callbacks, ImageReader listener and executors all
 * carry non-main threads). Use [run] anywhere a view/window must be touched;
 * heavy work stays off the main thread — only the UI mutation crosses.
 *
 * [run] executes inline when already on the main thread, so it is always safe
 * to call (no re-entrant posting, no behavior change for existing main callers).
 *
 * [assertDebug] logs a full stack trace when called off the main thread in
 * debug builds (debuggable flag is provided once via [init] by the Application).
 * It never throws — the goal is early detection, not another crash class.
 */
object MainThread {

    @Volatile
    private var debuggable: Boolean = false

    /** Called once from Application.attachBaseContext (debuggable flag of THIS build). */
    fun init(debuggable: Boolean) {
        this.debuggable = debuggable
    }

    @Volatile
    private var cachedMainHandler: Handler? = null

    @PublishedApi
    internal fun mainHandler(): Handler =
        cachedMainHandler ?: Handler(Looper.getMainLooper()).also { cachedMainHandler = it }

    /** True on the main thread (identity check against the main looper). */
    fun isMain(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    /** Runs [block] on the main thread — inline if already there, else posted. */
    inline fun run(crossinline block: () -> Unit) {
        if (isMain()) {
            block()
        } else {
            mainHandler().post { block() }
        }
    }

    /** [run] with a guard — a failing block is logged and never crashes the caller's thread. */
    inline fun runSafely(crossinline block: () -> Unit) =
        run {
            try {
                block()
            } catch (t: Throwable) {
                Log.e("ManhwaLens", "main-thread block failed — ${t.javaClass.name}: ${t.message}", t)
            }
        }

    /** Debug-only: logs a stack trace when invoked off the main thread (never throws). */
    fun assertDebug(what: String) {
        if (!debuggable || isMain()) return
        Log.e(
            "ManhwaLens",
            "WRONG THREAD for $what — expected main, got '${Thread.currentThread().name}'",
            Throwable("thread trace")
        )
    }
}
