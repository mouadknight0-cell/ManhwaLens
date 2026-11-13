package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.manhwalens.app.ocr.geometry.Rect2D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.manhwalens.app.ui.theme.ManhwaLensTheme

/**
 * Lightweight non-focusable pill pinned to the top of the screen:
 * indeterminate progress + message while a capture/translation runs, or a
 * red-ish error message that auto-dismisses. One pill per process.
 */
class StatusPillWindow(private val context: Context, private val windowManager: WindowManager) {

    private var root: ComposeView? = null
    private var overlayOwner: OverlayLifecycleOwner? = null
    private val main = Handler(Looper.getMainLooper())
    private var autoHide: Runnable? = null

    private var state by mutableStateOf(PillState.HIDDEN)
    private var message by mutableStateOf("")

    enum class PillState { HIDDEN, LOADING, ERROR }    fun showLoading(text: String) {
        show(PillState.LOADING, text, autoHideMs = 0)
    }

    fun showError(text: String, autoHideMs: Long = 2600L) {
        show(PillState.ERROR, text, autoHideMs)
    }

    private fun show(state: PillState, text: String, autoHideMs: Long) {
        main.post {
            this.state = state
            this.message = text
            ensureView()
            autoHide?.let { main.removeCallbacks(it) }
            if (autoHideMs > 0) {
                autoHide = Runnable { hide() }.also { main.postDelayed(it, autoHideMs) }
            } else {
                autoHide = null
            }
        }
    }

    fun hide() {
        main.post {
            autoHide?.let { main.removeCallbacks(it) }
            autoHide = null
            state = PillState.HIDDEN
            root?.let { view ->
                runCatching { windowManager.removeView(view) }
                    .onFailure { android.util.Log.w("ManhwaLens", "pill: removeView ignored (already removed?)", it) }
                android.util.Log.i("ManhwaLens", "pill: removed from WindowManager")
            }
            root = null
            overlayOwner?.onDestroy()
            overlayOwner = null
        }
    }

    /** The pill's on-screen rect in FRAME (captured-bitmap) pixels, or null when not showing. */
    fun screenRect(): Rect2D? = root?.let { view ->
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        Rect2D(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat())
    }

    private fun ensureView() {
        if (root != null) return
        // Fresh owner every show (never reuse destroyed owners). Required order:
        // owner.onCreate -> attach owners -> strategy -> setContent -> onResume -> addView.
        val owner = OverlayLifecycleOwner()
        owner.onCreate()
        android.util.Log.i("ManhwaLens", "pill: creating view (fresh OverlayLifecycleOwner)")
        val view = ComposeView(context)
        view.attachOverlayOwners(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            ManhwaLensTheme {
                Pill(state = state, message = message)
            }
        }
        owner.onResume()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * context.resources.displayMetrics.density).toInt()
        }
        try {
            windowManager.addView(view, params)
            root = view
            overlayOwner = owner
            android.util.Log.i("ManhwaLens", "pill: attached to WindowManager")
        } catch (e: SecurityException) {
            owner.onDestroy()
            overlayOwner = null
            android.util.Log.e("ManhwaLens", "pill: addView denied (overlay permission revoked?) — ${e.message}", e)
            root = null
        } catch (e: WindowManager.BadTokenException) {
            owner.onDestroy()
            overlayOwner = null
            android.util.Log.e("ManhwaLens", "pill: addView bad token — ${e.message}", e)
            root = null
        }
    }
}

@Composable
private fun Pill(state: StatusPillWindow.PillState, message: String) {
    if (state == StatusPillWindow.PillState.HIDDEN) return
    val bg = if (state == StatusPillWindow.PillState.ERROR) Color(0xE6B3261E) else Color(0xE622222E)
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(percent = 50))
            .widthIn(max = 340.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (state == StatusPillWindow.PillState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFB8A9FF)
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}
