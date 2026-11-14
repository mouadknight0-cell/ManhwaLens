package com.manhwalens.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks "any ManhwaLens activity is started" in-process (no IPC, no permissions).
 *
 * Registered once from [com.manhwalens.app.ManhwaLensApp.onCreate]. Live mode consults
 * [isAppVisible] before deciding to capture: our own screens are never translatable
 * pages, and the floating patch overlay must not cover them.
 *
 * v0.9.5 (Part 1a) — self-healing:
 *  - the STARTED activities themselves are tracked (weak refs), so the counter can be
 *    verified against ground truth instead of being trusted blindly;
 *  - `onActivityDestroyed` repairs a missing `onActivityStopped` (HyperOS process restore,
 *    a translucent activity killed mid-transition, a crash inside onStop) — the classic way
 *    this counter got stuck above 0 and paused Live forever;
 *  - [isAppVisibleSelfHealing] reconciles the counter before answering and logs it, so a
 *    stuck counter shows up in logcat, the gate line and the self-check screen;
 *  - `register` is idempotent (a second registration would double-count every activity).
 *
 * Thread-safe; callbacks are posted on the main thread by the system.
 */
object AppVisibilityTracker {

    private const val TAG = "ManhwaLens"

    private val state = AppVisibility.shared
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val registered = AtomicBoolean(false)

    /** Activities we counted as started and have not seen stopped yet. */
    private val startedActivities = CopyOnWriteArrayList<WeakReference<Activity>>()

    fun register(app: Application) {
        if (!registered.compareAndSet(false, true)) {
            Log.w(TAG, "AppVisibilityTracker.register called twice — ignored (counter would double-count)")
            return
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += WeakReference(activity)
                if (state.onActivityStarted()) dispatch(true)
            }

            override fun onActivityStopped(activity: Activity) {
                forget(activity)
                if (state.onActivityStopped()) dispatch(false)
            }

            override fun onActivityDestroyed(activity: Activity) {
                // Self-heal: an activity destroyed WITHOUT a stop callback would leave the
                // counter above 0 forever → Live paused with no visible reason.
                if (forget(activity)) {
                    val wasVisible = state.isVisible()
                    if (state.onActivityStopped()) dispatch(false)
                    Log.w(
                        TAG,
                        "visibility heal: ${activity.javaClass.simpleName} destroyed without onStop " +
                            "(counter was ${state.value() + 1}, now ${state.value()}; visibleBefore=$wasVisible)"
                    )
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /** Raw counter — the value Live used to trust blindly. */
    fun counterValue(): Int = state.value()

    /** Ground truth: how many activities we really see started (collected refs pruned). */
    fun actualStartedCount(): Int {
        prune()
        return startedActivities.size
    }

    /**
     * Visibility WITH the safety valve: if the counter says visible but nothing is actually
     * started, the counter is reset (and the heal logged) before answering. This is what the
     * live gate calls — a stuck counter can no longer pause Live forever.
     */
    fun isAppVisibleSelfHealing(): Boolean {
        val actual = actualStartedCount()
        val before = state.value()
        if (before > 0 && actual <= 0) {
            state.reconcile(0)
            Log.w(TAG, "visibility heal: counter=$before but 0 activities started → reset to 0 (Live resumes)")
            return false
        }
        if (actual > before) {
            state.reconcile(actual)
            Log.w(TAG, "visibility heal: counter=$before but $actual activities started → raised to $actual")
        }
        return state.isVisible()
    }

    /** Unhealed read (kept for callers that only report). */
    fun isAppVisible(): Boolean = state.isVisible()

    /**
     * Force the counter back to ground truth (called by the live gate chain when it proved the
     * counter was stuck). @return the corrected counter value.
     */
    fun healCounter(): Int {
        val actual = actualStartedCount()
        val before = state.value()
        val after = state.reconcile(actual)
        if (before != after) {
            Log.w(TAG, "visibility heal: counter $before → $after (activities really started: $actual)")
        }
        return after
    }

    /** One-line description for the gate / self-check: `counter=1 started=0`. */
    fun describe(): String = "counter=${state.value()} started=${actualStartedCount()}"

    /** Called on the 0↔1 visibility transitions only. */
    fun addVisibilityListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeVisibilityListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    // ------------------------------------------------------------------

    /** @return true when [activity] was still counted as started (i.e. a heal was needed). */
    private fun forget(activity: Activity): Boolean {
        var removed = false
        val it = startedActivities.iterator()
        while (it.hasNext()) {
            val ref = it.next()
            val a = ref.get()
            if (a == null || a === activity) {
                startedActivities.remove(ref)
                if (a === activity) removed = true
            }
        }
        return removed
    }

    /** Drop refs whose activity was collected or is finishing/destroyed. */
    private fun prune() {
        for (ref in startedActivities) {
            val a = ref.get()
            if (a == null || a.isFinishing || a.isDestroyed) startedActivities.remove(ref)
        }
    }

    private fun dispatch(visible: Boolean) {
        for (l in listeners) {
            try {
                l(visible)
            } catch (t: Throwable) {
                // A listener must never crash the lifecycle callback — but it must not be silent either.
                Log.e(TAG, "visibility listener failed — ${t.javaClass.name}: ${t.message}", t)
            }
        }
    }
}
