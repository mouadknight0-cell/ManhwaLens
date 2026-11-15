package com.manhwalens.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.manhwalens.app.overlay.OverlayLifecycleOwner
import com.manhwalens.app.overlay.attachOverlayOwners
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manhwalens.app.R
import com.manhwalens.app.render.AutoFitTextSizer
import com.manhwalens.app.render.BubbleRenderBox
import com.manhwalens.app.render.StaticTextMeasurer
import com.manhwalens.app.render.computeContentRect
import com.manhwalens.app.data.SettingsPrefs
import com.manhwalens.app.ui.theme.ManhwaLensTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen overlay window for one captured page.
 *
 * The frame is shown with each detected bubble covered by its Arabic
 * translation: panel background sampled from inside the bubble, font
 * auto-sized to the box, RTL shaping with the bundled Cairo family. Footer
 * toggles between the original frame and the Arabic rendering, shows capture
 * diagnostics, and carries a close action. Tap the frame to dismiss.
 *
 * The window is non-focusable: the manhwa reader keeps keyboard & focus.
 */
class CaptureDebugWindow(private val context: Context, private val windowManager: WindowManager) {

    private var root: ComposeView? = null
    private var overlayOwner: OverlayLifecycleOwner? = null
    private var shownBitmap: Bitmap? = null
    private val metaState = androidx.compose.runtime.mutableStateOf<CaptureMeta?>(null)
    private val frameState = androidx.compose.runtime.mutableStateOf<Bitmap?>(null)
    private val arabicState = androidx.compose.runtime.mutableStateOf(true)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** @return true when the overlay window was attached, false otherwise. Must be called on the main thread. */
    fun show(bitmap: Bitmap, meta: CaptureMeta, touchable: Boolean = true): Boolean {
        com.manhwalens.app.util.MainThread.assertDebug("CaptureDebugWindow.show")
        hide()

        // STEP: fresh owner every show (never reuse a destroyed one) — create,
        // restore, attach owners, THEN set composition, THEN resume, THEN addView.
        val owner = OverlayLifecycleOwner()
        owner.onCreate()
        android.util.Log.i("ManhwaLens", "translation overlay: creating view (fresh OverlayLifecycleOwner)")
        val composeView = ComposeView(context)
        composeView.attachOverlayOwners(owner)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        metaState.value = meta
        frameState.value = bitmap
        arabicState.value = true
        composeView.setContent {
            val bmp = frameState.value ?: return@setContent
            val current = metaState.value ?: return@setContent
            ManhwaLensTheme {
                DebugCaptureScreen(
                    bitmap = bmp,
                    meta = current,
                    arabicState = arabicState,
                    onDismiss = { hide() }
                )
            }
        }
        owner.onResume()

        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            // Live mode: touches must pass through to the reader underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        return try {
            windowManager.addView(composeView, params)
            root = composeView
            overlayOwner = owner
            shownBitmap = bitmap
            android.util.Log.i("ManhwaLens", "translation overlay: attached to WindowManager")
            true
        } catch (e: SecurityException) {
            destroyOwner(composeView, owner)
            android.util.Log.e("ManhwaLens", "translation overlay: addView denied (overlay permission revoked?) — ${e.message}", e)
            false
        } catch (e: WindowManager.BadTokenException) {
            destroyOwner(composeView, owner)
            android.util.Log.e("ManhwaLens", "translation overlay: addView bad token — ${e.message}", e)
            false
        } catch (e: IllegalArgumentException) {
            destroyOwner(composeView, owner)
            android.util.Log.e("ManhwaLens", "translation overlay: addView rejected — ${e.message}", e)
            false
        }
    }

    private fun destroyOwner(view: ComposeView?, owner: OverlayLifecycleOwner?) {
        // Never reuse the owner: detach already happened or never did.
        runCatching { view?.let { windowManager.removeView(it) } }
        if (view != null) {
            android.util.Log.i("ManhwaLens", "translation overlay: removed from WindowManager (cleanup)")
        }
        owner?.onDestroy()
    }

    /**
     * Progressive display: swap in better translations without re-attaching the window.
     * Safe from any thread — the state swap is marshalled to the main thread.
     */
    fun updateBoxes(boxes: List<BubbleRenderBox>, engine: String, ms: Long, hits: Int, misses: Int) {
        com.manhwalens.app.util.MainThread.assertDebug("CaptureDebugWindow.updateBoxes")
        com.manhwalens.app.util.MainThread.run {
            val current = metaState.value ?: return@run
            metaState.value = current.copy(
                bubbleBoxes = boxes,
                translateEngine = engine,
                translateMs = ms,
                cacheHits = hits,
                cacheMisses = misses
            )
        }
    }

    /** Live mode: full frame swap (bitmap + boxes + engine label) without re-attaching. Safe from any thread. */
    fun updateFrame(
        bitmap: Bitmap,
        boxes: List<BubbleRenderBox>,
        engine: String,
        ms: Long,
        hits: Int,
        misses: Int
    ) {
        com.manhwalens.app.util.MainThread.assertDebug("CaptureDebugWindow.updateFrame")
        com.manhwalens.app.util.MainThread.run {
            val current = metaState.value
            if (current == null) {
                // Window not attached (e.g. user closed it) — fall back to recycling.
                runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
            } else {
                val oldBitmap = shownBitmap
                shownBitmap = bitmap
                frameState.value = bitmap
                metaState.value = current.copy(
                    width = bitmap.width,
                    height = bitmap.height,
                    bubbleBoxes = boxes,
                    translateEngine = engine,
                    translateMs = ms,
                    cacheHits = hits,
                    cacheMisses = misses
                )
                root?.visibility = View.VISIBLE
                if (oldBitmap != null && oldBitmap !== bitmap) {
                    mainHandler.postDelayed({ runCatching { if (!oldBitmap.isRecycled) oldBitmap.recycle() } }, 150L)
                }
            }
        }
    }

    /** Live mode: hide/show the overlay WITHOUT detaching the window (INVISIBLE skips drawing). Safe from any thread. */
    fun setOverlayVisible(visible: Boolean) {
        com.manhwalens.app.util.MainThread.assertDebug("CaptureDebugWindow.setOverlayVisible")
        com.manhwalens.app.util.MainThread.run {
            root?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * Live mode: flip between original frame and Arabic boxes.
     * @return the new state, or null when no overlay with boxes is attached.
     */
    fun toggleArabicOverlay(): Boolean? {
        com.manhwalens.app.util.MainThread.assertDebug("CaptureDebugWindow.toggleArabicOverlay")
        val current = metaState.value ?: return null
        if (current.bubbleBoxes.isEmpty() || root == null) return null
        arabicState.value = !arabicState.value
        return arabicState.value
    }

    /** Whether the overlay is currently shown with at least one translated box. */
    fun hasTranslationBoxes(): Boolean =
        (metaState.value?.bubbleBoxes?.isNotEmpty() == true) && root != null

    fun hide() {
        com.manhwalens.app.util.MainThread.assertDebug("CaptureDebugWindow.hide")
        com.manhwalens.app.util.MainThread.run { hideImpl() }
    }

    private fun hideImpl() {
        metaState.value = null
        frameState.value = null
        arabicState.value = true
        val r = root
        root = null
        if (r != null) {
            runCatching { windowManager.removeView(r) }
                .onFailure { android.util.Log.w("ManhwaLens", "translation overlay: removeView ignored (already removed?)", it) }
            android.util.Log.i("ManhwaLens", "translation overlay: removed from WindowManager")
        }
        overlayOwner?.onDestroy()
        overlayOwner = null
        // Recycle the frame AFTER the window is gone; wait one extra frame so
        // Compose/Skia can't reference the pixels (no recycled-bitmap crash).
        val bmp = shownBitmap
        shownBitmap = null
        if (bmp != null) {
            mainHandler.postDelayed({
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
            }, 150L)
        }
    }
}

private val Cairo = FontFamily(
    Font(R.font.cairo, FontWeight.Normal),
    Font(R.font.cairo_bold, FontWeight.Bold)
)

@Composable
private fun DebugCaptureScreen(
    bitmap: Bitmap,
    meta: CaptureMeta,
    arabicState: androidx.compose.runtime.MutableState<Boolean>,
    onDismiss: () -> Unit
) {
    val showArabic by arabicState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2070710))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { if (!meta.liveMode) onDismiss() }
        ) {
            FrameWithOverlays(
                bitmap = bitmap,
                boxes = meta.bubbleBoxes,
                showArabic = showArabic
            )
        }

        if (!meta.liveMode) {
            FooterPanel(meta, showArabic, onToggle = { arabicState.value = it }, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun FrameWithOverlays(
    bitmap: Bitmap,
    boxes: List<BubbleRenderBox>,
    showArabic: Boolean
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = remember(context) { StaticTextMeasurer(context) }
    val fontScale = remember(context) { SettingsPrefs(context).fontScale }
    val padDp = 4.dp
    val padPx = with(density) { padDp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerSize = it.size }
    ) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        if (showArabic && containerSize.width > 0 && containerSize.height > 0) {
            val contentRect = computeContentRect(
                containerSize.width.toFloat(),
                containerSize.height.toFloat(),
                bitmap.width,
                bitmap.height
            )
            val fits = remember(boxes, contentRect) {
                boxes.map { b ->
                    val w = ((b.right - b.left) * contentRect.scale - padPx * 2).toInt().coerceAtLeast(4)
                    val h = ((b.bottom - b.top) * contentRect.scale - padPx * 2).toInt().coerceAtLeast(4)
                    AutoFitTextSizer.fit(
                        text = b.arabic,
                        maxWidthPx = w,
                        maxHeightPx = h,
                        minPx = 6f,
                        maxPx = (h * 0.62f).coerceAtLeast(8f),
                        measurer = measurer
                    )
                }
            }

            boxes.forEachIndexed { i, box ->
                val mapped = contentRect.mapBox(box.left, box.top, box.right, box.bottom)
                val wPx = mapped[2] - mapped[0]
                val hPx = mapped[3] - mapped[1]
                if (wPx <= 1f || hPx <= 1f) return@forEachIndexed
                val fit = fits[i]
                val scaledPx = (fit.fontSizePx * fontScale).coerceAtLeast(6f)
                val fontSp = with(density) { scaledPx.toSp() }
                val lineHeightSp = with(density) { (scaledPx * 1.15f).toSp() }
                val widthDp = with(density) { wPx.toDp() }
                val heightDp = with(density) { hPx.toDp() }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(mapped[0].roundToInt(), mapped[1].roundToInt()) }
                        .size(widthDp, heightDp)
                        .clip(RoundedCornerShape(4.dp))
                        .clipToBounds()
                        .background(Color(box.backgroundColor))
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Text(
                            text = box.arabic,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padDp),
                            color = Color(box.textColor),
                            fontFamily = Cairo,
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSp,
                            lineHeight = lineHeightSp,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Clip,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterPanel(
    meta: CaptureMeta,
    showArabic: Boolean,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF14141D))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.debug_overlay_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(text = stringResource(R.string.debug_toggle_original), selected = !showArabic) {
                    onToggle(false)
                }
                ToggleChip(text = stringResource(R.string.debug_toggle_arabic), selected = showArabic) {
                    onToggle(true)
                }
            }
        }

        Spacer(Modifier.size(8.dp))
        val time = remember(meta.capturedAt) {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(meta.capturedAt))
        }
        val tapLine = if (meta.tapToOverlayMs >= 0) " · tap→overlay ${meta.tapToOverlayMs} ms" else ""
        Text(
            text = "$time · ${meta.width}×${meta.height} · capture ${meta.totalMs} ms$tapLine · OCR ${meta.ocrMs} ms · bubbles ${meta.bubbleCount}",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFA9A6BC)
        )
        meta.translateEngine?.let { engine ->
            Text(
                text = "Engine: $engine · ${meta.translateMs} ms · cache ${meta.cacheHits}/${meta.cacheHits + meta.cacheMisses}" +
                    (if (meta.bubbleBoxes.isNotEmpty()) " · overlay boxes ${meta.bubbleBoxes.size}" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFFC45E),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (meta.nearBlack) {
            Text(
                text = stringResource(R.string.debug_secure_warn),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFC45E),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        meta.ocrError?.let { err ->
            Text(
                text = "OCR error: $err",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF8A80),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        meta.translateError?.let { err ->
            Text(
                text = "Translate error: $err",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF8A80),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.size(6.dp))
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.debug_tap_to_close), color = Color(0xFF8B7CF6))
        }
    }
}

@Composable
private fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF8B7CF6) else Color(0xFF23232F)
    val fg = if (selected) Color.White else Color(0xFFA9A6BC)
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = bg,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
