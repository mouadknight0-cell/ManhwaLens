package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.manhwalens.app.ocr.geometry.Rect2D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manhwalens.app.R
import androidx.compose.ui.res.stringResource
import com.manhwalens.app.ui.theme.ManhwaLensTheme
import com.manhwalens.app.util.PipelineTracer
import com.manhwalens.app.util.SpanTrace
import com.manhwalens.app.util.StageTrace

/**
 * Debug panel: a small, NON-blocking overlay shown after every tap when
 * Settings → Debug mode is on. Lists the 7 pipeline stages with status,
 * duration and detail so the user can screenshot it. Tap anywhere on the
 * panel to dismiss it. Never shows the API key (details are scrubbed in
 * PipelineTracer.record).
 */
class DebugPanelWindow(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var root: ComposeView? = null
    private var overlayOwner: OverlayLifecycleOwner? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * @param liveLines Part D: machine-readable Live diagnostics (frames/s, verdict, stash
     *                  state, projection/prefs, last patch-removal reason). Never page text.
     */
    fun show(stages: List<StageTrace>, liveStatus: String? = null, liveLines: List<String>? = null) {
        main.post {
            hide()
            // Fresh owner every show, in the required order.
            val owner = OverlayLifecycleOwner()
            owner.onCreate()
            android.util.Log.i("ManhwaLens", "debug panel: creating view (fresh OverlayLifecycleOwner)")
            val view = ComposeView(context)
            view.attachOverlayOwners(owner)
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            view.setContent {
                ManhwaLensTheme {
                    DebugPanel(
                        stages = stages,
                        spans = PipelineTracer.spanSnapshot(),
                        liveStatus = liveStatus,
                        liveLines = liveLines,
                        onDismiss = { hide() }
                    )
                }
            }
            owner.onResume()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 48
            }
            try {
                windowManager.addView(view, params)
                root = view
                overlayOwner = owner
                android.util.Log.i("ManhwaLens", "debug panel: attached to WindowManager")
            } catch (e: SecurityException) {
                owner.onDestroy()
                android.util.Log.e("ManhwaLens", "debug panel: addView denied (overlay permission revoked?) — ${e.message}", e)
            } catch (e: WindowManager.BadTokenException) {
                owner.onDestroy()
                android.util.Log.e("ManhwaLens", "debug panel: addView bad token — ${e.message}", e)
            }
        }
    }

    fun hide() {
        main.post {
            val r = root
            root = null
            if (r != null) {
                runCatching { windowManager.removeView(r) }
                    .onFailure { android.util.Log.w("ManhwaLens", "debug panel: removeView ignored (already removed?)", it) }
                android.util.Log.i("ManhwaLens", "debug panel: removed from WindowManager")
            }
            overlayOwner?.onDestroy()
            overlayOwner = null
        }
    }

    /** The panel's on-screen rect in FRAME (captured-bitmap) pixels, or null when not showing. */
    fun screenRect(): Rect2D? = root?.let { view ->
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        Rect2D(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat())
    }
}

@Composable
private fun DebugPanel(
    stages: List<StageTrace>,
    spans: List<SpanTrace>,
    liveStatus: String?,
    liveLines: List<String>?,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xEE14141D))
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.debug_panel_title),
                    color = Color(0xFFFFC45E),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Box(modifier = Modifier.clickable { onDismiss() }) {
                    Text(
                        text = "\u00D7",
                        color = Color(0xFFFFC45E),
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }
            if (!liveStatus.isNullOrBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = liveStatus,
                    color = Color(0xFF9B8CFF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (!liveLines.isNullOrEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "— live loop —",
                    color = Color(0xFF8F8CA6),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                liveLines.forEach { line ->
                    Text(
                        text = line,
                        color = Color(0xFF7FD8FF),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3
                    )
                }
            }
            Spacer(Modifier.size(6.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                stages.forEach { s ->
                    val color = when (s.status) {
                        StageTrace.Status.OK -> Color(0xFF4ADE80)
                        StageTrace.Status.FAILED -> Color(0xFFFF8A80)
                        StageTrace.Status.SKIPPED -> Color(0xFFA9A6BC)
                    }
                    Text(
                        text = s.line(),
                        color = color,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 5
                    )
                    Spacer(Modifier.size(4.dp))
                }
                if (spans.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "— waiting / sub-steps —",
                        color = Color(0xFF8F8CA6),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.size(4.dp))
                    spans.forEach { span ->
                        Text(
                            text = span.line(),
                            color = Color(0xFFB9B6CE),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3
                        )
                        Spacer(Modifier.size(2.dp))
                    }
                }
            }
        }
    }
}
