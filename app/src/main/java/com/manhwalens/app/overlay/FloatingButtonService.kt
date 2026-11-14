package com.manhwalens.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.Image
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.manhwalens.app.ManhwaLensApp
import com.manhwalens.app.R
import com.manhwalens.app.capture.CaptureConsentActivity
import com.manhwalens.app.capture.CaptureDebugWindow
import com.manhwalens.app.capture.CaptureMeta
import com.manhwalens.app.data.OverlayPrefs
import com.manhwalens.app.ocr.BubbleGrouper
import com.manhwalens.app.ocr.DetectedLine
import com.manhwalens.app.ocr.BubbleBlock
import com.manhwalens.app.ocr.TextRecognizerClient
import com.manhwalens.app.util.ErrorLog
import com.manhwalens.app.util.PipelineTracer
import com.manhwalens.app.util.StageTrace
import com.manhwalens.app.ocr.BlockFilter
import com.manhwalens.app.live.LiveFrameMath
import com.manhwalens.app.live.LiveStabilityTracker
import com.manhwalens.app.live.RowHashShift
import com.manhwalens.app.live.argbToLuma
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Single owner of the whole M1+M2 overlay feature set:
 *
 *  - M1: draggable floating lens button (TYPE_APPLICATION_OVERLAY)
 *  - M2: MediaProjection session + single-frame capture on button tap
 *
 * One service holds both so that a tap over ANY app (manhwa reader, browser,
 * home screen) can capture without inter-service IPC or background service
 * starts. On Android 14+ the projection token is per-session: consent is shown
 * on first tap, the session (FGS type mediaProjection) stays alive for
 * subsequent taps, and every new session (after Stop / system revoke /
 * process death) requires a fresh consent — by design.
 *
 * Logging tag: MLCapture
 */
class FloatingButtonService : Service() {

    companion object {
        /** Set while the service is alive; lets the accessibility watcher trigger a capture. */
        @Volatile var instance: FloatingButtonService? = null
            private set

        /**
         * Called by ManhwaAutoCaptureService when the user stops scrolling.
         * Optional EXTRA trigger only — live mode works fully without the accessibility
         * service. With live mode ON this just nudges the supervisor to evaluate the
         * current frame sooner; otherwise it behaves like the classic auto-capture.
         */
        fun requestAutoCapture() {
            val svc = instance ?: return
            val settings = com.manhwalens.app.data.SettingsPrefs(svc)
            if (settings.liveMode) {
                svc.liveHandler.post { svc.liveTick() }
            } else {
                svc.captureFrame()
            }
        }

        const val ACTION_START_WITH_TOKEN = "com.manhwalens.app.action.CAPTURE_TOKEN"
        const val ACTION_STOP = "com.manhwalens.app.action.STOP_CAPTURE"
        /** Part C: notification action — toggles Live (asks for capture consent first). */
        const val ACTION_TOGGLE_LIVE = "com.manhwalens.app.action.TOGGLE_LIVE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        private const val TAG = "MLCapture"
        private const val NOTIF_ID = 2001
        /** Tap path: how long to wait for the mirror's first/next frame when nothing is buffered. */
        private const val FRAME_WAIT_MS = 1_500L
        /** Tap path: how long to wait for the frame that follows hiding our own windows. */
        private const val CLEAN_CAPTURE_TIMEOUT_MS = 800L
        /** Tap path: extra settle time after the first post-hide frame (lets the compositor finish). */
        private const val CLEAN_CAPTURE_SETTLE_MS = 120L
        /** A menu closed less than this long ago may still be in the newest buffered frame. */
        private const val MENU_RECENT_MS = 500L
        /** How long a closed menu's rect still vetoes OCR text in a live frame. */
        private const val MENU_LIVE_GUARD_MS = 3_000L
        /** Live: how often a failed translation may raise a toast, and when the page is retried. */
        private const val LIVE_FAILURE_TOAST_INTERVAL_MS = 15_000L
        private const val LIVE_FAILURE_RETRY_MS = 6_000L
        /** Gemini circuit: short pause for transient failures (timeout / network / parse). */
        /** Live waits this long for a remote result only when the ML Kit draft itself failed. */
        private const val LIVE_REMOTE_WAIT_MS = 32_000L
        /** A finished remote result waits at most this long for its draft paint to be queued. */
        private const val LIVE_UPGRADE_WAIT_MS = 60_000L
        /** Last Settings benchmark report (also appended to the self-check "Copy report"). */
        const val BENCHMARK_REPORT_FILE = "benchmark-last.txt"

        /**
         * Lines appended to "Copy report": ML Kit batching choice, self-test, warm state and
         * the last benchmark table. Static so the report works without a running service.
         */
        fun translationDiagnosticsLines(context: android.content.Context): List<String> {
            val out = ArrayList<String>()
            out += "mlkit batching: " + com.manhwalens.app.translate.TranslatorFactory
                .ensureBatchPolicy(context).describe()
            val selfTest = com.manhwalens.app.translate.mlkit.MlKitTranslator.lastSelfTestSummary
            if (selfTest.isNotEmpty()) out += "mlkit self-test: $selfTest"
            out += "mlkit warmth: " + com.manhwalens.app.translate.MlKitWarmth.describe(System.currentTimeMillis())
            val bench = runCatching {
                java.io.File(context.filesDir, BENCHMARK_REPORT_FILE).takeIf { it.exists() }?.readLines()
            }.getOrNull()
            if (bench.isNullOrEmpty()) {
                out += "benchmark: not run yet (Settings → Diagnostics → Benchmark translation)"
            } else {
                out += "— last benchmark —"
                out += bench.take(40)
            }
            return out
        }
        /** How long a capture waits for a fresh frame after restarting a black mirror. */
        private const val BLACK_REFETCH_WAIT_MS = 700L
        private const val GEMINI_SHORT_PAUSE_MS = 30_000L
        /** Part 2: longer hide timeout for the retry when OCR output looks cut. */
        private const val RETRY_HIDE_TIMEOUT_MS = 1_200L
        /** Part C: the lens menu closes itself after this much inactivity. */
        const val LENS_MENU_AUTO_CLOSE_MS = 4_000L
        /** Part 1: how often the foreground allow-list is re-checked. */
        private const val FOREGROUND_RECHECK_MS = 2_000L
        /** Part 1: battery standby after this long without a frame change. */
        private const val STANDBY_AFTER_MS = 5 * 60_000L
        /** Part D: single 15 s attempt per bubble (never blocks the on-device overlay). */
        const val AI_CLEANUP_READ_TIMEOUT_MS = 15_000
    }

    @Volatile private var lastTapAt = 0L
    @Volatile private var captureQueuedAtMs = 0L
    @Volatile private var consentRequestedAtMs = 0L
    // ---- Persistent capture session: MediaProjection / VirtualDisplay / ImageReader
    // are kept alive for the whole service lifetime instead of re-created per tap.
    private var sessionReader: ImageReader? = null
    private var sessionVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    @Volatile private var latestImage: Image? = null
    private val frameLock = Any()
    private var firstFrameLatch = CountDownLatch(1)
    // ---- Live mode (Part C) ----
    // v0.9.4 Part A: the tick decision logic lives in the pure [LiveLoopDriver]; this
    // service is only its FrameSource (capture pipeline) and Sink (overlay/UI side effects).
    private val liveJobRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var liveLastTranslatedThumb: ByteArray? = null
    private var liveLastTranslatedBoxesAbs: List<com.manhwalens.app.render.BubbleRenderBox> = emptyList()
    /** Size of the last frame converted from the mirror — used for the diff mask. */
    private var liveFrameW = 0
    private var liveFrameH = 0
    /** Part B: Live ON was requested before a session existed; armed once consent lands. */
    @Volatile private var pendingLiveEnable = false
    /** Part D: latest frame-flow diagnostics from the driver. */
    @Volatile private var liveDiagnostics: com.manhwalens.app.live.LiveLoopDriver.Diagnostics? = null
    @Volatile private var liveStarvedLoggedAtMs = 0L
    private var liveScreenOff = false
    private var liveStandby = false
    private var liveStandbyEnteredAtMs = 0L
    private var liveForegroundAllowed = true
    private var liveForegroundDetail = "not checked yet"
    private var liveForegroundPackage: String? = null
    private var lastForegroundCheckMs = 0L
    // ---- v0.9.5 Part 1: gate instrumentation + stuck-flag watchdogs ----
    /** Every early return in the live path is recorded here (rate-limited to 1/s per gate). */
    private val liveGateLog = com.manhwalens.app.live.LiveGateLog { SystemClock.elapsedRealtime() }
    @Volatile private var liveJobStartedAtMs = 0L
    @Volatile private var captureStartedAtMs = 0L
    /** Self-heals applied by the gate chain (shown on the self-check screen). */
    @Volatile private var liveHeals = mutableListOf<String>()
    /** Wire form of the gate that stopped the last tick ("none" when the driver ran). */
    @Volatile private var lastGateWire = com.manhwalens.app.live.LiveGates.GATE_NONE
    @Volatile private var lastGateAction = "no tick yet"
    // ---- v0.9.5 Part 2: self-check telemetry ----
    @Volatile private var sessionCreatedAtMs = 0L
    @Volatile private var firstFrameAtMs = -1L
    @Volatile private var lastOcrLines = -1
    @Volatile private var lastOcrMs = -1L
    @Volatile private var lastOcrAtMs = -1L
    @Volatile private var lastTranslateEngine = "none"
    @Volatile private var lastTranslateMs = -1L
    @Volatile private var lastTranslateError: String? = null
    @Volatile private var lastTranslateAtMs = -1L
    @Volatile private var lastPatchesPainted = 0
    @Volatile private var lastPatchesExpected = 0
    /** Counts of OCR blocks dropped, keyed by drop reason (own-window, garbage, chrome, patch-overlap, …). */
    @Volatile private var ocrDropsByReason = mutableMapOf<String, Int>()
    /** Milliseconds spent waiting for a clean frame after hiding owned windows (last capture). */
    @Volatile private var lastCaptureHideWaitMs = 0L
    /** Android 14+ (U): one VirtualDisplay per MediaProjection token — never create twice. */
    @Volatile private var displayCreatedForProjection = false
    /** Part 3: the tap action to replay once consent lands (menu / translate-now). */
    @Volatile private var pendingTapMode: com.manhwalens.app.live.LiveAutoStart.TapMode? = null
    private var liveNeedsConsentToastShown = false
    @Volatile private var liveLoopActive = false
    private val liveStartLock = Any()
    private val liveTickRunnable = Runnable { liveTick() }
    /**
     * Part A: the live tick runs on its OWN thread. The clean-capture protocol blocks
     * up to 400 ms waiting for the mirror to redraw, and the ImageReader listener is
     * dispatched on [captureHandler] — blocking that handler would deadlock the wait.
     */
    private val liveThread = HandlerThread("manhwalens-live").also { it.start() }
    private val liveHandler = Handler(liveThread.looper)
    private val statusCoalescer = com.manhwalens.app.util.Coalescer<com.manhwalens.app.live.LiveStatus> { drain ->
        // Inline on main (synchronous for main callers), posted otherwise.
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) drain() else mainHandler.post(drain)
    }
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /**
     * v0.9.9: Remote translation requests get their OWN threads so they start at the same moment as the
     * ML Kit draft. They used to queue on [ioExecutor] (single thread) behind pre-warm work.
     */
    private val remoteExecutor = Executors.newFixedThreadPool(2)
    private var statusPill: StatusPillWindow? = null
    @Volatile private var liveStatus: com.manhwalens.app.live.LiveStatus = com.manhwalens.app.live.LiveStatus.PAUSED
    private val liveTickRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---- Part A: frame signal -------------------------------------------------
    // MediaProjection only emits a frame when the screen CHANGES. The clean-capture
    // protocol hides our overlay (a change) and then waits on this condition instead
    // of blindly re-acquiring whatever the reader happens to hold.
    private val frameSignalLock = java.util.concurrent.locks.ReentrantLock()
    private val frameSignal = frameSignalLock.newCondition()
    @Volatile private var frameSignalSeq = 0L
    /** elapsedRealtime of the newest mirror frame (tap clean-capture protocol). */
    @Volatile private var lastFrameArrivedAtMs = 0L
    /** Frame-px rects of OUR windows that may be inside the frame currently being OCR'd (tap path). */
    @Volatile private var tapOwnedRects: List<com.manhwalens.app.ocr.geometry.Rect2D> = emptyList()
    @Volatile private var lastLiveFailureToastAtMs = 0L

    // ---- shared state -------------------------------------------------------
    private lateinit var windowManager: WindowManager
    private var button: LensButtonView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: OverlayPrefs

    // ---- capture state ------------------------------------------------------
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val ocrClient by lazy { TextRecognizerClient() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureInFlight = AtomicBoolean(false)
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private val projectionCallbackHandler = Handler(Looper.getMainLooper())
    private var projectionCallback: android.media.projection.MediaProjection.Callback? = null
    private var patchOverlay: com.manhwalens.app.overlay.PatchOverlayWindow? = null
    private var lensMenuWindow: com.manhwalens.app.overlay.LensMenuWindow? = null
    private var reviewWindow: CaptureDebugWindow? = null
    /** Last tap-capture frame kept ONLY for Review mode (never populated by Live). */
    private var lastReviewFrame: Bitmap? = null
    private var lastReviewMeta: CaptureMeta? = null
    /** Patch rects (frame px) used to mask the previous thumbnail — union keeps the hide-transition comparable. */
    private var lastThumbMaskRects: List<com.manhwalens.app.ocr.geometry.Rect2D> = emptyList()
    // ---- Part 1: owned-window registry (our UI must never be OCR'd / translated) ----
    /** Every window ManhwaLens draws on top of other apps, in CAPTURED-FRAME pixels.
     *  Populated on first use from the live overlay rects. */
    @Volatile private var ownedWindows: com.manhwalens.app.util.OwnedWindows? = null
    private val ownedWindowsLock = Any()

    /** Lazily create the registry, keyed to elapsed-realtime so grace windows work. */
    private fun ownedWindows(): com.manhwalens.app.util.OwnedWindows {
        val ow = ownedWindows
        if (ow != null) return ow
        synchronized(ownedWindowsLock) {
            val ow2 = ownedWindows
            if (ow2 != null) return ow2
            val created = com.manhwalens.app.util.OwnedWindows { SystemClock.elapsedRealtime() }
            ownedWindows = created
            return created
        }
    }

    /** Register every window we own so captures skip them and OCR drops blocks over them. */
    private fun registerOwnedWindows() {
        val ow = ownedWindows()
        ow.clear()
        // Lens button + its live caption (the caption text is drawn on the button view).
        button?.let { b ->
            val r = b.screenRect()
            if (r != null) ow.add("lens-button", r)
            val c = b.captionRect()
            if (c != null) ow.add("lens-caption", c)
        }
        patchOverlay?.let { po ->
            po.rects().forEachIndexed { i, r -> ow.add("patch-$i", r) }
        }
        lensMenuWindow?.screenRect()?.let { ow.add("lens-menu", it) }
        statusPill?.screenRect()?.let { ow.add("status-pill", it) }
    }
    /** Part A: the pure tick engine (created in onCreate; owns the frame stash). */
    private lateinit var liveDriver: com.manhwalens.app.live.LiveLoopDriver<Bitmap>

    override fun onCreate() {
        super.onCreate()
        // Safe Mode (crash-loop guard): no services at all. Exit only via the banner button.
        if (com.manhwalens.app.util.SafeMode.active) {
            Log.w(TAG, "safe mode active — service NOT starting (release via app banner)")
            stopSelf()
            return
        }
        instance = this
        prefs = OverlayPrefs(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        liveDriver = com.manhwalens.app.live.LiveLoopDriver(
            source = LiveFrameSource(),
            sink = LiveDriverSink()
        )
        captureThread = HandlerThread("manhwalens-capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        // FIX: StatusPillWindow used `windowManager` before it was assigned
        // (lateinit) → UninitializedPropertyAccessException killed the process
        // on every toggle-ON. Order now: windowManager → pill → button.
        statusPill = StatusPillWindow(applicationContext, windowManager)
        // Pre-warm: load the OCR recognizer and the ML Kit EN→AR translator
        // (verifying/downloading the one-time ~30 MB model) so the first tap
        // is not slow. Runs on the background executor; failures are non-fatal.
        ioExecutor.execute {
            try {
                preWarmTranslators()
            } catch (t: Throwable) {
                Log.e("ManhwaLens", "pre-warm failed hard — continuing without it", t)
            }
        }
        registerLiveScreenReceiver()
        // The live loop is NOT started here: it begins only once a capture session is
        // armed (first lens tap / granted consent) — see ensureLiveLoopStarted().
        // Button is added ONLY after a successful FGS promotion (see onStartCommand).
        Log.i(TAG, "service created (capture session idle)")
    }

    /** One-time background warm-up of OCR + ML Kit EN→AR (no trace, no UI). */
    private fun preWarmTranslators() {
        val t0 = SystemClock.elapsedRealtime()
        try {
            // 1) OCR recognizer (bundled model — first run loads it)
            val warmBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                val lines = com.manhwalens.app.ocr.TextRecognizerClient().recognize(warmBmp)
                Log.i(TAG, "pre-warm: OCR ready in ${SystemClock.elapsedRealtime() - t0} ms (${lines.lines.size} lines)")
            } finally {
                warmBmp.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "pre-warm: OCR warm-up failed (first tap may be slower) — ${e.javaClass.name}")
        }
        try {
            // 2) ML Kit EN→AR — verify (or download once) the translation model,
            //    through the SAME shared client the pipeline uses.
            //    v0.9.9: warmUp() also runs a dummy "hello" translation (loads the model into
            //    memory) and, on first use, the separator self-test.
            com.manhwalens.app.translate.TranslatorFactory.ensureBatchPolicy(applicationContext)
            val ready = com.manhwalens.app.translate.mlkit.MlKitTranslator.warmUp()
            if (ready) {
                Log.i(TAG, "pre-warm: ML Kit EN→AR ready in ${SystemClock.elapsedRealtime() - t0} ms — " +
                    com.manhwalens.app.translate.MlKitWarmth.describe(System.currentTimeMillis()))
            } else {
                Log.w(TAG, "pre-warm: EN→AR model not ready yet — first translation will retry the download")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "pre-warm: EN→AR model not ready yet (${e.javaClass.simpleName}) — first translation will retry the download")
        }
    }

    private var capturePromoted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Safe Mode (crash-loop guard) takes precedence over any start request.
        if (com.manhwalens.app.util.SafeMode.active) {
            Log.w(TAG, "safe mode active — ignoring start request")
            stopSelf()
            return START_NOT_STICKY
        }
        // 1) Promote FIRST (Android 12+ 5-second rule). If this fails we do NOT
        //    add the overlay button and we leave the toggle OFF.
        val promotionFailure = startForegroundButtonMode()
        if (promotionFailure != null) {
            onForegroundStartFailed(promotionFailure)
            return START_NOT_STICKY
        }
        // 2) Button mode requires the overlay permission.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            onForegroundStartFailed(SecurityException("SYSTEM_ALERT_WINDOW not granted"))
            return START_NOT_STICKY
        }
        addButton()
        // 3) Handle the command.
        when (intent?.action) {
            ACTION_START_WITH_TOKEN -> handleConsentToken(intent)
            ACTION_STOP -> {
                Log.i(TAG, "stop requested via notification action")
                stopEverything()
            }
            ACTION_TOGGLE_LIVE -> {
                val on = !com.manhwalens.app.data.SettingsPrefs(applicationContext).liveMode
                Log.i(TAG, "live toggle requested via notification action (enable=$on)")
                setLiveMode(on)
            }
            else -> Unit
        }
        // Sticky per product decision: MIUI kills plain services; the button
        // must come back. The capture TOKEN is NOT sticky — a sticky restart
        // (null intent) only re-adds the button, never resurrects a projection.
        return START_STICKY
    }

    private fun onForegroundStartFailed(e: Exception) {
        val reason = ErrorLog.reasonOf(e)
        ErrorLog.write(this, "fgs", e)
        Log.e("ManhwaLens", "floating button FGS start failed — button NOT added: $reason", e)
        prefs.enabled = false
        prefs.captureReady = false
        OverlayPrefs.serviceFailure.value = reason
        mainHandler.post {
            Toast.makeText(this, getString(R.string.toast_fgs_failed_reason, reason), Toast.LENGTH_LONG).show()
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** @return null on success, the failure otherwise (caller decides what to do). */
    private fun startForegroundButtonMode(): Exception? {
        if (capturePromoted) return null
        return try {
            val notification = buildCaptureNotification(ready = false, buttonMode = true)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(
                        NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                else ->
                    startForeground(NOTIF_ID, notification)
            }
            Log.i(TAG, "FGS specialUse started (floating button mode)")
            null
        } catch (e: Exception) {
            Log.e(
                "ManhwaLens",
                "startForeground(specialUse) failed — ${e.javaClass.name}: ${e.message}",
                e
            )
            e
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // Stop the live loop FIRST — no callbacks may fire once views start going away.
        liveLoopActive = false
        liveHandler.removeCallbacks(liveTickRunnable)
        statusPill?.hide()
        statusPill = null
        runCatching { ioExecutor.shutdown() }
        runCatching { remoteExecutor.shutdown() }
        patchOverlay?.detach()
        patchOverlay = null
        reviewWindow?.hide()
        reviewWindow = null
        lensMenuWindow?.dismiss()
        lensMenuWindow = null
        synchronized(frameLock) {
            lastReviewFrame?.let { runCatching { if (!it.isRecycled) it.recycle() } }
            lastReviewFrame = null
            lastReviewMeta = null
        }
        runCatching { screenReceiver?.let { applicationContext.unregisterReceiver(it) } }
        screenReceiver = null
        if (::liveDriver.isInitialized) liveDriver.releaseStash()
        releaseCaptureSession("service destroyed")
        releaseProjection("service destroyed")
        button?.let { view -> runCatching { windowManager.removeView(view) } }
        button = null
        liveThread.quitSafely()
        captureThread.quitSafely()
        captureExecutor.shutdown()
        super.onDestroy()
    }

    // =========================================================================
    // M1 — floating button (unchanged behavior)
    // =========================================================================

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            Point(metrics.bounds.width(), metrics.bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val size = Point().also { windowManager.defaultDisplay.getSize(it) }
            size
        }
    }

    private fun addButton() {
        if (button != null) return

        val screen = screenSize()
        val buttonSizePx = (56 * resources.displayMetrics.density).toInt()
        val savedX = prefs.buttonX
        val savedY = prefs.buttonY

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX.coerceIn(0, (screen.x - buttonSizePx).coerceAtLeast(0))
            else screen.x - buttonSizePx
            y = if (savedY >= 0) savedY.coerceIn(0, (screen.y - buttonSizePx).coerceAtLeast(0))
            else screen.y / 3
        }

        val view = LensButtonView(this)
        view.setOnTouchListener(DragToRepositionListener(view))
        // Part B: persisted liveMode=true with no capture session → grey ring + "Tap to start".
        val s = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        if (com.manhwalens.app.live.LiveAutoStart.showsNoSession(mediaProjection != null, s.liveMode)) {
            Log.i(TAG, "live persisted ON but no capture session — lens shows NO_SESSION (tap to start)")
            setLiveStatus(com.manhwalens.app.live.LiveStatus.NO_SESSION)
        } else {
            setLiveStatus(com.manhwalens.app.live.LiveStatus.PAUSED)
        }

        try {
            windowManager.addView(view, layoutParams)
            button = view
        } catch (e: SecurityException) {
            val reason = ErrorLog.reasonOf(e)
            ErrorLog.write(this, "overlay", e)
            Log.e("ManhwaLens", "overlay addView failed — ${e.javaClass.name}: ${e.message}", e)
            prefs.enabled = false
            OverlayPrefs.serviceFailure.value = reason
            stopSelf()
        } catch (e: WindowManager.BadTokenException) {
            val reason = ErrorLog.reasonOf(e)
            ErrorLog.write(this, "overlay", e)
            Log.e("ManhwaLens", "overlay addView bad token — ${e.javaClass.name}: ${e.message}", e)
            prefs.enabled = false
            OverlayPrefs.serviceFailure.value = reason
            stopSelf()
        }
    }

    private fun snapToNearestHorizontalEdge(view: View) {
        val screen = screenSize()
        val targetX = if (layoutParams.x < (screen.x - view.width) / 2) 0 else screen.x - view.width
        layoutParams.x = targetX
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
        prefs.savePosition(layoutParams.x, layoutParams.y)
    }

    private fun clampVertical(view: View) {
        val screen = screenSize()
        val maxY = (screen.y - view.height).coerceAtLeast(0)
        layoutParams.y = layoutParams.y.coerceIn(0, maxY)
    }

    /**
     * Lens tap (Part B + C). Capture consent is checked FIRST: v0.9.3 read the persisted
     * `liveMode` before looking at the projection, so with liveMode=true and no session the
     * tap went to the live handler and nothing happened — consent was never requested.
     */
    private fun onTap() {
        lastTapAt = SystemClock.elapsedRealtime()
        PipelineTracer.reset()
        val tTap = lastTapAt
        val settings = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        val tapMode = com.manhwalens.app.live.LiveAutoStart.TapMode.fromWire(settings.lensTapMode)
        val projectionReady = mediaProjection != null
        val action = com.manhwalens.app.live.LiveAutoStart.tapAction(projectionReady, settings.liveMode, tapMode)
        Log.i(TAG, "lens tapped (projectionActive=$projectionReady liveMode=${settings.liveMode} tapMode=${tapMode.wire}) → $action")
        PipelineTracer.record(PipelineTracer.Stage.TAP, StageTrace.Status.OK, 0L,
            "tap received (projectionActive=$projectionReady liveMode=${settings.liveMode} tapMode=${tapMode.wire}) → ${action.name}")
        when (action) {
            com.manhwalens.app.live.LiveAutoStart.TapAction.CONSENT -> {
                PipelineTracer.record(PipelineTracer.Stage.CONSENT, StageTrace.Status.SKIPPED, 0L,
                    "no projection — consent requested before anything else")
                // Remember BOTH intents: whether Live should be armed once the session exists
                // (auto-arm unless the user explicitly opted out) and what the tap itself wanted
                // (menu vs one-shot), so the flow never dead-ends at the consent dialog.
                pendingLiveEnable = com.manhwalens.app.live.LiveAutoStart.pendingLiveEnableOnTap(
                    liveMode = settings.liveMode,
                    liveOptOut = settings.liveOptOut
                )
                pendingTapMode = tapMode
                gate("projection", "none", "consent requested (pendingLive=$pendingLiveEnable tapMode=${tapMode.wire})")
                launchConsent()
            }
            com.manhwalens.app.live.LiveAutoStart.TapAction.MENU -> {
                PipelineTracer.record(PipelineTracer.Stage.CONSENT, StageTrace.Status.OK, 0L,
                    "projection active — token valid")
                openLensMenu()
            }
            com.manhwalens.app.live.LiveAutoStart.TapAction.TRANSLATE_NOW -> {
                translateNow()
            }
        }
        PipelineTracer.span("tap handling", SystemClock.elapsedRealtime() - tTap,
            "action=${action.name} projectionActive=$projectionReady")
    }

    // =========================================================================
    // M2 — capture session
    // =========================================================================

    /**
     * Keep the mirror pipeline alive for the whole service lifetime. Creating a
     * VirtualDisplay + ImageReader per tap costs real time and forced every capture
     * to wait for a fresh first frame. With a persistent session the tap path is a
     * straight acquire of the newest buffered frame.
     */
    private fun ensureCaptureSession(
        projection: android.media.projection.MediaProjection,
        screen: Point,
        densityDpi: Int
    ) {
        if (sessionVirtualDisplay != null && sessionReader != null) {
            com.manhwalens.app.util.PipelineTracer.span("capture session", 0, "reuse (virtual display alive)")
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        if (displayCreatedForProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: a MediaProjection token allows exactly ONE createVirtualDisplay.
            // A second call throws IllegalStateException and kills the session, so the only
            // legal recovery is a fresh consent (new projection token).
            com.manhwalens.app.util.LiveLog.w(
                TAG,
                "live gate: virtualDisplay = already created on this projection (Android 14+ rule) -> requesting fresh consent"
            )
            launchConsent()
            throw CaptureException(
                "VirtualDisplay already created for this MediaProjection (Android 14+ allows one) — fresh consent requested"
            )
        }
        // Fresh first-frame latch + session telemetry for this session.
        firstFrameLatch = CountDownLatch(1)
        sessionCreatedAtMs = SystemClock.elapsedRealtime()
        firstFrameAtMs = -1L
        val reader = ImageReader.newInstance(screen.x, screen.y, PixelFormat.RGBA_8888, 3)
        sessionReader = reader
        reader.setOnImageAvailableListener({ onFrameAvailable() }, captureHandler)
        val virtualDisplay = projection.createVirtualDisplay(
            "manhwalens-mirror",
            screen.x,
            screen.y,
            densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        ) ?: throw CaptureException("createVirtualDisplay returned null")
        sessionVirtualDisplay = virtualDisplay
        displayCreatedForProjection = true
        virtualDisplayRecreated = false
        com.manhwalens.app.util.PipelineTracer.span(
            "capture session",
            SystemClock.elapsedRealtime() - t0,
            "create (session now persistent for service lifetime)"
        )
        com.manhwalens.app.util.LiveLog.i(
            TAG, "live: capture session created (persistent mirror) display=${screen.x}x${screen.y}"
        )
        // Part 1c: EVERY path that creates the session must start the loop.
        ensureLiveLoopStarted("capture session created")
    }

    /** Drains the reader to the newest image; older buffered frames are closed immediately. */
    private fun onFrameAvailable() {
        val reader = sessionReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            // Reader closed concurrently with a pending frame — nothing to store.
            null
        }
        if (image != null) {
            synchronized(frameLock) {
                latestImage?.close() // keep at most one frame; close it before swapping in
                latestImage = image
                firstFrameLatch.countDown() // idempotent — open the session's first-frame latch
            }
            if (firstFrameAtMs < 0L) {
                firstFrameAtMs = SystemClock.elapsedRealtime()
                val afterSession = if (sessionCreatedAtMs > 0L) firstFrameAtMs - sessionCreatedAtMs else -1L
                com.manhwalens.app.util.LiveLog.i(
                    TAG, "live: first frame received ${afterSession}ms after session start"
                )
            }
            // Part A: wake anyone waiting for a REAL new frame (clean-capture protocol).
            frameSignalLock.lock()
            try {
                lastFrameArrivedAtMs = SystemClock.elapsedRealtime()
                frameSignalSeq++
                frameSignal.signalAll()
            } finally {
                frameSignalLock.unlock()
            }
        }
    }

    /**
     * Create the mirror session (VirtualDisplay + ImageReader) for the current projection if it
     * does not exist yet. Runs on [captureExecutor] — the same single thread the tap capture
     * uses — so the two can never create it twice. No-op without a projection, when the session
     * is alive, or when this token already spent its one display (Android 14+; that case is
     * handled by the no-frame watchdog, which asks for a fresh consent).
     */
    private fun armCaptureSessionAsync(reason: String) {
        if (mediaProjection == null) return
        if (sessionVirtualDisplay != null && sessionReader != null) return
        runCatching {
            captureExecutor.execute {
                val projection = mediaProjection ?: return@execute
                if (sessionVirtualDisplay != null && sessionReader != null) return@execute
                if (displayCreatedForProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    return@execute
                }
                try {
                    ensureCaptureSession(projection, screenSize(), resources.displayMetrics.densityDpi)
                    com.manhwalens.app.util.LiveLog.i(TAG, "live: capture session armed without a tap ($reason)")
                } catch (e: Throwable) {
                    com.manhwalens.app.util.LiveLog.e(TAG, "live: could not arm the capture session ($reason)", e)
                    ErrorLog.write(this, "live-session", e)
                }
            }
        }
    }

    /** Take the freshest buffered frame (null when the session has produced none yet). */
    private fun takeLatestFrame(): Image? =
        synchronized(frameLock) { latestImage?.also { latestImage = null } }

    private fun releaseCaptureSession(reason: String) {
        synchronized(frameLock) {
            latestImage?.close()
            latestImage = null
        }
        runCatching { sessionVirtualDisplay?.release() }
            .onFailure { com.manhwalens.app.util.LiveLog.swallowed("releaseCaptureSession/virtualDisplay", it) }
        sessionVirtualDisplay = null
        runCatching { sessionReader?.close() }
            .onFailure { com.manhwalens.app.util.LiveLog.swallowed("releaseCaptureSession/reader", it) }
        sessionReader = null
        firstFrameLatch = CountDownLatch(1)
        sessionCreatedAtMs = 0L
        firstFrameAtMs = -1L
        com.manhwalens.app.util.LiveLog.i(TAG, "live: capture session released ($reason)")
    }

    /**
     * Shared by tap and live paths: sample each translated bubble's background and
     * produce render-ready overlay boxes. Must run before any annotation touches
     * the pixels.
     */
    /** Sample the dominant bubble interior colour (bubble-sized getPixels — cheap). */
    private fun sampleBackground(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        val l = left.coerceIn(0, bitmap.width); val t = top.coerceIn(0, bitmap.height)
        val r = right.coerceIn(0, bitmap.width); val b = bottom.coerceIn(0, bitmap.height)
        val w = r - l; val h = b - t
        if (w < 2 || h < 2) return 0xFFFFFFFF.toInt()
        return try {
            val region = IntArray(w * h)
            bitmap.getPixels(region, 0, w, l, t, w, h)
            com.manhwalens.app.render.ColorSampler.sample(region, w, h, 0, 0, w, h)
                ?: 0xFFFFFFFF.toInt()
        } catch (e: OutOfMemoryError) {
            Log.e("ManhwaLens", "bubble background sample OOM — white fallback — ${e.javaClass.name}")
            0xFFFFFFFF.toInt()
        }
    }

    private fun sampleBubbleBoxes(
        bitmap: Bitmap,
        ocrBlocks: List<BubbleBlock>,
        arabicById: Map<Int, String>
    ): ArrayList<com.manhwalens.app.render.BubbleRenderBox> {
        val bubbleBoxes = ArrayList<com.manhwalens.app.render.BubbleRenderBox>()
        if (arabicById.isEmpty() || ocrBlocks.isEmpty()) return bubbleBoxes
        try {
            for (block in ocrBlocks) {
                val arabic = arabicById[block.id] ?: continue
                if (arabic.isBlank()) continue
                val r = block.bounds
                val left = r.left.toInt().coerceIn(0, bitmap.width); val top = r.top.toInt().coerceIn(0, bitmap.height)
                val right = r.right.toInt().coerceIn(0, bitmap.width); val bottom = r.bottom.toInt().coerceIn(0, bitmap.height)
                val bg = sampleBackground(bitmap, left, top, right, bottom)
                bubbleBoxes += com.manhwalens.app.render.BubbleRenderBox(
                    id = block.id,
                    left = left, top = top, right = right, bottom = bottom,
                    english = block.text,
                    arabic = arabic,
                    backgroundColor = bg,
                    textColor = com.manhwalens.app.render.ColorSampler.bestTextColorOn(bg)
                )
            }
            Log.i(TAG, "sampled backgrounds for ${bubbleBoxes.size} bubble overlays")
        } catch (e: OutOfMemoryError) {
            Log.e("ManhwaLens", "background sampling OOM — overlay will fall back to white pills — ${e.javaClass.name}", e)
        } catch (e: Exception) {
            Log.e("ManhwaLens", "background sampling failed — ${e.javaClass.name}: ${e.message}", e)
        }
        return bubbleBoxes
    }

    /**
     * Crop status/nav bars from the OCR input (full-frame bitmaps only).
     * Returns the cropped bitmap (recycled by the caller) or the original + 0 offset.
     */
    private fun cropBarsForOcr(bitmap: Bitmap): Pair<Bitmap, Float> {
        val cropTop = statusBarHeightPx()
        val cropBottom = navigationBarHeightPx()
        val canCrop = cropTop + cropBottom > 0 &&
            bitmap.height - cropTop - cropBottom >= 64 &&
            (cropTop + cropBottom) < bitmap.height / 2
        return if (canCrop) {
            val tCrop = SystemClock.elapsedRealtime()
            val cropped = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop - cropBottom)
            com.manhwalens.app.util.PipelineTracer.span(
                "crop bars",
                SystemClock.elapsedRealtime() - tCrop,
                "top=${cropTop}px bottom=${cropBottom}px"
            )
            cropped to cropTop.toFloat()
        } else {
            bitmap to 0f
        }
    }

    /** Downscale so width ≤ [maxWidth]; recycles the original when replaced. */
    private fun capBitmapWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val targetH = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, targetH, true)
        if (scaled !== bitmap) {
            bitmap.recycle()
            Log.i(TAG, "capture downscaled to ${scaled.width}x${scaled.height} (max width $maxWidth)")
        }
        return scaled
    }

    /** Screen on/off — live mode pauses while the screen is off. */
    /**
     * Starts the live supervision loop exactly once, AFTER a capture session is armed.
     * Restores the persisted liveMode (unless the user opted out) — never at app launch.
     */
    private fun ensureLiveLoopStarted(reason: String) {
        if (com.manhwalens.app.util.SafeMode.active) {
            gate("loopStart", "safeMode=true", "loop NOT started", com.manhwalens.app.util.LiveLog::w)
            return
        }
        if (!com.manhwalens.app.live.LiveAutoStart.shouldStartLoop(mediaProjection != null)) {
            gate("loopStart", "projection=none", "loop not started ($reason)", com.manhwalens.app.util.LiveLog::i)
            return
        }
        // v0.9.10: the loop needs a mirror to watch. The VirtualDisplay + ImageReader used to be
        // created ONLY by a tap capture, so "consent → menu → Live ON" (no capture first) left
        // Live with a projection but no display: 0 frames forever (self-check stage 2 FAIL).
        armCaptureSessionAsync(reason)
        // A fresh session ALWAYS re-arms the driver watchdogs — even when the loop is already
        // ticking. v0.9.4 skipped startSession() in that case, so after a consent round-trip
        // the 3 s no-frame watchdog never re-armed and "No frames yet" could never fire again.
        virtualDisplayRecreated = false
        liveDriver.startSession(SystemClock.elapsedRealtime())
        synchronized(liveStartLock) {
            if (liveLoopActive) {
                com.manhwalens.app.util.LiveLog.i(
                    TAG, "live: loop already active ($reason) — driver watchdogs re-armed"
                )
                refreshLiveStatusFromSettings()
                return
            }
            liveLoopActive = true
        }
        liveHandler.removeCallbacks(liveTickRunnable)
        liveHandler.post(liveTickRunnable)
        val s = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        if (com.manhwalens.app.live.LiveAutoStart.shouldRestoreLiveSetting(mediaProjection != null, s.liveMode, s.liveOptOut)) {
            setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
            Log.i(TAG, "live loop started ($reason) — restored liveMode=true")
        } else {
            setLiveStatus(com.manhwalens.app.live.LiveStatus.PAUSED)
            Log.i(TAG, "live loop started ($reason) — live stays paused (liveMode=${s.liveMode}, optOut=${s.liveOptOut})")
        }
    }

    /** Re-apply the persisted Live state to the lens (used when the loop was already running). */
    private fun refreshLiveStatusFromSettings() {
        val s = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        setLiveStatus(
            when {
                !s.liveMode -> com.manhwalens.app.live.LiveStatus.PAUSED
                mediaProjection == null -> com.manhwalens.app.live.LiveStatus.NO_SESSION
                else -> com.manhwalens.app.live.LiveStatus.WAITING
            }
        )
    }

    private fun registerLiveScreenReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        liveScreenOff = true
                        com.manhwalens.app.util.LiveLog.i(TAG, "live gate: screenOff = true -> pausing loop")
                        // Patches must never sit above the lock screen — hide (keep) them.
                        patchOverlay?.setOverlayVisible(false)
                        Log.i(TAG, "live mode: screen off — pausing loop (patches hidden)")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        liveScreenOff = false
                        liveStandby = false
                        liveStandbyEnteredAtMs = 0L
                        liveDriver.reset()
                        mainHandler.post { patchOverlay?.setOverlayVisible(true) }
                        // v0.9.9: the engine may have been paged out while the screen was off —
                        // re-warm it now so the first page after unlock is not a cold call.
                        runCatching {
                            ioExecutor.execute {
                                runCatching {
                                    com.manhwalens.app.translate.mlkit.MlKitTranslator.keepWarm("screen on")
                                }
                            }
                        }
                        Log.i(TAG, "live mode: screen on — resuming loop")
                    }
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }
    }

    /**
     * Part 1b: allow-list check, decided by the pure [ForegroundAppPolicy].
     *
     * v0.9.4 queried only the last 60 s and blocked Live whenever the newest ACTIVITY_RESUMED
     * named a package outside the list — with HyperOS/Brave that event can be minutes old
     * (scrolling emits none), so Live stayed blocked with no visible reason. Now the window is
     * 5 minutes, an event older than [ForegroundAppPolicy.STALE_EVENT_MS] counts as UNKNOWN,
     * and unknown never blocks. The default allow-list is empty (= everything allowed).
     */
    private fun foregroundAppAllowed(allowListRaw: String): ForegroundDecision {
        if (com.manhwalens.app.live.ForegroundAppPolicy.parseList(allowListRaw).isEmpty()) {
            return ForegroundDecision(true, "empty allow-list (everything allowed)", granted = true)
        }
        val granted = usageAccessGranted()
        val usm = applicationContext.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        var lastPackage: String? = null
        var lastEventMs = -1L
        if (granted && usm != null) {
            try {
                val window = com.manhwalens.app.live.ForegroundAppPolicy.QUERY_WINDOW_MS
                val result = usm.queryEvents(now - window, now)
                val event = android.app.usage.UsageEvents.Event()
                while (result != null && result.hasNextEvent()) {
                    if (result.getNextEvent(event) &&
                        (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                            event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND)
                    ) {
                        if (lastEventMs < 0 || event.timeStamp > lastEventMs) {
                            lastEventMs = event.timeStamp
                            lastPackage = event.packageName
                        }
                    }
                }
            } catch (e: Exception) {
                com.manhwalens.app.util.LiveLog.swallowed("foregroundAppAllowed/queryEvents", e)
            }
        }
        // Remember the package for the Live gate input (own-app check).
        liveForegroundPackage = lastPackage

        // Skip translation entirely if the foreground package is our own app —
        // never translate Settings, consent, review, or any ManhwaLens screen.
        if (lastPackage?.equals("com.manhwalens.app", ignoreCase = true) == true) {
            return ForegroundDecision(
                false, "foreground=$lastPackage (own app) — blocking translation", granted
            )
        }

        val age = if (lastEventMs > 0) now - lastEventMs else -1L
        val decision = com.manhwalens.app.live.ForegroundAppPolicy.decide(
            allowListRaw = allowListRaw,
            usageAccessGranted = granted,
            lastResumedPackage = lastPackage,
            lastEventAgeMs = age
        )
        return ForegroundDecision(decision.allowed, decision.reason, granted)
    }

    private data class ForegroundDecision(val allowed: Boolean, val reason: String, val granted: Boolean)

    /** True when the usage-access appops check is MODE_ALLOWED (unknown/throwing → false). */
    private fun usageAccessGranted(): Boolean {
        val appOps = applicationContext.getSystemService(android.content.Context.APP_OPS_SERVICE)
            as? android.app.AppOpsManager ?: return false
        return try {
            val check = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
            }
            check == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            com.manhwalens.app.util.LiveLog.swallowed("usageAccessGranted", e)
            false
        }
    }

    /** Ground truth for the screen state (the broadcast receiver can miss one on HyperOS). */
    private fun isScreenInteractive(): Boolean = try {
        val pm = getSystemService(android.os.PowerManager::class.java)
        pm?.isInteractive ?: true
    } catch (e: Exception) {
        com.manhwalens.app.util.LiveLog.swallowed("screenInteractive", e)
        true
    }

    /**
     * Part A: convert the newest buffered mirror frame into a downscaled bitmap.
     * Returns null when the screen went still — MediaProjection emits frames only on
     * change — and the driver then reuses its stashed thumbnail instead of skipping
     * the tick (that skip was the frame-starvation root cause).
     */
    private fun acquireNewLiveFrame(): Bitmap? {
        val image = takeLatestFrame() ?: return null
        val raw = try {
            imageToBitmap(image)
        } catch (e: Exception) {
            Log.e(TAG, "live frame conversion failed — ${e.javaClass.name}: ${e.message}", e)
            null
        } finally {
            image.close()
        } ?: return null
        return capBitmapWidth(raw, 720).also { noteFrameSize(it) }
    }

    private fun noteFrameSize(bitmap: Bitmap) {
        liveFrameW = bitmap.width
        liveFrameH = bitmap.height
    }

    /**
     * Part A clean-capture wait: block until the mirror delivers a REAL new frame
     * (signalled from [onFrameAvailable]) or [timeoutMs] elapses. Hiding our overlay is
     * itself a screen change, so a frame normally arrives within a few tens of ms.
     *
     * MUST NOT be called on [captureHandler] (that is the thread the ImageReader
     * listener runs on) — the live tick therefore lives on its own [liveHandler].
     */
    private fun awaitNewLiveFrame(timeoutMs: Long): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var seenSeq = frameSignalSeq
        while (true) {
            frameSignalLock.lock()
            try {
                while (frameSignalSeq == seenSeq) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) return null
                    frameSignal.await(remaining, TimeUnit.MILLISECONDS)
                }
                seenSeq = frameSignalSeq
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } finally {
                frameSignalLock.unlock()
            }
            val bitmap = acquireNewLiveFrame()
            if (bitmap != null) return bitmap
            // Another consumer drained the slot (tap capture) — wait for the next signal.
            if (SystemClock.elapsedRealtime() >= deadline) return null
        }
    }

    /** A frame for the tap path + whether it is guaranteed free of our own windows. */
    private class TapFrame(val bitmap: Bitmap, val clean: Boolean)

    /**
     * v0.9.8 tap clean-capture protocol (replaces the old "abort when no new frame" logic,
     * which failed on every static page):
     *  1. hide everything we draw except the lens itself (menu, pill, caption, old patches);
     *  2. if something was hidden — or the menu closed a moment ago — wait for the mirror
     *     frame that FOLLOWS the hide, plus a short settle;
     *  3. take the newest buffered frame. A static page with nothing of ours on it needs no
     *     new frame at all: the buffered one already shows exactly what the user sees.
     * When the post-hide frame never arrives the newest frame is still used, and the rects
     * of what we hid are published in [tapOwnedRects] so OCR drops text under them.
     */
    private fun acquireTapFrame(): TapFrame? {
        val po = patchOverlay
        val hadPatches = po != null && po.hasVisibleOrFading() && po.isOverlayDrawn()
        val menu = lensMenuWindow
        val hadMenu = menu?.isShowing() == true
        val hadPill = statusPill?.screenRect() != null
        val captionLabel = button?.currentCaption
        val hadCaption = captionLabel?.isNotBlank() == true
        val dotState = button?.currentDotState ?: -1

        // Screen-px rects of what is about to disappear (only used if the frame is not clean).
        val hiddenScreenRects = ArrayList<com.manhwalens.app.ocr.geometry.Rect2D>()
        menu?.screenRect()?.let { hiddenScreenRects += it }
        statusPill?.screenRect()?.let { hiddenScreenRects += it }
        button?.captionRect()?.let { hiddenScreenRects += it }
        val oldPatchRects = if (hadPatches) po?.rects() ?: emptyList() else emptyList()

        var hideAt = 0L
        if (hadPatches || hadMenu || hadPill || hadCaption) {
            val hideLatch = CountDownLatch(1)
            mainHandler.post {
                try {
                    if (hadPatches) po?.setOverlayVisible(false)
                    if (hadMenu) menu?.dismiss()
                    if (hadPill) statusPill?.hide()
                    if (hadCaption) button?.setLiveState(dotState, null)
                } finally {
                    hideLatch.countDown()
                }
            }
            try {
                hideLatch.await(300L, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            hideAt = SystemClock.elapsedRealtime()
        }
        // The Capture icon closes the menu BEFORE this runs — the newest buffered frame may
        // still show it, so treat a very recent close exactly like a hide we did ourselves.
        val menuHiddenAt = menu?.lastHiddenAtMs ?: 0L
        if (menuHiddenAt > 0L && SystemClock.elapsedRealtime() - menuHiddenAt < MENU_RECENT_MS) {
            if (menuHiddenAt > hideAt) hideAt = menuHiddenAt
            menu?.lastHiddenRect?.let { hiddenScreenRects += it }
        }

        var clean = true
        val waitStart = SystemClock.elapsedRealtime()
        try {
            if (hideAt > 0L) {
                val deadline = waitStart + CLEAN_CAPTURE_TIMEOUT_MS
                while (lastFrameArrivedAtMs <= hideAt && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        Thread.sleep(15L)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                if (lastFrameArrivedAtMs > hideAt) {
                    try {
                        Thread.sleep(CLEAN_CAPTURE_SETTLE_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                } else {
                    clean = false
                }
            }
            var bitmap: Bitmap? = takeLatestFrame()?.let { image ->
                try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
            }
            var origin = "buffered frame"
            if (bitmap == null) {
                // The live loop drained the mirror slot — its stash is the same screen content.
                bitmap = liveDriver.takeStashedFrameIfClean()
                origin = "live stash"
            }
            if (bitmap == null) {
                bitmap = awaitNewLiveFrame(FRAME_WAIT_MS)
                origin = "waited for a new frame"
            }
            lastCaptureHideWaitMs = SystemClock.elapsedRealtime() - waitStart
            if (bitmap == null) return null

            // Publish the frame-px rects OCR must ignore: always the lens button (it stays on
            // screen), plus everything we hid when the frame could not be proven clean.
            val frameW = minOf(bitmap.width, 720)
            val scale = frameW.toFloat() / screenSize().x.coerceAtLeast(1)
            val rects = ArrayList<com.manhwalens.app.ocr.geometry.Rect2D>()
            fun addScaled(r: com.manhwalens.app.ocr.geometry.Rect2D) {
                rects += com.manhwalens.app.ocr.geometry.Rect2D(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale)
            }
            button?.screenRect()?.let { addScaled(it) }
            if (!clean) {
                hiddenScreenRects.forEach { addScaled(it) }
                rects += oldPatchRects
            }
            tapOwnedRects = rects
            PipelineTracer.span(
                "capture frame", lastCaptureHideWaitMs,
                "source=$origin hidden=${hideAt > 0L} clean=$clean ownedRects=${rects.size}"
            )
            return TapFrame(bitmap, clean)
        } finally {
            mainHandler.post {
                if (hadPatches) {
                    // A new capture replaces the previous page's translation.
                    patchOverlay?.clearPatches("recapture")
                    patchOverlay?.setOverlayVisible(true)
                }
                if (hadCaption && captionLabel != null) button?.setLiveState(dotState, captionLabel)
            }
        }
    }

    /**
     * Hide ALL of our own windows (patch overlay, lens menu, status pill, debug panel,
     * lens caption), wait (bounded) for a frame that cannot contain our own Arabic, then
     * restore everything. Used by the tap path; the live path goes through the driver's
     * clean-capture protocol instead.
     *
     * The rects are registered into [ownedWindows] so post-OCR block filtering can drop
     * anything that still overlaps our windows (defense in depth).
     */
    private fun hideOverlayAndAwaitCleanFrame(timeoutMs: Long): Bitmap? {
        // Register our current rects before hiding, so post-OCR filtering can use them.
        registerOwnedWindows()

        val po = patchOverlay
        val hadPatches = po != null && po.hasVisibleOrFading() && po.isOverlayDrawn()
        val hadMenu = lensMenuWindow?.isShowing() == true
        val hadPill = statusPill?.screenRect() != null
        val hadCaption = button?.currentCaption?.isNotBlank() == true
        val captionLabel = button?.currentCaption
        val dotState = button?.currentDotState ?: -1

        val hideLatch = CountDownLatch(1)
        mainHandler.post {
            try {
                if (hadPatches) po?.setOverlayVisible(false)
                if (hadMenu) lensMenuWindow?.dismiss()
                if (hadPill) statusPill?.hide()
                if (hadCaption) button?.setLiveState(dotState, null)
            } finally {
                hideLatch.countDown()
            }
        }
        val hidden = try {
            hideLatch.await(150L, TimeUnit.MILLISECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!hidden) Log.w(TAG, "owned-window hide timed out after 150 ms — waiting for a clean frame anyway")

        val waitStart = SystemClock.elapsedRealtime()
        return try {
            awaitNewLiveFrame(timeoutMs)
        } finally {
            lastCaptureHideWaitMs = SystemClock.elapsedRealtime() - waitStart
            // Restore everything we hid (the overlay will be re-shown by the caller as needed).
            mainHandler.post {
                if (hadPatches) patchOverlay?.setOverlayVisible(true)
                if (hadCaption && captionLabel != null) {
                    button?.setLiveState(dotState, captionLabel)
                }
            }
        }
    }

    /**
     * Part C: mask out the cells our own patches occupy (union with the PREVIOUS
     * cycle's rects, so a frame captured right after patches disappeared still
     * excludes the just-vacated cells) PLUS the lens menu rect — our own UI must
     * never start a cycle.
     */
    private fun computeThumbMask(): BooleanArray? {
        val w = liveFrameW
        val h = liveFrameH
        if (w <= 0 || h <= 0) return null
        val cur = patchOverlay?.rects() ?: emptyList()
        val combined = ArrayList<com.manhwalens.app.ocr.geometry.Rect2D>(lastThumbMaskRects.size + cur.size + 1)
        combined += lastThumbMaskRects
        combined += cur
        lensMenuWindow?.screenRect()?.let { menu ->
            val screen = screenSize()
            if (screen.x > 0 && screen.y > 0) {
                val sx = w.toFloat() / screen.x
                val sy = h.toFloat() / screen.y
                combined += com.manhwalens.app.ocr.geometry.Rect2D(
                    menu.left * sx, menu.top * sy, menu.right * sx, menu.bottom * sy
                )
            }
        }
        val mask = com.manhwalens.app.live.DiffMask.cellMaskForRects(combined, w, h)
        lastThumbMaskRects = cur
        val active = BooleanArray(mask.size)
        var any = false
        for (i in mask.indices) { if (mask[i]) { active[i] = true; any = true } }
        return if (any) active else null
    }

    private fun thumbnailGray(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            LiveFrameMath.THUMB_W,
            LiveFrameMath.THUMB_H,
            true
        )
        val px = IntArray(LiveFrameMath.THUMB_W * LiveFrameMath.THUMB_H)
        scaled.getPixels(px, 0, LiveFrameMath.THUMB_W, 0, 0, LiveFrameMath.THUMB_W, LiveFrameMath.THUMB_H)
        scaled.recycle()
        return argbToLuma(px)
    }

    /**
     * One `live gate: <name> = <value> -> <action>` line (Part 1). Rate-limited to once per
     * second per gate name by [liveGateLog]; the gate itself is ALWAYS recorded so the status
     * line and the self-check screen show the current one.
     */
    private fun gate(
        name: String,
        value: String,
        action: String,
        log: (String, String) -> Unit = com.manhwalens.app.util.LiveLog::i
    ): com.manhwalens.app.live.LiveGate {
        val g = com.manhwalens.app.live.LiveGate(name, value, action)
        lastGateWire = g.wire()
        lastGateAction = action
        if (liveGateLog.record(g)) log(TAG, g.line())
        return g
    }

    /** Record a gate decision produced by the pure [LiveGates] chain. */
    private fun recordDecision(decision: com.manhwalens.app.live.LiveGates.Decision) {
        val g = decision.gate
        if (g == null) {
            lastGateWire = com.manhwalens.app.live.LiveGates.GATE_NONE
            lastGateAction = "tick ran (driver decision)"
            liveGateLog.record(null)
            return
        }
        lastGateWire = g.wire()
        lastGateAction = g.action
        if (liveGateLog.record(g)) com.manhwalens.app.util.LiveLog.i(TAG, g.line())
    }

    /** Compact tick: cheap when live mode is off; the driver decides everything else. */
    private fun liveTick() {
        if (!liveLoopActive) return
        if (!liveTickRunning.compareAndSet(false, true)) return
        var delay = 1_000L
        try {
            delay = liveTickInner()
        } catch (e: Throwable) {
            Log.e("ManhwaLens", "live tick failed — ${e.javaClass.name}: ${e.message}", e)
            com.manhwalens.app.util.ErrorLog.write(this, "live", e)
            com.manhwalens.app.util.PipelineTracer.span("live error", 0, "live stopped: ${e.javaClass.simpleName}")
            stopLiveAfterError(e)
        } finally {
            liveTickRunning.set(false)
            if (liveLoopActive) liveHandler.postDelayed(liveTickRunnable, delay)
        }
    }

    /** Stop the live loop after a tick failure; persist Live=OFF so it stays off across restarts. */
    private fun stopLiveAfterError(e: Throwable) {
        liveLoopActive = false
        liveHandler.removeCallbacks(liveTickRunnable)
        try {
            com.manhwalens.app.data.SettingsPrefs(applicationContext).apply { liveMode = false }
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "could not persist liveMode=false after live error", t)
        }
        setLiveStatus(com.manhwalens.app.live.LiveStatus.ERROR)
        Log.e("ManhwaLens", "live mode stopped after error — re-enable it manually (reason: ${e.javaClass.simpleName})", e)
    }

    /**
     * One tick of the Live gate chain (v0.9.5 Part 1).
     *
     * Every early return is a NAMED gate: the pure [LiveGates] chain decides, this method
     * applies its self-heals, logs `live gate: <name> = <value> -> <action>` (rate-limited to
     * once per second per gate) and records the gate for the status line + self-check screen.
     * Nothing in the live path may stop silently any more.
     */
    private fun liveTickInner(): Long {
        val settings = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        liveDriver.stableWindowMs = settings.liveStableWindowMs.toLong()
        liveDriver.changeThreshold = settings.liveChangeThreshold()
        val now = SystemClock.elapsedRealtime()

        // Foreground allow-list — a real appops + UsageEvents scan, so at most every 2 s.
        if (now - lastForegroundCheckMs >= FOREGROUND_RECHECK_MS) {
            lastForegroundCheckMs = now
            val allowed = foregroundAppAllowed(settings.liveAllowedApps)
            liveForegroundAllowed = allowed.allowed
            liveForegroundDetail = allowed.reason
        }

        // Battery: stand down after 5 minutes without a frame change (auto-resumes on change).
        // Patches stay up — they are static, tiny and GPU-cheap; removing them after five idle
        // minutes would un-translate a page the user may still be reading.
        val lastChangeAt = liveDriver.lastChangeAtMs
        if (!liveStandby && settings.liveMode && mediaProjection != null &&
            lastChangeAt > 0 && now - lastChangeAt > STANDBY_AFTER_MS
        ) {
            liveStandby = true
            liveStandbyEnteredAtMs = now
            com.manhwalens.app.util.LiveLog.i(
                TAG, "live: ${STANDBY_AFTER_MS / 60_000} min without frame change — entering standby (patches retained)"
            )
            mainHandler.post { Toast.makeText(this, R.string.live_paused_battery, Toast.LENGTH_LONG).show() }
        }

        val decision = com.manhwalens.app.live.LiveGates.evaluate(
            com.manhwalens.app.live.LiveGates.Input(
                liveMode = settings.liveMode,
                projectionReady = mediaProjection != null,
                screenOff = liveScreenOff,
                // Only pay the PowerManager call when the flag claims the screen is off.
                screenInteractive = if (liveScreenOff) isScreenInteractive() else true,
                appVisibleCounter = com.manhwalens.app.util.AppVisibilityTracker.counterValue(),
                appActuallyStarted = com.manhwalens.app.util.AppVisibilityTracker.actualStartedCount(),
                standby = liveStandby,
                standbyEnteredAtMs = liveStandbyEnteredAtMs,
                lastChangeAtMs = lastChangeAt,
                foregroundAllowed = liveForegroundAllowed,
                foregroundDetail = liveForegroundDetail,
                foregroundPackage = liveForegroundPackage,
                captureInFlight = captureInFlight.get(),
                captureStartedAtMs = captureStartedAtMs,
                liveJobRunning = liveJobRunning.get(),
                liveJobStartedAtMs = liveJobStartedAtMs,
                nowMs = now
            )
        )

        applyGateHeals(decision.heals)
        recordDecision(decision)

        return when (decision.outcome) {
            com.manhwalens.app.live.LiveGates.Outcome.RUN ->
                // v0.9.12: never feed the driver a frame that shows our own menu.
                if (lensMenuOnGlass()) 250L else
                // Part A: the driver ticks even when the mirror delivered nothing — it reuses
                // the stashed thumbnail so Changing → Settling → Stable advances with time.
                liveDriver.tick()

            com.manhwalens.app.live.LiveGates.Outcome.STANDBY -> standbyProbeTick()

            com.manhwalens.app.live.LiveGates.Outcome.WAIT -> decision.delayMs

            com.manhwalens.app.live.LiveGates.Outcome.PAUSE -> {
                onLivePaused(decision.gate)
                decision.delayMs
            }
        }
    }

    /** Side effects of a paused tick: the toast/status the user sees + patch cleanup. */
    private fun onLivePaused(gate: com.manhwalens.app.live.LiveGate?) {
        when (gate?.name) {
            com.manhwalens.app.live.LiveGates.GATE_PROJECTION -> {
                if (!liveNeedsConsentToastShown) {
                    liveNeedsConsentToastShown = true
                    mainHandler.post { Toast.makeText(this, R.string.live_needs_capture, Toast.LENGTH_LONG).show() }
                }
                setLiveStatus(com.manhwalens.app.live.LiveStatus.NO_SESSION)
            }
            com.manhwalens.app.live.LiveGates.GATE_APP_VISIBLE,
            com.manhwalens.app.live.LiveGates.GATE_FOREGROUND -> {
                // Never OCR/translate/paint over our own screens or a non-listed app's UI.
                patchOverlay?.clearPatches("app visible")
                setLiveStatus(com.manhwalens.app.live.LiveStatus.PAUSED)
            }
            com.manhwalens.app.live.LiveGates.GATE_LIVE_MODE -> {
                watchTapPatches()
                setLiveStatus(com.manhwalens.app.live.LiveStatus.PAUSED)
            }
            else -> setLiveStatus(com.manhwalens.app.live.LiveStatus.PAUSED)
        }
    }

    private var tapWatchRef: ByteArray? = null
    private var tapWatchEpoch = -1

    /**
     * v0.9.12 — Live is OFF but a tap capture left Arabic patches up. Nothing used to remove
     * them: they stayed glued to the glass while the page scrolled or the user left the reader.
     * Once per paused tick (1 Hz) compare the newest mirror frame with the one seen right after
     * the patches were painted (patch cells and the lens menu excluded); a real change clears
     * the patches. Costs nothing while no patch is showing or the screen is still.
     */
    private fun watchTapPatches() {
        val po = patchOverlay
        if (po == null || !po.hasVisibleOrFading() || mediaProjection == null) {
            tapWatchRef = null
            return
        }
        if (captureInFlight.get()) return          // the tap capture owns the mirror right now
        if (lensMenuWindow?.isShowing() == true) return
        val epoch = po.contentEpoch()
        if (epoch != tapWatchEpoch) {
            tapWatchEpoch = epoch
            tapWatchRef = null
        }
        val frame = acquireNewLiveFrame() ?: return // still screen — nothing moved
        val gray = try {
            thumbnailGray(frame)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { if (!frame.isRecycled) frame.recycle() }
        } ?: return
        val ref = tapWatchRef
        if (ref == null) {
            tapWatchRef = gray
            return
        }
        val diff = com.manhwalens.app.live.DiffMask.meanAbsoluteDifferenceExcluding(ref, gray, computeThumbMask())
        if (com.manhwalens.app.live.TapPatchWatch.shouldClear(diff, liveDriver.changeThreshold)) {
            tapWatchRef = null
            po.clearPatches("page moved (Live off)")
            com.manhwalens.app.util.LiveLog.i(TAG, "tap patches cleared — screen changed (diff=$diff) while Live is OFF")
        }
    }

    /**
     * v0.9.12 — frame-px rects of our own UI that can be inside a LIVE frame: the lens button
     * with its caption, and the menu (open now or closed a moment ago). The live path used to
     * reuse the last TAP capture's rects, so the menu labels and the "Scanning" caption were
     * read, translated and painted as patches.
     */
    private fun liveOwnedRects(frameWidth: Int): List<com.manhwalens.app.ocr.geometry.Rect2D> {
        val scale = frameWidth.toFloat() / screenSize().x.coerceAtLeast(1)
        val pad = 8f * resources.displayMetrics.density
        val out = ArrayList<com.manhwalens.app.ocr.geometry.Rect2D>(3)
        fun add(r: com.manhwalens.app.ocr.geometry.Rect2D) {
            out += com.manhwalens.app.ocr.geometry.Rect2D(
                (r.left - pad) * scale, (r.top - pad) * scale, (r.right + pad) * scale, (r.bottom + pad) * scale
            )
        }
        button?.screenRect()?.let { add(it) }
        button?.captionRect()?.let { add(it) }
        val menu = lensMenuWindow
        (menu?.screenRect() ?: menu?.lastHiddenRect?.takeIf {
            SystemClock.elapsedRealtime() - menu.lastHiddenAtMs < MENU_LIVE_GUARD_MS
        })?.let { add(it) }
        return out
    }

    /** True while the lens menu is on screen or closed less than [MENU_RECENT_MS] ago. */
    private fun lensMenuOnGlass(): Boolean {
        val menu = lensMenuWindow ?: return false
        if (menu.isShowing()) return true
        val hiddenAt = menu.lastHiddenAtMs
        return hiddenAt > 0L && SystemClock.elapsedRealtime() - hiddenAt < MENU_RECENT_MS
    }

    /** Clear the flags the pure gate chain proved to be stuck (Part 1a / 1d / 1e). */
    private fun applyGateHeals(heals: List<com.manhwalens.app.live.LiveGates.Heal>) {
        if (heals.isEmpty()) return
        for (heal in heals) {
            com.manhwalens.app.util.LiveLog.w(TAG, heal.line())
            com.manhwalens.app.util.PipelineTracer.span("live heal", 0, heal.line())
            liveHeals = (liveHeals + heal.line()).takeLast(6).toMutableList()
            when (heal.flag) {
                com.manhwalens.app.live.LiveGates.GATE_APP_VISIBLE ->
                    com.manhwalens.app.util.AppVisibilityTracker.healCounter()
                com.manhwalens.app.live.LiveGates.GATE_SCREEN_OFF -> liveScreenOff = false
                com.manhwalens.app.live.LiveGates.GATE_STANDBY -> resumeFromStandby()
                com.manhwalens.app.live.LiveGates.GATE_CAPTURE_IN_FLIGHT -> {
                    captureInFlight.set(false)
                    captureStartedAtMs = 0L
                }
                com.manhwalens.app.live.LiveGates.GATE_LIVE_JOB -> {
                    liveJobRunning.set(false)
                    liveJobStartedAtMs = 0L
                }
            }
        }
    }

    /** Battery standby: probe for a real change at 1 Hz and resume the moment one lands. */
    private fun standbyProbeTick(): Long {
        val frame = acquireNewLiveFrame()
        if (frame != null) {
            val gray = try {
                thumbnailGray(frame)
            } catch (e: Exception) {
                com.manhwalens.app.util.LiveLog.e(TAG, "live standby thumbnail failed", e)
                null
            }
            if (gray != null && liveDriver.changedSinceLastKnown(gray)) {
                resumeFromStandby()
                setLiveStatus(com.manhwalens.app.live.LiveStatus.SCANNING)
                mainHandler.post { Toast.makeText(this, R.string.live_resumed_toast, Toast.LENGTH_SHORT).show() }
                com.manhwalens.app.util.LiveLog.i(TAG, "live: frame changed — leaving battery standby")
                // Hand the probed frame to the driver: the screen may now be still, so the
                // next tick must not depend on the mirror emitting another one.
                liveDriver.adoptFrame(frame, gray)
                return liveDriver.tick()
            }
            if (gray != null) {
                liveDriver.adoptFrame(frame, gray)
            } else {
                runCatching { if (!frame.isRecycled) frame.recycle() }
                    .onFailure { com.manhwalens.app.util.LiveLog.swallowed("standbyProbe/recycle", it) }
            }
        }
        setLiveStatus(com.manhwalens.app.live.LiveStatus.PAUSED)
        return com.manhwalens.app.live.LiveGates.PAUSE_DELAY_MS
    }

    /** Leave battery standby: forget the old page so the next pass is a fresh attempt. */
    private fun resumeFromStandby() {
        liveStandby = false
        liveStandbyEnteredAtMs = 0L
        liveDriver.resetTracker()
        liveDriver.invalidateSamePage()
    }

    // =========================================================================
    // Part A — the driver's two adapters (capture pipeline in, overlay/UI out)
    // =========================================================================

    /** [LiveLoopDriver.FrameSource] over the MediaProjection mirror + patch overlay. */
    private inner class LiveFrameSource : com.manhwalens.app.live.LiveLoopDriver.FrameSource<Bitmap> {

        override fun acquireNewFrame(): Bitmap? = acquireNewLiveFrame()

        override fun grayOf(frame: Bitmap): ByteArray = thumbnailGray(frame)

        override fun diffMask(): BooleanArray? = computeThumbMask()

        override fun hasPatchesOnScreen(): Boolean = patchOverlay?.hasVisibleOrFading() == true

        override fun patchesBurnedInNow(): Boolean {
            val po = patchOverlay ?: return false
            return po.hasVisibleOrFading() && po.isOverlayDrawn()
        }

        override fun patchCounts() = com.manhwalens.app.live.LiveLoopDriver.PatchCounts(
            visible = patchOverlay?.visiblePatchCount() ?: 0,
            fading = patchOverlay?.fadingPatchCount() ?: 0
        )

        override fun releaseFrame(frame: Bitmap) {
            runCatching { if (!frame.isRecycled) frame.recycle() }
        }

        override fun onShiftPatches(frame: Bitmap, dyThumbnailRows: Int, confidence: Float): Boolean {
            if (dyThumbnailRows == 0) return false
            val dyFrame = -(dyThumbnailRows * frame.height / LiveFrameMath.THUMB_H)
            if (dyFrame == 0) return false
            val moved = patchOverlay?.shiftPatchesBy(dyFrame, frame.height) == true
            if (moved) {
                PipelineTracer.span(
                    "live patch shift", 0,
                    "dy=${dyFrame}px conf=${"%.2f".format(confidence)} — patches moved with scroll"
                )
            }
            return moved
        }

        override fun hideOverlay(): Boolean {
            val po = patchOverlay ?: return true
            if (!po.hasVisibleOrFading()) return true
            val hideLatch = CountDownLatch(1)
            mainHandler.post {
                try {
                    po.setOverlayVisible(false)
                } finally {
                    hideLatch.countDown()
                }
            }
            return try {
                hideLatch.await(150L, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        override fun awaitNewFrame(timeoutMs: Long): Bitmap? =
            awaitNewLiveFrame(timeoutMs)?.also { noteFrameSize(it) }

        override fun showOverlay() {
            mainHandler.post { patchOverlay?.setOverlayVisible(true) }
        }
    }

    /** [LiveLoopDriver.Sink]: every side effect the pure driver decides on. */
    private inner class LiveDriverSink : com.manhwalens.app.live.LiveLoopDriver.Sink<Bitmap> {

        override fun onStatus(status: com.manhwalens.app.live.LiveLoopDriver.DriverStatus) {
            setLiveStatus(
                when (status) {
                    com.manhwalens.app.live.LiveLoopDriver.DriverStatus.SCANNING ->
                        com.manhwalens.app.live.LiveStatus.SCANNING
                    com.manhwalens.app.live.LiveLoopDriver.DriverStatus.WAITING ->
                        com.manhwalens.app.live.LiveStatus.WAITING
                    com.manhwalens.app.live.LiveLoopDriver.DriverStatus.TRANSLATING ->
                        com.manhwalens.app.live.LiveStatus.TRANSLATING
                    com.manhwalens.app.live.LiveLoopDriver.DriverStatus.SKIPPED ->
                        com.manhwalens.app.live.LiveStatus.SKIPPED
                }
            )
        }

        override fun onTranslate(frame: Bitmap, gray: ByteArray, frameOrigin: String) {
            liveJobRunning.set(true)
            liveJobStartedAtMs = SystemClock.elapsedRealtime()
            PipelineTracer.span(
                "live clean frame", 0,
                "source=$frameOrigin stash=${liveDriver.stashState().wire} patches=${patchOverlay?.hasVisibleOrFading() == true}"
            )
            // Part 1d: a rejected task (executor shut down during teardown) used to leave
            // liveJobRunning stuck true forever → the loop returned 300 ms forever, silently.
            try {
                ioExecutor.execute {
                    try {
                        liveTranslate(frame, gray)
                    } catch (t: Throwable) {
                        com.manhwalens.app.util.LiveLog.e(TAG, "live translate crashed", t)
                        ErrorLog.write(this@FloatingButtonService, "live", t)
                        setLiveStatus(com.manhwalens.app.live.LiveStatus.ERROR)
                    } finally {
                        liveJobRunning.set(false)
                        liveJobStartedAtMs = 0L
                    }
                }
            } catch (t: Throwable) {
                liveJobRunning.set(false)
                liveJobStartedAtMs = 0L
                runCatching { if (!frame.isRecycled) frame.recycle() }
                com.manhwalens.app.util.LiveLog.e(TAG, "live job rejected by the executor — flag reset", t)
                gate("liveJobRunning", "rejected(${t.javaClass.simpleName})", "flag reset, frame released")
            }
        }

        override fun onClearPatches(reason: String) {
            patchOverlay?.clearPatches(reason)
        }

        override fun onPatchesMovedOrCleared() {
            // The driver already dropped its same-page memory; keep the mask history honest.
            PipelineTracer.span("live same-page memory", 0, "invalidated (patches shifted or cleared)")
        }

        override fun onNoFramesYet(elapsedMs: Long) = recreateVirtualDisplayOnce(elapsedMs)

        override fun onStarved(msSinceLastFrame: Long, verdict: LiveStabilityTracker.Verdict) {
            val now = SystemClock.elapsedRealtime()
            if (now - liveStarvedLoggedAtMs < 1_000L) return
            liveStarvedLoggedAtMs = now
            val name = verdict.javaClass.simpleName
            Log.w(TAG, "live starved: no new frame for ${msSinceLastFrame}ms (verdict=$name) — reusing the stashed thumbnail")
            PipelineTracer.span("live starved", 0, "no new frame for ${msSinceLastFrame}ms · verdict=$name · stash=${liveDriver.stashState().wire}")
        }

        override fun onCleanCaptureTimeout(consecutiveAttempts: Int) {
            Log.w(TAG, "live clean capture timed out (attempt $consecutiveAttempts) — cycle aborted, retrying next tick")
            PipelineTracer.span(
                "live clean capture", 0,
                "timeout — burned-in stash never OCR'd; retry next tick (attempt $consecutiveAttempts)"
            )
            setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
        }

        override fun onDiagnostics(diagnostics: com.manhwalens.app.live.LiveLoopDriver.Diagnostics) {
            liveDiagnostics = diagnostics
        }

        override fun trace(stage: String, detail: String) {
            PipelineTracer.span(stage, 0, detail)
        }

        override fun onError(what: String) {
            com.manhwalens.app.util.LiveLog.e(TAG, "live driver: $what")
        }

        /** Part 1f: nothing the driver absorbs may vanish — class + message + stack, tag ManhwaLens. */
        override fun onSwallowed(where: String, error: Throwable) {
            com.manhwalens.app.util.LiveLog.swallowed("driver/$where", error)
        }
    }

    /**
     * Part A watchdog / Part 3 (v0.9.5): the mirror produced no frame at all.
     *
     * Android 14+ (API 34) allows exactly ONE `createVirtualDisplay` per MediaProjection
     * token — a second call throws IllegalStateException and burns the session. So the retry
     * RE-ATTACHES the existing display (`resize()` + `setSurface(null)` → `setSurface(surface)`),
     * which is the documented way to restart mirroring on the same token. Only when this
     * projection has never created a display do we create one; if the display object is gone
     * but the token was already used, the only legal recovery is a fresh consent.
     */
    private var virtualDisplayRecreated = false

    private fun recreateVirtualDisplayOnce(elapsedMs: Long) {
        val projection = mediaProjection
        if (projection == null) {
            gate("noFrames", "projection=none (${elapsedMs}ms)", "nothing to re-attach", com.manhwalens.app.util.LiveLog::w)
            return
        }
        // "No frames yet" is visible even if the re-attach already happened once.
        showNoFramesYet()
        if (virtualDisplayRecreated) {
            gate(
                "noFrames", "still none after ${elapsedMs}ms",
                "display already re-attached once this session — waiting", com.manhwalens.app.util.LiveLog::w
            )
            return
        }
        virtualDisplayRecreated = true
        val display = sessionVirtualDisplay
        val surface = sessionReader?.surface
        if (display != null && surface != null) {
            gate("noFrames", "none in ${elapsedMs}ms", "re-attaching the VirtualDisplay (resize + setSurface)")
            captureExecutor.execute {
                try {
                    val screen = screenSize()
                    val dpi = resources.displayMetrics.densityDpi
                    display.resize(screen.x, screen.y, dpi)
                    // Detach + re-attach the SAME surface: forces the mirror to start over
                    // without creating a second VirtualDisplay on this projection token.
                    display.setSurface(null)
                    display.setSurface(surface)
                    firstFrameLatch = CountDownLatch(1)
                    firstFrameAtMs = -1L
                    sessionCreatedAtMs = SystemClock.elapsedRealtime()
                    liveDriver.startSession(SystemClock.elapsedRealtime())
                    com.manhwalens.app.util.LiveLog.i(
                        TAG, "live: VirtualDisplay re-attached (${screen.x}x${screen.y} @$dpi) — waiting for frames again"
                    )
                } catch (e: Exception) {
                    com.manhwalens.app.util.LiveLog.e(TAG, "live: VirtualDisplay re-attach failed — requesting fresh consent", e)
                    ErrorLog.write(this, "live-display", e)
                    requestFreshProjection("virtual display re-attach failed")
                }
            }
            return
        }
        if (displayCreatedForProjection) {
            // The token was already used for its one display and that display is gone.
            gate(
                "noFrames", "none in ${elapsedMs}ms, display lost",
                "Android 14+ single-display rule — requesting fresh consent", com.manhwalens.app.util.LiveLog::w
            )
            requestFreshProjection("virtual display lost on a used projection token")
            return
        }
        if (surface == null) {
            // No session was ever created for this projection — build the whole thing
            // (reader + display) instead of giving up.
            gate("noFrames", "none in ${elapsedMs}ms, no session yet", "creating the capture session", com.manhwalens.app.util.LiveLog::w)
            virtualDisplayRecreated = false
            armCaptureSessionAsync("no-frame watchdog")
            return
        }
        gate("noFrames", "none in ${elapsedMs}ms", "creating the first VirtualDisplay for this projection")
        captureExecutor.execute {
            try {
                val screen = screenSize()
                val dpi = resources.displayMetrics.densityDpi
                val created = projection.createVirtualDisplay(
                    "manhwalens-mirror",
                    screen.x, screen.y, dpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, captureHandler
                )
                if (created == null) {
                    com.manhwalens.app.util.LiveLog.e(TAG, "live: createVirtualDisplay returned null on retry")
                    return@execute
                }
                sessionVirtualDisplay = created
                displayCreatedForProjection = true
                firstFrameLatch = CountDownLatch(1)
                sessionCreatedAtMs = SystemClock.elapsedRealtime()
                firstFrameAtMs = -1L
                liveDriver.startSession(SystemClock.elapsedRealtime())
                // Part 1c: every path that creates the session must (re)start the loop.
                ensureLiveLoopStarted("virtual display created by the no-frame watchdog")
                com.manhwalens.app.util.LiveLog.i(TAG, "live: VirtualDisplay created (${screen.x}x${screen.y}) — waiting for frames")
            } catch (e: Exception) {
                com.manhwalens.app.util.LiveLog.e(TAG, "live: VirtualDisplay create failed — requesting fresh consent", e)
                ErrorLog.write(this, "live-display", e)
                requestFreshProjection("createVirtualDisplay failed")
            }
        }
    }

    /**
     * The current projection token is unusable (its one VirtualDisplay is gone / rejected).
     * Drop it and ask for a new one — on Android 14+ that is the only legal recovery.
     */
    private fun requestFreshProjection(reason: String) {
        com.manhwalens.app.util.LiveLog.w(TAG, "live: releasing the projection ($reason) — a fresh consent is required")
        pendingLiveEnable = com.manhwalens.app.data.SettingsPrefs(applicationContext).liveMode
        releaseProjection(reason)
        setLiveStatus(com.manhwalens.app.live.LiveStatus.NO_SESSION)
        launchConsent()
    }

    /** Grey ring + "No frames yet" caption (Part A watchdog). */
    private fun showNoFramesYet() {
        mainHandler.post {
            button?.setLiveState(
                LensButtonView.LIVE_DOT_NO_SESSION,
                getString(R.string.live_status_no_frames)
            )
        }
    }

    // =========================================================================
    // Part D — Live diagnostics
    // =========================================================================

    /** Machine-readable Live lines for the debug panel (never contains page text). */
    private fun liveDebugLines(): List<String> {
        val settings = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        val d = liveDiagnostics
        val out = ArrayList<String>()
        out += "projection=${if (mediaProjection != null) "active" else "none"}" +
            " display=${if (sessionVirtualDisplay != null) "alive" else "none"}" +
            " recreated=${if (virtualDisplayRecreated) "yes" else "no"}"
        out += "liveMode=${settings.liveMode} liveOptOut=${settings.liveOptOut} pendingLiveEnable=$pendingLiveEnable" +
            " standby=$liveStandby screenOff=$liveScreenOff fgAllowed=$liveForegroundAllowed"
        if (d != null) {
            out += d.lines()
        } else {
            out += "no tick yet (driver idle)"
        }
        out += "overlay: " + (patchOverlay?.lastRemoval() ?: "none") +
            " drawn=${patchOverlay?.isOverlayDrawn() == true}" +
            " arabic=${patchOverlay?.isArabicVisible() == true}"
        lensMenuWindow?.let { out += it.diagnosticsLine() }
        out += "translatedThumb=${if (liveLastTranslatedThumb != null) "yes" else "no"}" +
            " reusedBoxes=${liveLastTranslatedBoxesAbs.size}" +
            " jobRunning=${liveJobRunning.get()}"
        out += "ocrDrops=" + (if (ocrDropsByReason.isEmpty()) "none"
            else ocrDropsByReason.entries.joinToString(",") { "${it.key}=${it.value}" })
        out += " captureHideWaitMs=$lastCaptureHideWaitMs"
        return out
    }

    // =========================================================================
    // Part 2 — Live self-check (Settings → Diagnostics → Live self-check)
    // =========================================================================

    /**
     * Snapshot of every stage the self-check screen shows. Read from the UI thread through
     * [instance]; all fields are volatile/plain reads, no locks, no view access.
     */
    fun liveSelfCheckInput(): com.manhwalens.app.live.LiveSelfCheck.Input {
        val settings = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        val d = liveDiagnostics
        val now = SystemClock.elapsedRealtime()
        val gate = lastGateWire
        return com.manhwalens.app.live.LiveSelfCheck.Input(
            overlayPermission = try {
                android.provider.Settings.canDrawOverlays(this)
            } catch (t: Throwable) {
                com.manhwalens.app.util.LiveLog.swallowed("selfCheck/canDrawOverlays", t); false
            },
            liveMode = settings.liveMode,
            projectionActive = mediaProjection != null,
            sessionExists = sessionVirtualDisplay != null && sessionReader != null,
            sessionAgeMs = if (sessionCreatedAtMs > 0L) now - sessionCreatedAtMs else -1L,
            firstFrameMs = if (firstFrameAtMs > 0L && sessionCreatedAtMs > 0L) firstFrameAtMs - sessionCreatedAtMs else firstFrameAtMs,
            framesPerSecond = d?.framesPerSecond ?: 0f,
            framesTotal = d?.framesReceived ?: 0L,
            idleTicks = d?.ticksWithoutNewFrame ?: 0L,
            frameOrigin = d?.frameOrigin?.wire ?: "none",
            loopActive = liveLoopActive,
            lastGate = gate,
            lastGateAction = lastGateAction,
            gateHits = liveGateLog.summary(),
            verdict = d?.verdict ?: "none",
            msSinceChange = d?.msSinceChange ?: -1L,
            diff = d?.diff ?: 0f,
            stash = liveDriver.stashState().wire,
            ocrLines = lastOcrLines,
            ocrMs = lastOcrMs,
            ocrAgeMs = if (lastOcrAtMs > 0L) now - lastOcrAtMs else -1L,
            translateEngine = lastTranslateEngine,
            translateMs = lastTranslateMs,
            translateError = lastTranslateError,
            translateAgeMs = if (lastTranslateAtMs > 0L) now - lastTranslateAtMs else -1L,
            patchesPainted = lastPatchesPainted,
            patchesExpected = lastPatchesExpected,
            lastRemoval = patchOverlay?.lastRemoval() ?: "none (overlay not attached)",
            liveStatus = liveStatus.wire,
            heals = if (liveHeals.isEmpty()) "" else liveHeals.joinToString(" | ")
        )
    }

    /** Extra state lines for the report (same content as the debug panel). */
    fun liveSelfCheckExtraLines(): List<String> = liveDebugLines()

    /**
     * Plain-text report for "Copy report": the 12 stages + state + the last 60 Live log lines.
     * Logcat is best-effort (no READ_LOGS on modern Android) — our in-memory ring always works.
     */
    fun liveSelfCheckReport(): String {
        val input = liveSelfCheckInput()
        val logLines = ArrayList<String>()
        logLines += com.manhwalens.app.util.LiveLog.recentLines(60)
        logLines += readOwnLogcat(60)
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName ?: "?"} (${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
        }.getOrElse { "? (?)" }
        return com.manhwalens.app.live.LiveSelfCheck.report(
            versionName = version.substringBefore(" ("),
            versionCode = version.substringAfter(" (").removeSuffix(")").toIntOrNull() ?: 0,
            input = input,
            // v0.9.9: batching choice, warm state and the last Settings benchmark ride along.
            extraLines = liveSelfCheckExtraLines() + translationDiagnosticsLines(applicationContext),
            logLines = logLines.takeLast(60)
        )
    }

    /**
     * Best-effort read of OUR OWN logcat lines (an app may always read its own output).
     * Never blocks longer than ~2 s and never throws; returns [] when unavailable.
     */
    private fun readOwnLogcat(maxLines: Int): List<String> {
        return try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "brief", "-t", "${maxLines * 3}", "--pid=$pid")
            )
            val finished = process.waitFor(2L, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                return emptyList()
            }
            process.inputStream.bufferedReader().readLines()
                .filter { it.contains("MLCapture") || it.contains("ManhwaLens") }
                .takeLast(maxLines)
        } catch (t: Throwable) {
            com.manhwalens.app.util.LiveLog.swallowed("readOwnLogcat", t)
            emptyList()
        }
    }

    // ---- self-check telemetry recorders (stages 9-11) ----

    private fun recordOcrResult(lines: Int, ms: Long) {
        lastOcrLines = lines
        lastOcrMs = ms
        lastOcrAtMs = SystemClock.elapsedRealtime()
        com.manhwalens.app.util.LiveLog.i(TAG, "live ocr: $lines lines in ${ms}ms")
    }

    private fun recordTranslateResult(engine: String?, ms: Long, error: String?) {
        lastTranslateEngine = engine ?: "none"
        lastTranslateMs = ms
        lastTranslateError = error
        lastTranslateAtMs = SystemClock.elapsedRealtime()
        com.manhwalens.app.util.LiveLog.i(
            TAG, "live translate: engine=${engine ?: "none"} ${ms}ms" + (error?.let { " error=$it" } ?: "")
        )
    }

    private fun recordPaintResult(painted: Int, expected: Int) {
        lastPatchesPainted = painted
        lastPatchesExpected = expected
        com.manhwalens.app.util.LiveLog.i(TAG, "live overlay: patches painted = $painted of $expected")
    }

    /** One-line Live status for the lens menu / debug panel header (Part 2). */
    fun liveStatusLine(): String {
        val d = liveDiagnostics
        return "Live: ${liveStatus.wire} · gate=${lastGateWire} · src=${d?.frameOrigin?.wire ?: "none"}" +
            " · verdict=${d?.verdict ?: "none"} · stash=${liveDriver.stashState().wire}"
    }

    /**
     * "Translate now" — the one-shot path (Settings → Tap on the lens = Translate now,
     * or the lens-menu row). Works with Live OFF; asks for capture consent first when
     * no session exists (Part B).
     */
    private fun translateNow() {
        if (mediaProjection == null) {
            PipelineTracer.record(PipelineTracer.Stage.CONSENT, StageTrace.Status.SKIPPED, 0L,
                "no projection — consent requested before translate-now")
            // Part 3: after consent the one-shot capture must still happen.
            pendingTapMode = com.manhwalens.app.live.LiveAutoStart.TapMode.TRANSLATE_NOW
            pendingLiveEnable = com.manhwalens.app.data.SettingsPrefs(applicationContext).liveMode
            gate("projection", "none", "consent requested before translate-now")
            launchConsent()
            return
        }
        if (liveStandby) resumeFromStandby()
        if (captureInFlight.get()) {
            PipelineTracer.record(PipelineTracer.Stage.TAP, StageTrace.Status.SKIPPED, 0L,
                "capture already in flight — tap ignored")
            Toast.makeText(this, R.string.toast_capture_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        PipelineTracer.record(PipelineTracer.Stage.CONSENT, StageTrace.Status.OK, 0L,
            "projection active — token valid")
        // The "Capturing…" pill is shown by doCapture AFTER the frame is grabbed — showing it
        // here put our own pill inside the very frame we were about to OCR.
        captureFrame()
    }


    /** Single source of truth for the user-visible live state (button dot + caption + debug panel). */
    private fun setLiveStatus(status: com.manhwalens.app.live.LiveStatus) {
        statusCoalescer.submit(status) { s ->
            if (liveStatus == s) return@submit
            liveStatus = s
            // Coalesced delivery lands on the main thread; the view call is doubly safe.
            button?.setLiveState(
                s.dot,
                if (s == com.manhwalens.app.live.LiveStatus.PAUSED) null else getString(s.captionRes)
            )
            Log.i(TAG, "live status → ${s.wire}")
        }
    }

    /** Wire name for the debug panel header (language-independent, scrub-safe). */
    private fun liveDebugStatusLine(): String = liveStatusLine()

    /**
     * Auto-arms live mode on the first successful capture when the user has not opted
     * out (long-press or Settings). Live runs until paused or the service stops.
     */
    private fun autoArmLiveIfNeeded() {
        val settings = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        if (settings.liveMode || settings.liveOptOut) return
        settings.liveMode = true
        liveStandby = false
        liveScreenOff = false
        liveNeedsConsentToastShown = false
        liveDriver.reset()
        liveForegroundAllowed = true
        setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
        mainHandler.post { Toast.makeText(this, R.string.live_auto_armed, Toast.LENGTH_LONG).show() }
        Log.i(TAG, "live mode auto-armed by first lens tap")
    }

    /** Height of the status bar in px (0 when unknown). */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    /** Height of the navigation bar in px (0 when unknown / gesture-only). */
    private fun navigationBarHeightPx(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun launchConsent() {
        consentRequestedAtMs = SystemClock.elapsedRealtime()
        try {
            startActivity(
                Intent(this, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "capture consent activity launched")
        } catch (e: Exception) {
            ErrorLog.write(this, "consent-launch", e)
            Log.e("ManhwaLens", "unable to launch consent activity — ${e.javaClass.name}: ${e.message}", e)
            com.manhwalens.app.util.MainThread.run {
                Toast.makeText(this, getString(R.string.toast_capture_failed_reason, ErrorLog.reasonOf(e)), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Part C menu row / notification action: stop the loop, release the capture session,
     * remove every overlay and stop the foreground service.
     */
    private fun stopEverything() {
        Log.i(TAG, "stopEverything: live loop, capture session, overlays and FGS are going down")
        liveLoopActive = false
        liveHandler.removeCallbacks(liveTickRunnable)
        pendingLiveEnable = false
        // v0.9.12: Exit means OFF until the user switches the button on again — otherwise
        // opening the app (or a sticky restart) brings the lens straight back.
        prefs.enabled = false
        com.manhwalens.app.util.MainThread.run {
            button?.let { view -> runCatching { windowManager.removeView(view) } }
            button = null
            lensMenuWindow?.dismiss()
            lensMenuWindow = null
            patchOverlay?.clearPatches("stop")
            patchOverlay?.detach()
            patchOverlay = null
            statusPill?.hide()
            reviewWindow?.hide()
        }
        if (::liveDriver.isInitialized) liveDriver.releaseStash()
        releaseProjection("stop requested")
        prefs.captureReady = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProjection(reason: String) {
        releaseCaptureSession(reason)
        val projection = mediaProjection ?: return
        mediaProjection = null
        // The token is gone: a NEW projection may create its own VirtualDisplay again.
        displayCreatedForProjection = false
        virtualDisplayRecreated = false
        sessionCreatedAtMs = 0L
        firstFrameAtMs = -1L
        prefs.captureReady = false
        projectionCallback?.let { cb -> projection.unregisterCallback(cb) }
        projectionCallback = null
        runCatching { projection.stop() }
            .onFailure { Log.w(TAG, "projection.stop() failed ($reason)", it) }
        Log.i(TAG, "projection released ($reason)")
    }

    private fun handleConsentToken(intent: Intent) {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "consent token received (extras present=${intent.extras?.keySet()})")
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            if (consentRequestedAtMs > 0) {
                PipelineTracer.span("consent wait", SystemClock.elapsedRealtime() - consentRequestedAtMs,
                    "dialog rejected/dismissed")
            }
            PipelineTracer.record(PipelineTracer.Stage.CONSENT, StageTrace.Status.FAILED,
                SystemClock.elapsedRealtime() - t0,
                "token rejected (rc=$resultCode, data=${data != null})")
            // Visible failure, not a silent return.
            val synthetic = IllegalStateException("consent token rejected (rc=$resultCode, data=${data != null})")
            ErrorLog.write(this, "consent", synthetic)
            Log.e("ManhwaLens", "consent token rejected by service (rc=$resultCode, data=${data != null})")
            mainHandler.post {
                Toast.makeText(this, R.string.toast_consent_invalid, Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            // Android 14+: FGS of type mediaProjection MUST be foreground before
            // getMediaProjection() is called, and the manifest must declare the type.
            capturePromoted = true
            val tPromote = SystemClock.elapsedRealtime()
            startForegroundCapture()
            Log.i(TAG, "stage=mediaProjection-fgs promoted in ${SystemClock.elapsedRealtime() - tPromote} ms")

            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val tGet = SystemClock.elapsedRealtime()
            val projection = mgr.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("getMediaProjection returned null")
            Log.i(TAG, "stage=getMediaProjection ok in ${SystemClock.elapsedRealtime() - tGet} ms")

            val callback = object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    com.manhwalens.app.util.LiveLog.w(TAG, "live: projection stopped (system chip or revoke)")
                    mediaProjection = null
                    prefs.captureReady = false
                    // Part 1c/3: the VirtualDisplay belonged to that token — drop the whole
                    // session and reset the bookkeeping, otherwise the next consent round-trip
                    // reuses a dead display and Android 14+ refuses a second createVirtualDisplay.
                    displayCreatedForProjection = false
                    virtualDisplayRecreated = false
                    releaseCaptureSession("projection stopped")
                    liveDriver.reset()
                    pendingTapMode = null
                    setLiveStatus(com.manhwalens.app.live.LiveStatus.NO_SESSION)
                    gate("projection", "stopped by system", "session released, NO_SESSION")
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        .onFailure { com.manhwalens.app.util.LiveLog.swallowed("projection.onStop/stopForeground", it) }
                    refreshNotification()
                }
            }
            // Required on API 34: the projection callback MUST be registered
            // before createVirtualDisplay(), which happens later in doCapture().
            projection.registerCallback(callback, projectionCallbackHandler)
            projectionCallback = callback
            mediaProjection = projection
            prefs.captureReady = true

            if (consentRequestedAtMs > 0) {
                PipelineTracer.span("consent wait", SystemClock.elapsedRealtime() - consentRequestedAtMs,
                    "dialog granted -> token forwarded")
            }
            PipelineTracer.record(PipelineTracer.Stage.CONSENT, StageTrace.Status.OK,
                SystemClock.elapsedRealtime() - t0,
                "consent granted — projection acquired, callback registered before virtual display")
            Log.i(TAG, "projection callback registered (before any virtual display); acquired in ${SystemClock.elapsedRealtime() - t0} ms; FGS mediaProjection active")
            showForegroundNotification(ready = true)
            com.manhwalens.app.util.MainThread.run { Toast.makeText(this, R.string.toast_capture_ready, Toast.LENGTH_SHORT).show() }
            ensureLiveLoopStarted("projection armed")
            // Part B/3: consent landed — apply the plan the pure policy computed for the tap
            // that started all this (arm Live, then replay MENU / TRANSLATE_NOW).
            val st = com.manhwalens.app.data.SettingsPrefs(applicationContext)
            val tapMode = pendingTapMode
                ?: com.manhwalens.app.live.LiveAutoStart.TapMode.fromWire(st.lensTapMode)
            val plan = com.manhwalens.app.live.LiveAutoStart.afterConsent(
                tapMode = tapMode,
                requestedLiveEnable = pendingLiveEnable,
                liveMode = st.liveMode,
                liveOptOut = st.liveOptOut
            )
            pendingTapMode = null
            com.manhwalens.app.util.LiveLog.i(TAG, "live: consent granted — after-consent plan ${plan.reason}")
            PipelineTracer.span("live consent", 0, "granted — ${plan.reason}")
            if (plan.enableLive) {
                pendingLiveEnable = false
                st.liveMode = true
                st.liveOptOut = false
                liveStandby = false
                liveStandbyEnteredAtMs = 0L
                liveScreenOff = false
                liveNeedsConsentToastShown = false
                liveForegroundAllowed = true
                liveDriver.reset()
                ensureLiveLoopStarted("consent granted — Live ON")
                setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
                refreshNotification()
            }
            if (plan.openMenu) mainHandler.post { openLensMenu() }
            if (plan.translateNow) mainHandler.post { translateNow() }
        } catch (e: SecurityException) {
            ErrorLog.write(this, "projection", e)
            Log.e("ManhwaLens", "stage=getMediaProjection — ${e.javaClass.name}: ${e.message}", e)
            mediaProjection = null
            prefs.captureReady = false
            capturePromoted = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            mainHandler.post {
                Toast.makeText(this, getString(R.string.toast_capture_failed_reason, "projection · ${ErrorLog.reasonOf(e)}"), Toast.LENGTH_LONG).show()
            }
            stopSelf()
        } catch (e: Exception) {
            ErrorLog.write(this, "projection", e)
            Log.e("ManhwaLens", "stage=projection — ${e.javaClass.name}: ${e.message}", e)
            mediaProjection = null
            prefs.captureReady = false
            capturePromoted = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            mainHandler.post {
                Toast.makeText(this, getString(R.string.toast_capture_failed_reason, "projection · ${ErrorLog.reasonOf(e)}"), Toast.LENGTH_LONG).show()
            }
            stopSelf()
        }
    }

    private fun startForegroundCapture() {
        val notification = buildCaptureNotification(ready = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Log.i(TAG, "FGS mediaProjection started (pending projection acquire)")
    }

    private fun showForegroundNotification(ready: Boolean) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildCaptureNotification(ready))
    }

    /** Re-post the notification so the Live ON/OFF action label matches the current state. */
    private fun refreshNotification() {
        runCatching { showForegroundNotification(ready = prefs.captureReady) }
            .onFailure { Log.w(TAG, "notification refresh failed — ${it.javaClass.simpleName}") }
    }

    private fun buildCaptureNotification(ready: Boolean, buttonMode: Boolean = false): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Part C: "Live ON/OFF" + "Stop" notification actions.
        val liveOn = try {
            com.manhwalens.app.data.SettingsPrefs(applicationContext).liveMode
        } catch (t: Throwable) {
            false
        }
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_TOGGLE_LIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ManhwaLensApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_lens)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(
                getString(
                    if (buttonMode) R.string.notif_capture_button
                    else if (ready) R.string.notif_capture_ready
                    else R.string.notif_capture_starting
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(if (liveOn) R.string.notif_action_live_off else R.string.notif_action_live_on),
                toggleIntent
            )
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun captureFrame() {
        if (!captureInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "capture already in flight; tap ignored")
            return
        }
        captureStartedAtMs = SystemClock.elapsedRealtime()
        // Part C: a capture is starting — the menu must not be on screen (it would be
        // burned into the frame and would read as a page change).
        mainHandler.post { lensMenuWindow?.dismiss() }
        val projection = mediaProjection
        if (projection == null) {
            captureInFlight.set(false)
            Log.w(TAG, "no active projection; launching consent instead")
            launchConsent()
            return
        }
        captureQueuedAtMs = SystemClock.elapsedRealtime()
        // Part 1d: a rejected task must not leave captureInFlight stuck true (the live loop
        // would wait 250 ms forever and the lens would ignore every tap).
        try {
            captureExecutor.execute {
                try {
                    doCapture(projection)
                } catch (t: Throwable) {
                    com.manhwalens.app.util.LiveLog.e(TAG, "doCapture escaped all handlers", t)
                    onCaptureFailure(t, fatal = true, stage = "capture")
                } finally {
                    captureInFlight.set(false)
                    captureStartedAtMs = 0L
                }
            }
        } catch (t: Throwable) {
            captureInFlight.set(false)
            captureStartedAtMs = 0L
            com.manhwalens.app.util.LiveLog.e(TAG, "capture task rejected by the executor — flag reset", t)
            gate("captureInFlight", "rejected(${t.javaClass.simpleName})", "flag reset")
        }
    }

    private fun doCapture(projection: android.media.projection.MediaProjection) {
        // Per-cycle timers start clean: spans/stages accumulate only within this run.
        com.manhwalens.app.util.PipelineTracer.reset()
        val startedAt = SystemClock.elapsedRealtime()
        if (captureQueuedAtMs > 0) {
            PipelineTracer.span("capture queue wait", startedAt - captureQueuedAtMs, "captureExecutor")
        }
        var stage = "screen"
        var captureStageDone = false
        val debugMode = com.manhwalens.app.data.SettingsPrefs(applicationContext).debugMode
        try {
            val screen = screenSize()
            val densityDpi = resources.displayMetrics.densityDpi
            stage = "imageReader"
            Log.i(TAG, "capture start: display=${screen.x}x${screen.y} densityDpi=$densityDpi")

            stage = "session"
            ensureCaptureSession(projection, screen, densityDpi)
            val displayCreatedAt = SystemClock.elapsedRealtime()

            stage = "firstFrame"
            // Wait ONLY when this session has produced no frame yet (first capture after
            // session creation or after a session restart). Otherwise use the freshest
            // buffered frame immediately — this is the big win of the persistent session.
            val tapFrame = acquireTapFrame()
                ?: throw CaptureException("the screen mirror delivered no frame — try again, or re-grant screen capture")
            var bitmap: Bitmap = tapFrame.bitmap
            noteFrameSize(bitmap)
            statusPill?.showLoading(getString(R.string.pill_capturing))
            bitmap = capBitmapWidth(bitmap, 720)
            val origW = bitmap.width
            val origH = bitmap.height
            val firstFrameAt = SystemClock.elapsedRealtime()

            var dark = isNearlyBlack(bitmap)
            if (dark) {
                // Maybe just a stale black first frame — restart the mirror once and look again.
                val fresh = refetchFrameAfterBlack()
                if (fresh != null) {
                    runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                    bitmap = capBitmapWidth(fresh, 720)
                    dark = isNearlyBlack(bitmap)
                }
            }
            val totalMs = SystemClock.elapsedRealtime() - startedAt
            PipelineTracer.record(
                PipelineTracer.Stage.CAPTURE, StageTrace.Status.OK,
                SystemClock.elapsedRealtime() - startedAt,
                "${origW}x${origH}" +
                    (if (bitmap.width != origW || bitmap.height != origH) " → ${bitmap.width}x${bitmap.height} (downscaled)" else "") +
                    " black=$dark [$lastBlackCheck]" + (if (dark) " (FLAG_SECURE?)" else "")
            )
            captureStageDone = true
            Log.i(
                TAG,
                "capture ok: totalMs=$totalMs displayCreateMs=${displayCreatedAt - startedAt} " +
                    "firstFrameWaitMs=${firstFrameAt - displayCreatedAt} " +
                    "bitmap=${bitmap.width}x${bitmap.height} bytes=${bitmap.byteCount} nearBlack=$dark"
            )
            if (dark) {
                // FLAG_SECURE-style black frame: zero page content — skip OCR/translate/overlay.
                Log.w(TAG, "black frame — OCR skipped, nothing drawn")
                PipelineTracer.record(PipelineTracer.Stage.OCR, StageTrace.Status.SKIPPED, 0L,
                    "black frame — OCR skipped (FLAG_SECURE?)")
                PipelineTracer.record(PipelineTracer.Stage.FILTER, StageTrace.Status.SKIPPED, 0L,
                    "skipped — black frame")
                PipelineTracer.record(PipelineTracer.Stage.TRANSLATE, StageTrace.Status.SKIPPED, 0L,
                    "skipped — black frame")
                PipelineTracer.record(PipelineTracer.Stage.OVERLAY, StageTrace.Status.SKIPPED, 0L,
                    "nothing drawn — black frame")
                mainHandler.post { statusPill?.hide() }
                finishPipelineTrace()
                runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                return
            }

            // Write the jpg off the critical path — capture continues straight into OCR.
            val jpgName = "capture_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
            val savedPath = File(cacheDir, jpgName).absolutePath
            val capBitmap = bitmap
            ioExecutor.execute {
                runCatching { saveBitmapToCache(capBitmap, savedPath) }
                    .onFailure { Log.e("ManhwaLens", "failed to save capture to cache — ${it.javaClass.name}: ${it.message}", it) }
            }

            // --- OCR + bubble grouping (Part 2: same clean-capture routine as Live) ----
            stage = "ocr"
            var ocrMs = -1L
            var ocrError: String? = null
            var ocrLineCount = 0
            var ocrBlocks: List<BubbleBlock> = emptyList()
            ocrBlocks = recognizeAndFilterBlocks(bitmap)
            ocrMs = lastOcrMs
            ocrLineCount = lastOcrLines
            // Part 2: retry once after longer hide if OCR output looks cut (incomplete sentence).
            // v0.9.8: the "looks cut" re-capture is disabled — on a static page no new frame ever
            // arrives, so it only added a 1.2 s stall to most captures.
            var ocrRetried = true
            if (ocrBlocks.any { com.manhwalens.app.ocr.CaptureQC.looksCut(it.text.replace("\n", " ").trim()) } && !ocrRetried) {
                ocrRetried = true
                val retryBitmap = hideOverlayAndAwaitCleanFrame(RETRY_HIDE_TIMEOUT_MS)?.also { noteFrameSize(it) }
                if (retryBitmap != null) {
                    val oldBitmap = bitmap
                    runCatching { if (!oldBitmap.isRecycled) oldBitmap.recycle() }
                    bitmap = retryBitmap
                    val retryBlocks = recognizeAndFilterBlocks(bitmap)
                    if (retryBlocks.isNotEmpty()) {
                        ocrBlocks = retryBlocks
                        ocrMs = lastOcrMs
                        ocrLineCount = lastOcrLines
                        ocrError = null
                        PipelineTracer.span("ocr retry", 0, "re-captured after longer hide ($RETRY_HIDE_TIMEOUT_MS ms)")
                    }
                }
            }
            if (ocrError == null) {
                val previews = ocrBlocks.take(6).joinToString("\n") { b ->
                    "  #" + b.id + " \"" + b.text.replace("\n", " ").trim().take(60) + "\""
                }
                val more = if (ocrBlocks.size > 6) "\n  +${ocrBlocks.size - 6} more" else ""
                PipelineTracer.record(
                    PipelineTracer.Stage.OCR, StageTrace.Status.OK, ocrMs.coerceAtLeast(0),
                    "${ocrBlocks.size} blocks / $ocrLineCount lines" + if (previews.isNotEmpty()) "\n$previews$more" else ""
                )
            } else {
                PipelineTracer.record(
                    PipelineTracer.Stage.OCR, StageTrace.Status.FAILED, 0L,
                    "ocr failed: $ocrError"
                )
            }

            // Debug mode: save the frame with the OCR boxes drawn on it.
            if (debugMode) {
                runCatching {
                    val marked = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(marked)
                    val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 5f
                        color = 0xFFFF5252.toInt()
                    }
                    val idPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.FILL
                        color = 0xFFFFD54F.toInt()
                        textSize = 30f
                    }
                    canvas.drawText("ManhwaLens OCR", 12f, 40f, idPaint)
                    ocrBlocks.forEach { b ->
                        val r = b.bounds
                        canvas.drawRect(r.left, r.top, r.right, r.bottom, boxPaint)
                        canvas.drawText("#${b.id}", r.left + 6f, (r.top + 34f).coerceAtMost(marked.height - 6f), idPaint)
                    }
                    val outFile = File(filesDir, "manhwalens-last-capture.jpg")
                    java.io.FileOutputStream(outFile).use { out ->
                        marked.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    marked.recycle()
                    Log.i(TAG, "debug capture saved: ${outFile.absolutePath} (${ocrBlocks.size} boxes)")
                }.onFailure { Log.e("ManhwaLens", "failed to save debug capture", it) }
            }

            // --- Filter (sound effects / watermarks / ignore-list) ----------
            stage = "filter"
            if (ocrError == null) {
                val fStart = SystemClock.elapsedRealtime()
                val edgePrefs = com.manhwalens.app.data.SettingsPrefs(applicationContext)
                val density = resources.displayMetrics.density
                val filterResult = BlockFilter.apply(
                    ocrBlocks, bitmap.height,
                    edgePrefs.ignoreTopPct, edgePrefs.ignoreBottomPct, edgePrefs.ignorePhraseList(),
                    minHeightPx = 16f * density, minWordCount = 1
                )
                val removed = filterResult.removed.joinToString(", ") { "#${it.id}:${it.reason}" }.take(300)
                PipelineTracer.record(
                    PipelineTracer.Stage.FILTER, StageTrace.Status.OK,
                    SystemClock.elapsedRealtime() - fStart,
                    "kept ${filterResult.kept.size}/${ocrBlocks.size}" +
                        if (removed.isNotEmpty()) " · removed: $removed" else " · removed: none"
                )
                ocrBlocks = filterResult.kept

                // ---- Part 1 defense-in-depth: drop anything that is OUR windows ----
                // (blocks intersecting our rects, matching our UI strings, or looking
                // like OCR garbage from burned-in Arabic). Runs AFTER BlockFilter so the
                // trace shows both reasons.
                if (ocrBlocks.isNotEmpty()) {
                    val gStart = SystemClock.elapsedRealtime()
                    val classifier = com.manhwalens.app.ocr.OcrGarbageClassifier(
                        com.manhwalens.app.util.OwnedWindows.UI_STRINGS
                    ) { SystemClock.elapsedRealtime() }
                    // v0.9.8: the frame is captured with our windows hidden, so stale patch /
                    // menu rects must NOT veto real page text (they used to drop every bubble
                    // that sat where an old patch or the menu had been).
                    val patchRects = emptyList<com.manhwalens.app.ocr.geometry.Rect2D>()
                    val ownedRects = tapOwnedRects
                    val before = ocrBlocks.size
                    ocrBlocks = ocrBlocks.filterNot { block ->
                        val v = classifier.classify(
                            block.text.replace('\n', ' ').trim(),
                            block.bounds,
                            patchRects = patchRects,
                            ownedRects = ownedRects
                        )
                        if (!v.keep) {
                            PipelineTracer.span("ocr drop", 0, "#${block.id}=${v.reason}")
                            true
                        } else false
                    }
                    val dropped = before - ocrBlocks.size
                    PipelineTracer.record(
                        PipelineTracer.Stage.FILTER, StageTrace.Status.OK,
                        SystemClock.elapsedRealtime() - gStart,
                        "kept ${ocrBlocks.size}/$before (garbage+own-window drops: $dropped)"
                    )
                }
            } else {
                PipelineTracer.record(
                    PipelineTracer.Stage.FILTER, StageTrace.Status.SKIPPED, 0L,
                    "skipped — OCR produced no usable blocks"
                )
            }

            // --- Translation -------------------------------------------------
            stage = "translation"
            var translateEngine: String? = null
            var translateMs = -1L
            var cacheHits = 0
            var cacheMisses = 0
            var translateError: String? = null
            var translations: List<com.manhwalens.app.translate.TranslatedBlock> = emptyList()
            val pageBlocks = ocrBlocks
                .map { com.manhwalens.app.translate.TranslationBlock(it.id, it.text) }
                .filter { it.english.isNotBlank() }
            // Per-page plan (v0.11.0): meaningless blocks (watermarks, "......", chapter
            // headers, our own UI) are dropped BEFORE the engine and never painted;
            // identical bubble strings are sent ONCE and fanned back out (Part B).
            val dedupePlan = com.manhwalens.app.translate.PagePlan.prepare(
                pageBlocks,
                ownStrings = com.manhwalens.app.util.OwnedWindows.UI_STRINGS
            )
            if (pageBlocks.size != dedupePlan.uniqueCount) {
                com.manhwalens.app.util.PipelineTracer.span(
                    "dedupe", 0, com.manhwalens.app.translate.PagePlan.describe(dedupePlan)
                )
                for ((id, reason) in dedupePlan.dropped) {
                    ocrDropsByReason[reason.name] = (ocrDropsByReason[reason.name] ?: 0) + 1
                    PipelineTracer.span("plan drop", 0, "#$id=$reason")
                }
            }
            // Priority order: short (<80 chars) → largest area → most central first.
            // Drives the engine's per-block call order so the most screenshot-worthy
            // bubbles land first. The painter paints whatever resolves, whenever it does.
            val pageIds = pageBlocks.map { it.id }.toHashSet()
            val keptForPaint = ocrBlocks.filter { it.id in pageIds }
            val priorityIdx = com.manhwalens.app.translate.BlockPriority.orderRects(
                keptForPaint.map { it.text },
                keptForPaint.map { it.bounds.left },
                keptForPaint.map { it.bounds.top },
                keptForPaint.map { it.bounds.right },
                keptForPaint.map { it.bounds.bottom },
                bitmap.width, bitmap.height
            )
            val rankById = HashMap<Int, Int>(priorityIdx.size)
            priorityIdx.forEachIndexed { rank, i -> rankById[keptForPaint[i].id] = rank }
            // PagePlan.engineBlocks keeps each item's FIRST original id, so the engine ids are
            // real block ids and the painter can still map them back to OCR bounds. Priority
            // order therefore sorts the engine blocks by their own rank directly.
            //
            // P2-02: two orders, two purposes. The local progressive pass paints in visual
            // priority order (short → biggest → most central first) so the most useful bubbles
            // land first; every request that may reach a REMOTE engine uses page/reading order,
            // because the remote prompt tells the model that consecutive blocks are consecutive
            // page dialogue and its chunker slices that list sequentially.
            val remoteBlocks = com.manhwalens.app.translate.PagePlan.remoteOrder(dedupePlan)
            val blocksToSend = com.manhwalens.app.translate.PagePlan.progressiveOrder(dedupePlan, rankById)

            // Progressive display (Part C): paint each bubble the moment its Arabic lands.
            val painter: BubblePainter? = if (pageBlocks.isNotEmpty()) {
                BubblePainter(bitmap, keptForPaint, lastTapAt, "ml_kit_en_ar")
            } else null

            var usedProgressiveMlKit = false
            if (pageBlocks.isNotEmpty()) {
                try {
                    val prefsT = com.manhwalens.app.data.TranslationPrefs(applicationContext)
                    val modelBefore = prefsT.geminiModel
                    if (prefsT.engine == com.manhwalens.app.translate.TranslationEngine.GEMINI &&
                        prefsT.geminiCircuit.isOpen()
                    ) {
                        // One-line notice — no Gemini is being tried this capture.
                        mainHandler.post {
                            Toast.makeText(this, R.string.notice_gemini_paused, Toast.LENGTH_LONG).show()
                        }
                    }
                    // Every progressive capture needs the local draft, even when a remote
                    // provider is selected; show the one-time model download on first use.
                    if (!com.manhwalens.app.translate.mlkit.MlKitTranslator.isModelReady()) {
                        // One-time ~30 MB download — make it visible instead of a frozen pill.
                        statusPill?.showLoading(getString(R.string.pill_model_download))
                    }
                    // A configured remote engine runs beside the on-device ML Kit draft. The
                    // local bubbles appear first; only validated remote Arabic can replace them.
                    val useProgressive = com.manhwalens.app.translate.TranslatorFactory
                        .remoteUpgradeAvailable(applicationContext)
                    // Launch the optional remote upgrade NOW — it runs alongside the ML Kit pass.
                    if (useProgressive && painter != null) {
                        val relayPlan = dedupePlan
                        val relayOriginal = pageBlocks
                        val relayUnique = remoteBlocks
                        val relayPainter = painter
                        // Keep remote work on its own executor; model warm-up cannot queue ahead of it.
                        remoteExecutor.execute {
                            relayRemoteTranslation(relayPainter, relayPlan, relayOriginal, relayUnique)
                        }
                    }
                    // v0.9.5 self-check stage 10 also wants ms + error for the TAP path,
                    // and PageTranslation.Failure carries no elapsedMs of its own.
                    val tapTranslateStartedAt = android.os.SystemClock.elapsedRealtime()
                    var tr: com.manhwalens.app.translate.PageTranslation
                    if (painter != null) {
                        // Tap path always paints progressively (ML Kit, priority order).
                        val mlkit = com.manhwalens.app.translate.TranslatorFactory.createProgressive(
                            applicationContext,
                            painter::onBlockReady
                        )
                        Log.i(TAG, "progressive pass: translating ${blocksToSend.size} unique blocks, first bubbles paint as they land")
                        val fast = mlkit.translatePage(blocksToSend)
                        tr = if (fast is com.manhwalens.app.translate.PageTranslation.Success) {
                            usedProgressiveMlKit = useProgressive
                            fast
                        } else {
                            Log.i(TAG, "progressive ML Kit pass failed (${(fast as? com.manhwalens.app.translate.PageTranslation.Failure)?.reason}) — falling back to the normal engine order")
                            com.manhwalens.app.util.PipelineTracer.span(
                                "progressive switch", 0,
                                "ml kit first pass failed — factory order"
                            )
                            // May resolve to a remote engine → page order (P2-02).
                            com.manhwalens.app.translate.TranslatorFactory.create(applicationContext).translatePage(remoteBlocks)
                        }
                    } else if (useProgressive) {
                        val mlkit = com.manhwalens.app.translate.TranslatorFactory.createOffline(applicationContext)
                        val fast = mlkit.translatePage(blocksToSend)
                        tr = if (fast is com.manhwalens.app.translate.PageTranslation.Success) {
                            usedProgressiveMlKit = true
                            fast
                        } else {
                            // May resolve to a remote engine → page order (P2-02).
                            com.manhwalens.app.translate.TranslatorFactory.create(applicationContext).translatePage(remoteBlocks)
                        }
                    } else {
                        Log.i(TAG, "translating ${blocksToSend.size} unique blocks")
                        // May resolve to a remote engine → page order (P2-02).
                        tr = com.manhwalens.app.translate.TranslatorFactory.create(applicationContext).translatePage(remoteBlocks)
                    }
                    when (tr) {
                        is com.manhwalens.app.translate.PageTranslation.Success -> {
                            translateEngine = if (usedProgressiveMlKit) "ml_kit_en_ar (progressive)" else tr.engineId
                            translateMs = tr.elapsedMs
                            cacheHits = tr.cacheHits
                            cacheMisses = tr.cacheMisses
                            recordTranslateResult(translateEngine, tr.elapsedMs, null)
                            translations = com.manhwalens.app.translate.PagePlan.expand(dedupePlan, tr.blocks, pageBlocks.associate { it.id to it.english })
                            Log.i(TAG, "translate ok: engine=${tr.engineId} ms=${tr.elapsedMs} cache=${tr.cacheHits} hits / ${tr.cacheMisses} misses")
                            if (prefsT.geminiModel != modelBefore) {
                                val switched = prefsT.geminiModel
                                mainHandler.post {
                                    Toast.makeText(this, getString(R.string.toast_model_switched, switched), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        is com.manhwalens.app.translate.PageTranslation.Failure -> {
                            Log.e("ManhwaLens", "stage=translation — ${tr.reason}: ${tr.message}")
                            recordTranslateResult(
                                tr.engineId,
                                android.os.SystemClock.elapsedRealtime() - tapTranslateStartedAt,
                                "${tr.reason}: ${tr.message}"
                            )
                            // Remote failure → translate offline for THIS capture only.
                            if (tr.engineId == "gemini" || tr.engineId == "libre_translate") {
                                if (tr.engineId == "gemini") {
                                    // The Gemini circuit prevents repeated slow/key failures.
                                    openGeminiCircuit(tr.reason, tr.message)
                                    Log.i(TAG, "gemini circuit opened after ${tr.reason}")
                                }
                                Log.i(TAG, "${tr.engineId} failed (${tr.reason}); falling back to ML Kit for this capture")
                                val fallback = try {
                                    com.manhwalens.app.translate.mlkit.MlKitTranslator().translatePage(blocksToSend)
                                } catch (e: Exception) {
                                    ErrorLog.write(this, "translate-fallback", e)
                                    Log.e("ManhwaLens", "ml kit fallback crashed — ${e.javaClass.name}: ${e.message}", e)
                                    null
                                }
                                PipelineTracer.span(
                                    "fallback -> ML Kit",
                                    (fallback as? com.manhwalens.app.translate.PageTranslation.Success)?.elapsedMs ?: -1L,
                                    "${tr.engineId} failed: ${tr.reason}"
                                )
                                if (fallback is com.manhwalens.app.translate.PageTranslation.Success) {
                                    recordTranslateResult(
                                        "ml_kit_en_ar (offline fallback)", fallback.elapsedMs, null
                                    )
                                    translateEngine = "ml_kit_en_ar (offline fallback)"
                                    translateMs = fallback.elapsedMs
                                    translations = com.manhwalens.app.translate.PagePlan.expand(dedupePlan, fallback.blocks, pageBlocks.associate { it.id to it.english })
                                    translateError = null
                                    Log.i(TAG, "ml kit fallback ok: ${fallback.blocks.size} blocks in ${fallback.elapsedMs} ms")
                                    mainHandler.post {
                                        Toast.makeText(this, R.string.notice_translated_offline, Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    val fbMsg = (fallback as? com.manhwalens.app.translate.PageTranslation.Failure)
                                        ?.let { fb -> "${fb.reason}: ${fb.message}" } ?: "unknown"
                                    translateError = "${tr.reason}: ${tr.message} (offline fallback also failed: $fbMsg)"
                                    translateEngine = tr.engineId
                                    ErrorLog.write(this, "translate", IllegalStateException(translateError))
                                    statusPill?.showError(getString(R.string.pill_translate_failed))
                                    mainHandler.post {
                                        Toast.makeText(
                                            this,
                                            getString(R.string.toast_capture_failed_reason, "translation · ${tr.reason}: ${tr.message}"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            } else {
                                translateError = "${tr.reason}: ${tr.message}"
                                translateEngine = tr.engineId
                                ErrorLog.write(this, "translate", IllegalStateException(translateError))
                                statusPill?.showError(getString(R.string.pill_translate_failed))
                                mainHandler.post {
                                    Toast.makeText(
                                        this,
                                        getString(R.string.toast_capture_failed_reason, "translation · ${tr.reason}: ${tr.message}"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    translateError = ErrorLog.reasonOf(e)
                    ErrorLog.write(this, "translate", e)
                    recordTranslateResult(translateEngine, translateMs, translateError)
                    com.manhwalens.app.util.LiveLog.swallowed("tap translate", e)
                    Log.e("ManhwaLens", "stage=translation — ${e.javaClass.name}: ${e.message}", e)
                    mainHandler.post {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_capture_failed_reason, "translation · ${ErrorLog.reasonOf(e)}"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            run {
                val sent = dedupePlan.uniqueCount
                val received = translations.count { it.arabic.isNotBlank() }
                val first = translations.firstOrNull { it.arabic.isNotBlank() }?.arabic?.take(60)
                val status = when {
                    sent == 0 -> StageTrace.Status.SKIPPED
                    translateError != null -> StageTrace.Status.FAILED
                    received > 0 -> StageTrace.Status.OK
                    else -> StageTrace.Status.FAILED
                }
                PipelineTracer.record(
                    PipelineTracer.Stage.TRANSLATE, status, translateMs.coerceAtLeast(0),
                    "engine=${translateEngine ?: "none"} sent=$sent received=$received" +
                        (first?.let { " · first=\"" + it + "\"" } ?: "") +
                        (translateError?.let { " · error=$it" } ?: "")
                )
            }

            PipelineTracer.span("cache", 0, "hits=$cacheHits misses=$cacheMisses per page")

            // --- Reconcile the progressive overlay (Part C) ------------------
            // Painter already painted whatever resolved early; fill in the rest.
            // If nothing painted yet (engine returned everything at once), fall back
            // to a single full-page paint.
            painter?.finish(translations)
            val arabicById = translations.associateBy({ it.id }, { it.arabic })
            val painterBoxes = painter?.snapshot() ?: emptyList()
            val painterAttached = painter?.attached == true || painter?.attachRequested == true
            val bubbleBoxes = if (painterBoxes.isNotEmpty()) painterBoxes else sampleBubbleBoxes(bitmap, ocrBlocks, arabicById)

            val painterFirstBubbleMs = painter?.firstBubbleMs ?: -1L
            val tapToOverlay = when {
                painterFirstBubbleMs >= 0 -> painterFirstBubbleMs
                lastTapAt > 0 -> SystemClock.elapsedRealtime() - lastTapAt
                else -> -1L
            }
            val meta = CaptureMeta(
                width = bitmap.width,
                height = bitmap.height,
                totalMs = totalMs,
                firstFrameWaitMs = firstFrameAt - displayCreatedAt,
                savedPath = savedPath,
                nearBlack = dark,
                capturedAt = System.currentTimeMillis(),
                ocrMs = ocrMs,
                ocrLineCount = ocrLineCount,
                bubbleCount = ocrBlocks.size,
                ocrError = ocrError,
                translateEngine = translateEngine,
                translateMs = translateMs,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                translateError = translateError,
                translations = translations,
                bubbleBoxes = bubbleBoxes,
                tapToOverlayMs = tapToOverlay
            )
            autoArmLiveIfNeeded()
            stage = "overlay"
            retainReviewFrame(bitmap, meta)
            maybeScheduleAiCleanup(bitmap, bubbleBoxes)
            if (painterAttached) {
                // Patches already on screen with the ML Kit bubbles — nothing else to draw.
                mainHandler.post {
                    statusPill?.hide()
                    finishPipelineTrace()
                }
            } else {
                val patches = if (bubbleBoxes.isNotEmpty()) buildPatches(bitmap, bubbleBoxes) else emptyList()
                val bw = bitmap.width
                val bh = bitmap.height
                mainHandler.post {
                    statusPill?.hide()
                    recordPaintResult(patches.size, bubbleBoxes.size)
                    if (patches.isNotEmpty()) {
                        patchOverlayEnsure().replaceAll(patches, bw, bh)
                        PipelineTracer.record(
                            PipelineTracer.Stage.OVERLAY, StageTrace.Status.OK, 0L,
                            "transparent patch overlay attached · patches painted = ${patches.size} of ${bubbleBoxes.size}"
                        )
                    } else {
                        patchOverlay?.clearPatches("error")
                        PipelineTracer.record(
                            PipelineTracer.Stage.OVERLAY, StageTrace.Status.FAILED, 0L,
                            if (bubbleBoxes.isEmpty()) "0 boxes — nothing translated for this page"
                            else "patch build failed for ${bubbleBoxes.size} box(es)"
                        )
                    }
                    finishPipelineTrace()
                }
            }
            Log.i(TAG, "stage=overlay shown; tap→overlay ${meta.tapToOverlayMs} ms")
        } catch (e: SecurityException) {
            if (!captureStageDone) traceCaptureFailed(startedAt, stage, e)
            onCaptureFailure(e, fatal = true, stage = stage)
        } catch (e: CaptureException) {
            if (!captureStageDone) traceCaptureFailed(startedAt, stage, e)
            onCaptureFailure(e, fatal = false, stage = stage)
        } catch (e: OutOfMemoryError) {
            if (!captureStageDone) traceCaptureFailed(startedAt, stage, e)
            onCaptureFailure(e, fatal = true, stage = stage)
        } catch (e: Exception) {
            if (!captureStageDone) traceCaptureFailed(startedAt, stage, e)
            onCaptureFailure(e, fatal = true, stage = stage)
        } catch (e: Throwable) {
            // Last-resort catch-all (NoClassDefFoundError, etc.): report and die gracefully.
            if (!captureStageDone) traceCaptureFailed(startedAt, stage, e)
            onCaptureFailure(e, fatal = true, stage = stage)
        } finally {
            // Session stays alive (persistent mirror); only the in-flight flag resets.
            captureInFlight.set(false)
        }
    }
    /**
     * Part 2 helper: OCR → group → BlockFilter → Part 1 garbage/own-window/own-string filter.
     * Returns the kept bubble blocks. Used by the tap path and by the longer-hide retry.
     * OCR failures are logged and return an empty list; the caller sets [ocrError].
     */
    private fun recognizeAndFilterBlocks(
        bitmap: Bitmap,
        ownedRectsOverride: List<com.manhwalens.app.ocr.geometry.Rect2D>? = null
    ): List<BubbleBlock> {
        val (ocrBitmap, cropOffsetY) = cropBarsForOcr(bitmap)
        val canCrop = ocrBitmap !== bitmap
        return try {
            val recognized = ocrClient.recognize(ocrBitmap)
            val lines = if (canCrop) {
                recognized.lines.map {
                    it.copy(box = com.manhwalens.app.ocr.geometry.Rect2D(
                        it.box.left, it.box.top + cropOffsetY, it.box.right, it.box.bottom + cropOffsetY
                    ))
                }
            } else recognized.lines
            recordOcrResult(lines.size, recognized.elapsedMs)
            val grouped = BubbleGrouper.group(lines)
            val edgePrefs = com.manhwalens.app.data.SettingsPrefs(applicationContext)
            val density = resources.displayMetrics.density
            val filterResult = BlockFilter.apply(
                grouped, bitmap.height, edgePrefs.ignoreTopPct, edgePrefs.ignoreBottomPct,
                edgePrefs.ignorePhraseList(),
                minHeightPx = 16f * density, minWordCount = 1
            )
            // Count BlockFilter drop reasons for diagnostics.
            for (r in filterResult.removed) {
                ocrDropsByReason[r.reason] = (ocrDropsByReason[r.reason] ?: 0) + 1
            }
            var filtered = filterResult.kept
            // Part 1 defense-in-depth: drop blocks over our windows, our strings, or OCR garbage.
            if (filtered.isNotEmpty()) {
                val classifier = com.manhwalens.app.ocr.OcrGarbageClassifier(
                    com.manhwalens.app.util.OwnedWindows.UI_STRINGS
                ) { SystemClock.elapsedRealtime() }
                val patchRects = emptyList<com.manhwalens.app.ocr.geometry.Rect2D>()
                val ownedRects = ownedRectsOverride ?: tapOwnedRects
                filtered = filtered.filterNot { block ->
                    val v = classifier.classify(
                        block.text.replace('\n', ' ').trim(), block.bounds,
                        patchRects = patchRects, ownedRects = ownedRects
                    )
                    if (!v.keep) {
                        ocrDropsByReason[v.reason.name] = (ocrDropsByReason[v.reason.name] ?: 0) + 1
                        true
                    } else false
                }
            }
            filtered
        } catch (e: Exception) {
            ErrorLog.write(this, "ocr", e)
            Log.e("ManhwaLens", "stage=ocr — ${e.javaClass.name}: ${e.message}", e)
            mainHandler.post {
                Toast.makeText(
                    this,
                    getString(R.string.toast_capture_failed_reason, "ocr · ${ErrorLog.reasonOf(e)}"),
                    Toast.LENGTH_LONG
                ).show()
            }
            emptyList()
        } finally {
            if (canCrop) runCatching { ocrBitmap.recycle() }
        }
    }

    /**
     * One live translation pass (Part C):
     *  1. row-hash the thumbnail vs the last translated frame → estimate the vertical
     *     scroll shift; bubbles still inside the frame are REUSED (same Arabic, shifted),
     *     only the newly revealed band is OCR'd;
     *  2. full-frame OCR when no reusable shift is found;
     *  3. dedupe + translate only the new blocks; union reused+new boxes;
     *  4. swap the overlay content in place (or attach it for the first pass).
     */
    private fun liveTranslate(inputFrame: Bitmap, initialGray: ByteArray) {
        // Per-cycle timers start clean: a live pass owns its own span/stage trace.
        com.manhwalens.app.util.PipelineTracer.reset()
        var frame = inputFrame
        var gray = initialGray
        val t0 = SystemClock.elapsedRealtime()
        // Remote request started alongside the ML Kit draft (null = single engine).
        var pendingUpgrade: LiveUpgrade? = null
        // Black frames (FLAG_SECURE) carry no page content — skip OCR, draw nothing.
        if (isNearlyBlack(frame)) {
            Log.w(TAG, "live: black frame — OCR skipped, overlay untouched")
            com.manhwalens.app.util.PipelineTracer.span("live black frame", 0, "black=true — skipped OCR")
            runCatching { if (!frame.isRecycled) frame.recycle() }
            setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
            return
        }
        try {
            // Part A (v0.9.4): the frame handed to this pass is CLEAN by construction — the
            // driver either used a stash captured with no patches on screen, or hid the overlay
            // and WAITED for a real new frame (400 ms cap, abort + retry on timeout). The old
            // code called acquireLiveBitmap() immediately after hiding the overlay, i.e. before
            // any new frame existed, and then OCR'd a frame with our own Arabic burned in.
            noteFrameSize(frame)

            // ---------- 1) vertical shift estimate + region reuse ----------
            val prevGray = liveLastTranslatedThumb
            val scaleY = frame.height / LiveFrameMath.THUMB_H.toFloat()
            var shiftPx = 0
            var shiftConfidence = 0f
            if (prevGray != null) {
                val shift = RowHashShift.estimate(prevGray, gray, LiveFrameMath.THUMB_W, LiveFrameMath.THUMB_H)
                if (shift != null) {
                    shiftPx = (shift.rows * scaleY).toInt()
                    shiftConfidence = shift.confidence
                }
            }
            var reuseId = -1
            val canReuse = kotlin.math.abs(shiftPx) in 8..(frame.height - 48) &&
                liveLastTranslatedBoxesAbs.isNotEmpty() &&
                shiftConfidence >= 0.6f
            val reusedBoxes: List<com.manhwalens.app.render.BubbleRenderBox> =
                if (canReuse) {
                    liveLastTranslatedBoxesAbs.mapNotNull { b ->
                        val top = b.top - shiftPx
                        val bottom = b.bottom - shiftPx
                        if (top >= 0 && bottom <= frame.height) {
                            b.copy(id = reuseId--, top = top, bottom = bottom)
                        } else null
                    }
                } else emptyList()
            if (canReuse) {
                com.manhwalens.app.util.PipelineTracer.span(
                    "live region reuse", 0,
                    "shift=${shiftPx}px conf=${"%.2f".format(shiftConfidence)} reused=${reusedBoxes.size}/${liveLastTranslatedBoxesAbs.size}"
                )
            }

            // Which band do we need to OCR for the NEW content?
            val band: android.graphics.Rect = when {
                canReuse && shiftPx > 0 -> android.graphics.Rect(0, frame.height - shiftPx, frame.width, frame.height)
                canReuse && shiftPx < 0 -> android.graphics.Rect(0, 0, frame.width, -shiftPx)
                else -> android.graphics.Rect(0, 0, frame.width, frame.height)
            }
            val bandHeight = band.height()

            // ---------- 2) OCR (new band only, or full frame) ----------
            val newBoxes: ArrayList<com.manhwalens.app.render.BubbleRenderBox> = ArrayList()
            var ocrMs = -1L
            var newBlockCount = 0
            var engineLabel: String? = null
            var engineMs = -1L
            var hits = 0
            var misses = 0
            var translatedExpanded: List<com.manhwalens.app.translate.TranslatedBlock> = emptyList()
            if (bandHeight >= 48) {
                var bandTop = band.top
                val ocrBitmap: Bitmap
                if (band.top == 0 && band.bottom == frame.height) {
                    val (cropped, yOff) = cropBarsForOcr(frame)
                    ocrBitmap = cropped
                    bandTop = (band.top + yOff).toInt()
                } else {
                    ocrBitmap = Bitmap.createBitmap(frame, 0, band.top, frame.width, band.height())
                }
                try {
                    val recognized = ocrClient.recognize(ocrBitmap)
                    ocrMs = recognized.elapsedMs
                    recordOcrResult(recognized.lines.size, ocrMs)
                    val lines = if (ocrBitmap !== frame) {
                        recognized.lines.map {
                            it.copy(box = com.manhwalens.app.ocr.geometry.Rect2D(
                                it.box.left,
                                it.box.top + bandTop,
                                it.box.right,
                                it.box.bottom + bandTop
                            ))
                        }
                    } else recognized.lines
                    val grouped = BubbleGrouper.group(lines)
                    val edgePrefs = com.manhwalens.app.data.SettingsPrefs(applicationContext)
                    val density = resources.displayMetrics.density
                    val filtered = BlockFilter.apply(
                        grouped, frame.height, edgePrefs.ignoreTopPct, edgePrefs.ignoreBottomPct,
                        edgePrefs.ignorePhraseList(),
                        minHeightPx = 16f * density, minWordCount = 1
                    )
                    // v0.9.12: text sitting on our own UI (lens, caption, menu) is not page text.
                    val ownRects = liveOwnedRects(frame.width)
                    val pageBlocks = filtered.kept.filterNot {
                        com.manhwalens.app.live.OwnUiFilter.isOwnUi(it.bounds, ownRects)
                    }
                    if (pageBlocks.size != filtered.kept.size) {
                        com.manhwalens.app.util.PipelineTracer.span(
                            "live own-ui drop", 0, "${filtered.kept.size - pageBlocks.size} block(s) over the lens/menu"
                        )
                    }
                    val newBlocks = pageBlocks
                        .map { com.manhwalens.app.translate.TranslationBlock(it.id, it.text) }
                        .filter { it.english.isNotBlank() }
                    newBlockCount = newBlocks.size

                    // ---------- 3) plan + translate ONLY the new blocks ----------
                    // PagePlan (v0.11.0) drops meaningless blocks (watermarks, "......",
                    // chapter headers, our own UI) BEFORE the engine, so they cost no request
                    // and are never painted; it also dedupes on the glossary-prepared text.
                    val pagePlan = com.manhwalens.app.translate.PagePlan.prepare(
                        newBlocks,
                        ownStrings = com.manhwalens.app.util.OwnedWindows.UI_STRINGS
                    )
                    if (pagePlan.droppedCount > 0 || pagePlan.uniqueCount != pagePlan.originalCount) {
                        com.manhwalens.app.util.PipelineTracer.span(
                            "live page plan", 0, com.manhwalens.app.translate.PagePlan.describe(pagePlan)
                        )
                    }
                    if (pagePlan.droppedCount > 0) {
                        for ((id, reason) in pagePlan.dropped) {
                            ocrDropsByReason[reason.name] = (ocrDropsByReason[reason.name] ?: 0) + 1
                            Log.i(TAG, "live page plan: #$id dropped ($reason)")
                        }
                    }
                    if (pagePlan.uniqueCount > 0) {
                        val unique = com.manhwalens.app.translate.PagePlan.engineBlocks(pagePlan)
                        // Failure carries no elapsedMs, so the live path times the call itself
                        // (self-check stage 10 wants ms for BOTH outcomes).
                        val trStartedAt = android.os.SystemClock.elapsedRealtime()
                        // A configured remote engine and ML Kit start together. The local
                        // draft is painted immediately and replaced only after a valid remote
                        // page arrives; Live never waits for the remote engine unless the draft failed.
                        val prefsL = com.manhwalens.app.data.TranslationPrefs(applicationContext)
                        val dual = com.manhwalens.app.translate.TranslatorFactory
                            .remoteUpgradeAvailable(applicationContext)
                        val remoteEngineId = when (prefsL.engine) {
                            com.manhwalens.app.translate.TranslationEngine.GEMINI -> "gemini"
                            com.manhwalens.app.translate.TranslationEngine.LIBRE_TRANSLATE -> "libre_translate"
                            com.manhwalens.app.translate.TranslationEngine.ML_KIT -> "remote"
                        }
                        val tr: com.manhwalens.app.translate.PageTranslation = if (dual) {
                            val up = LiveUpgrade()
                            pendingUpgrade = up
                            startLiveRemote(up, pagePlan, newBlocks, unique)
                            val tDraft = android.os.SystemClock.elapsedRealtime()
                            val draft = runCatching {
                                com.manhwalens.app.translate.TranslatorFactory.createOffline(applicationContext)
                                    .translatePage(unique)
                            }.getOrNull()
                            up.draftMs = android.os.SystemClock.elapsedRealtime() - tDraft
                            // Remote engine already back (cache / fast server)? Paint it directly, once.
                            val early = synchronized(up) { up.result.also { if (it != null) up.consumed = true } }
                            when {
                                early is com.manhwalens.app.translate.PageTranslation.Success -> {
                                    com.manhwalens.app.util.PipelineTracer.span(
                                        "live phases", up.remoteMs,
                                        "ML Kit draft ${up.draftMs} ms · ${early.engineId} ${up.remoteMs} ms (already back — painted once)"
                                    )
                                    early
                                }
                                draft is com.manhwalens.app.translate.PageTranslation.Success -> {
                                    com.manhwalens.app.util.PipelineTracer.span(
                                        "live phase 1 (ML Kit)", up.draftMs,
                                        if (early == null) "draft painted now · $remoteEngineId still running, will replace in place"
                                        else "draft painted · $remoteEngineId failed — draft stays"
                                    )
                                    draft
                                }
                                else -> {
                                    // No draft to show — only now is waiting for the remote result worth it.
                                    up.resultLatch.await(LIVE_REMOTE_WAIT_MS, TimeUnit.MILLISECONDS)
                                    val late = synchronized(up) { up.consumed = true; up.result }
                                    com.manhwalens.app.util.PipelineTracer.span(
                                        "live phases", android.os.SystemClock.elapsedRealtime() - trStartedAt,
                                        "ML Kit draft FAILED in ${up.draftMs} ms · waited for $remoteEngineId (${up.remoteMs} ms)"
                                    )
                                    late ?: draft ?: com.manhwalens.app.translate.PageTranslation.Failure(
                                        com.manhwalens.app.translate.FailureReason.TIMEOUT,
                                        "no engine answered in time", remoteEngineId
                                    )
                                }
                            }
                        } else {
                            com.manhwalens.app.translate.TranslatorFactory.create(applicationContext).translatePage(unique)
                        }
                        when (tr) {
                            is com.manhwalens.app.translate.PageTranslation.Success -> {
                                engineLabel = tr.engineId
                                engineMs = tr.elapsedMs
                                hits = tr.cacheHits
                                misses = tr.cacheMisses
                                translatedExpanded = com.manhwalens.app.translate.PagePlan.expand(pagePlan, tr.blocks, newBlocks.associate { it.id to it.english })
                                recordTranslateResult(tr.engineId, tr.elapsedMs, null)
                            }
                            is com.manhwalens.app.translate.PageTranslation.Failure -> {
                                if (tr.engineId == "gemini") {
                                    openGeminiCircuit(tr.reason, tr.message)
                                }
                                recordTranslateResult(
                                    tr.engineId,
                                    android.os.SystemClock.elapsedRealtime() - trStartedAt,
                                    "${tr.reason}: ${tr.message}"
                                )
                                com.manhwalens.app.util.LiveLog.e(
                                    TAG, "live translation failed (${tr.reason}): ${tr.message}"
                                )
                                // If a remote engine was unavailable before the dual phase, keep
                                // the page usable by retrying on-device.
                                var recovered = false
                                if ((tr.engineId == "gemini" || tr.engineId == "libre_translate") && !dual) {
                                    val fb = runCatching {
                                        com.manhwalens.app.translate.TranslatorFactory.createOffline(applicationContext)
                                            .translatePage(unique)
                                    }.getOrNull()
                                    if (fb is com.manhwalens.app.translate.PageTranslation.Success) {
                                        engineLabel = "ml_kit_en_ar (offline fallback)"
                                        engineMs = fb.elapsedMs
                                        hits = fb.cacheHits
                                        misses = fb.cacheMisses
                                        translatedExpanded = com.manhwalens.app.translate.PagePlan.expand(pagePlan, fb.blocks, newBlocks.associate { it.id to it.english })
                                        recordTranslateResult(engineLabel, fb.elapsedMs, null)
                                        recovered = true
                                    }
                                }
                                // …and when nothing worked, SAY so and retry the page shortly.
                                if (!recovered) {
                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (nowMs - lastLiveFailureToastAtMs > LIVE_FAILURE_TOAST_INTERVAL_MS) {
                                        lastLiveFailureToastAtMs = nowMs
                                        val why = "${tr.reason}: ${tr.message}".take(140)
                                        mainHandler.post {
                                            Toast.makeText(
                                                this, getString(R.string.live_translate_failed, why), Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                    liveHandler.postDelayed({
                                        liveLastTranslatedThumb = null
                                        liveDriver.reset()
                                    }, LIVE_FAILURE_RETRY_MS)
                                }
                            }
                        }
                        val arabicById = translatedExpanded.associateBy({ it.id }, { it.arabic })
                        // Blocks planned away as meaningless (watermarks, "......", chapter
                        // headers) are not painted at all, even if something answered them.
                        val plannedAway = pagePlan.dropped.map { it.first }.toHashSet()
                        val paintable = filtered.kept.filterNot { it.id in plannedAway }
                        if (plannedAway.isNotEmpty()) {
                            com.manhwalens.app.util.PipelineTracer.span(
                                "live plan skipped", 0,
                                "${plannedAway.size} meaningless block(s) not painted"
                            )
                        }
                        newBoxes += sampleBubbleBoxes(frame, paintable, arabicById)
                    }
                } finally {
                    if (ocrBitmap !== frame) runCatching { ocrBitmap.recycle() }
                }
            } else if (canReuse) {
                Log.i(TAG, "live: revealed band ${bandHeight}px < 48 — keeping reused bubbles only (OCR skipped)")
            }

            val finalBoxes = (reusedBoxes + newBoxes).sortedBy { it.top }
            liveLastTranslatedThumb = gray
            liveDriver.noteTranslated(gray)
            liveLastTranslatedBoxesAbs = finalBoxes

            val shownEngine = engineLabel ?: (if (canReuse) "cached (region reuse)" else "none")
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "live translate done: engine=$shownEngine boxes=${finalBoxes.size} (new=$newBlockCount reused=${reusedBoxes.size}) ocr=${ocrMs} ms total=$ms ms")
            com.manhwalens.app.util.PipelineTracer.span(
                "live translate", ms,
                "engine=$shownEngine new=$newBlockCount reused=${reusedBoxes.size}"
            )
            com.manhwalens.app.util.PipelineTracer.span("cache", 0, "hits=$hits misses=$misses")

            // ---------- 4) swap the overlay content in place ----------
            val frameToShow = frame
            // A remote reply that is still on its way needs these pixels to re-render its
            // bubbles: it takes over the frame and recycles it when it is done.
            val upgrade = pendingUpgrade
            val upgradeOwnsFrame = upgrade != null && newBoxes.isNotEmpty() &&
                synchronized(upgrade) {
                    if (upgrade.consumed) false else {
                        upgrade.frame = frameToShow
                        upgrade.boxes = newBoxes.toList()
                        true
                    }
                }
            mainHandler.post {
                statusPill?.hide()
                if (finalBoxes.isEmpty()) {
                    // Nothing to translate on this page — drop stale patches, keep the raw reader visible.
                    patchOverlay?.clearPatches("page change")
                    if (!upgradeOwnsFrame) runCatching { if (!frameToShow.isRecycled) frameToShow.recycle() }
                    setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
                    return@post
                }
                val meta = CaptureMeta(
                    width = frameToShow.width,
                    height = frameToShow.height,
                    totalMs = ms,
                    firstFrameWaitMs = -1L,
                    savedPath = null,
                    nearBlack = false,
                    capturedAt = System.currentTimeMillis(),
                    ocrMs = ocrMs,
                    ocrLineCount = -1,
                    bubbleCount = finalBoxes.size,
                    translateEngine = shownEngine,
                    translateMs = engineMs,
                    cacheHits = hits,
                    cacheMisses = misses,
                    translateError = null,
                    translations = translatedExpanded,
                    bubbleBoxes = finalBoxes,
                    tapToOverlayMs = -1L,
                    liveMode = true
                )
                val patches = buildPatches(frameToShow, finalBoxes)
                // The overlay shows patches ONLY — the captured frame is never displayed,
                // never retained by Live. Recycle it now that the patches own their pixels
                // (unless a pending remote upgrade took it over — it recycles it itself).
                if (!upgradeOwnsFrame) runCatching { if (!frameToShow.isRecycled) frameToShow.recycle() }
                recordPaintResult(patches.size, finalBoxes.size)
                if (patches.isNotEmpty()) {
                    val po = patchOverlayEnsure()
                    po.replaceAll(patches, meta.width, meta.height)
                    // Runs on the main thread, so replaceAll has already been applied.
                    if (upgradeOwnsFrame) upgrade?.epoch = po.contentEpoch()
                } else {
                    patchOverlay?.clearPatches("error")
                    PipelineTracer.record(PipelineTracer.Stage.OVERLAY, StageTrace.Status.FAILED, 0L,
                        "patch build failed for ${finalBoxes.size} box(es) — page left in original")
                }
                setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
                PipelineTracer.span("live overlay shown", 0, "patches painted = ${patches.size} of ${finalBoxes.size} · engine=$shownEngine")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "live translate failed — ${e.javaClass.name}: ${e.message}", e)
            ErrorLog.write(this, "live", e)
            // The frame is recycled here only if no pending upgrade has taken it over
            // (ownership is handed over right before the paint is queued, after which
            // nothing in this pass can throw).
            val up = pendingUpgrade
            val owned = up != null && synchronized(up) { up.frame != null }
            if (!owned) {
                up?.let { synchronized(it) { it.consumed = true } }
                runCatching { if (!frame.isRecycled) frame.recycle() }
            }
            setLiveStatus(com.manhwalens.app.live.LiveStatus.ERROR)
        } finally {
            // Phase 1 is queued (or this pass is over): the remote upgrade may now paint.
            pendingUpgrade?.phase1Latch?.countDown()
        }
    }

    /** Hand-over between one Live pass (ML Kit draft) and its optional remote upgrade. */
    private class LiveUpgrade {
        /** Remote outcome; guarded by `synchronized(this)` together with [consumed]. */
        var result: com.manhwalens.app.translate.PageTranslation? = null
        /** True when the Live pass used/abandoned the result itself — nothing to upgrade. */
        var consumed = false
        /** Frame handed over by the Live pass; the upgrade recycles it. */
        var frame: Bitmap? = null
        @Volatile var boxes: List<com.manhwalens.app.render.BubbleRenderBox> = emptyList()
        @Volatile var remoteMs = -1L
        @Volatile var draftMs = -1L
        /** Overlay content epoch right after the draft was painted (-1 = not painted). */
        @Volatile var epoch = -1
        val resultLatch = CountDownLatch(1)
        val phase1Latch = CountDownLatch(1)
    }

    /**
     * Start the selected remote engine beside the Live ML Kit draft. A failure leaves the
     * local translation untouched; only Gemini failures affect Gemini's circuit breaker.
     */
    private fun startLiveRemote(
        up: LiveUpgrade,
        pagePlan: com.manhwalens.app.translate.PagePlan.Plan,
        original: List<com.manhwalens.app.translate.TranslationBlock>,
        unique: List<com.manhwalens.app.translate.TranslationBlock>
    ) {
        val expectedEngine = when (com.manhwalens.app.data.TranslationPrefs(applicationContext).engine) {
            com.manhwalens.app.translate.TranslationEngine.GEMINI -> "gemini"
            com.manhwalens.app.translate.TranslationEngine.LIBRE_TRANSLATE -> "libre_translate"
            com.manhwalens.app.translate.TranslationEngine.ML_KIT -> "remote"
        }
        val task = Runnable {
            val t0 = SystemClock.elapsedRealtime()
            val remote = runCatching {
                com.manhwalens.app.translate.TranslatorFactory.createRemoteOnly(applicationContext)
            }.getOrNull()
            val res: com.manhwalens.app.translate.PageTranslation = if (remote == null) {
                com.manhwalens.app.translate.PageTranslation.Failure(
                    com.manhwalens.app.translate.FailureReason.UNKNOWN,
                    "remote translator unavailable",
                    expectedEngine
                )
            } else {
                runCatching { remote.translatePage(unique) }.getOrElse { e ->
                    Log.e(TAG, "live remote translator crashed — ${e.javaClass.name}: ${e.message}", e)
                    com.manhwalens.app.translate.PageTranslation.Failure(
                        com.manhwalens.app.translate.FailureReason.UNKNOWN,
                        "crash: ${e.javaClass.simpleName}",
                        remote.engineId
                    )
                }
            }
            val ms = SystemClock.elapsedRealtime() - t0
            up.remoteMs = ms
            if (res is com.manhwalens.app.translate.PageTranslation.Failure) {
                if (remote?.engineId == "gemini") openGeminiCircuit(res.reason, res.message)
                com.manhwalens.app.util.LiveLog.e(TAG, "live ${res.engineId} failed (${res.reason}): ${res.message}")
                PipelineTracer.span("live phase 2 (${res.engineId})", ms, "FAILED: ${res.reason} — ML Kit draft kept")
            }
            synchronized(up) { up.result = res }
            up.resultLatch.countDown()
            // Wait until the draft paint is queued, so anything below lands AFTER it.
            runCatching { up.phase1Latch.await(LIVE_UPGRADE_WAIT_MS, TimeUnit.MILLISECONDS) }
            // Take the frame if the Live pass handed it over; otherwise close the door so
            // a very late hand-over cannot leave a frame nobody recycles.
            val frame = synchronized(up) {
                val f = if (up.consumed) null else up.frame
                if (f == null) up.consumed = true
                f
            } ?: return@Runnable
            if (res is com.manhwalens.app.translate.PageTranslation.Success) {
                applyLiveUpgrade(up, frame, pagePlan, original, res, ms)
            } else {
                // Remote failed after the draft was painted: the draft stays, free the frame.
                mainHandler.post { runCatching { if (!frame.isRecycled) frame.recycle() } }
            }
        }
        try {
            remoteExecutor.execute(task)
        } catch (e: Throwable) {
            synchronized(up) {
                up.result = com.manhwalens.app.translate.PageTranslation.Failure(
                    com.manhwalens.app.translate.FailureReason.UNKNOWN, "executor rejected", expectedEngine
                )
            }
            up.resultLatch.countDown()
        }
    }

    /** Re-render the bubbles changed by a remote translation and swap them into the current patches. */
    private fun applyLiveUpgrade(
        up: LiveUpgrade,
        frame: Bitmap,
        pagePlan: com.manhwalens.app.translate.PagePlan.Plan,
        original: List<com.manhwalens.app.translate.TranslationBlock>,
        res: com.manhwalens.app.translate.PageTranslation.Success,
        remoteMs: Long
    ) {
        var patches: List<com.manhwalens.app.render.BubblePatch> = emptyList()
        var changed: List<com.manhwalens.app.render.BubbleRenderBox> = emptyList()
        try {
            val arabicById = com.manhwalens.app.translate.PagePlan.arabicById(pagePlan, res.blocks)
            changed = up.boxes.mapNotNull { b ->
                arabicById[b.id]?.takeIf { it != b.arabic }?.let { b.copy(arabic = it) }
            }
            patches = buildPatches(frame, changed)
        } catch (e: Throwable) {
            Log.e(TAG, "live remote upgrade failed to render — ${e.javaClass.name}: ${e.message}", e)
        }
        val swapped = changed
        val built = patches
        mainHandler.post {
            val po = patchOverlay
            val current = po != null && up.epoch >= 0 && po.contentEpoch() == up.epoch
            for (p in built) {
                if (current) po!!.replaceBitmap(p.id, p.bitmap) else runCatching { p.bitmap.recycle() }
            }
            runCatching { if (!frame.isRecycled) frame.recycle() }
            PipelineTracer.span(
                "live phase 2 (${res.engineId})", remoteMs,
                "ML Kit draft ${up.draftMs} ms → ${res.engineId} $remoteMs ms · " +
                    if (current) "replaced ${built.size} of ${up.boxes.size} bubble(s) in place"
                    else "page moved on — ${swapped.size} text(s) kept for the next pass"
            )
            Log.i(TAG, "live remote upgrade (${res.engineId}): draft=${up.draftMs} ms remote=$remoteMs ms changed=${swapped.size} current=$current")
        }
        // Reused boxes (scroll region reuse) must carry the remote text from now on.
        if (swapped.isNotEmpty()) {
            val byEnglish = swapped.associate { it.english to it.arabic }
            liveHandler.post {
                liveLastTranslatedBoxesAbs = liveLastTranslatedBoxesAbs.map { b ->
                    byEnglish[b.english]?.let { b.copy(arabic = it) } ?: b
                }
            }
        }
    }

    /**
     * Second half of progressive display: the selected remote translator runs AFTER the overlay
     * is visible (ML Kit pass). Each bubble's text is replaced in place as soon as the
     * batch lands; on failure the offline overlay stays unchanged.
     */
    /**
     * Optional remote upgrade — runs in PARALLEL with the ML Kit pass.
     * ONE batched 6 s-timeout request for the page; each bubble is swapped in place
     * on arrival. On any failure the ML Kit overlay stays and the circuit opens
     * for 5 minutes; the user never sees an error for this best-effort upgrade.
     */
    /**
     * Pause Gemini after a failure. Only failures that will not fix themselves (bad key,
     * quota, unknown model) pause it for the full 5 minutes; a timeout or a network blip
     * pauses it for 30 s so one slow reply no longer silently disables Gemini.
     */
    private fun openGeminiCircuit(reason: com.manhwalens.app.translate.FailureReason, message: String) {
        val longPause = reason == com.manhwalens.app.translate.FailureReason.INVALID_API_KEY ||
            reason == com.manhwalens.app.translate.FailureReason.RATE_LIMITED ||
            reason == com.manhwalens.app.translate.FailureReason.MODEL_NOT_FOUND
        val circuit = com.manhwalens.app.data.TranslationPrefs(applicationContext).geminiCircuit
        if (longPause) circuit.open("$reason: $message") else circuit.open("$reason: $message", GEMINI_SHORT_PAUSE_MS)
        Log.i(TAG, "gemini paused for ${if (longPause) "5 min" else "30 s"} ($reason)")
    }

    private fun relayRemoteTranslation(
        painter: BubblePainter,
        pagePlan: com.manhwalens.app.translate.PagePlan.Plan,
        original: List<com.manhwalens.app.translate.TranslationBlock>,
        unique: List<com.manhwalens.app.translate.TranslationBlock>
    ) {
        val t0 = SystemClock.elapsedRealtime()
        val remote = com.manhwalens.app.translate.TranslatorFactory.createRemoteOnly(applicationContext)
        if (remote == null) {
            Log.i(TAG, "remote translation relay skipped — engine is no longer configured")
            return
        }
        val res = runCatching { remote.translatePage(unique) }.getOrElse { e ->
            Log.e(TAG, "${remote.engineId} relay crashed — ${e.javaClass.name}: ${e.message}", e)
            if (remote.engineId == "gemini") {
                com.manhwalens.app.data.TranslationPrefs(applicationContext).geminiCircuit
                    .open("relay crash: ${e.javaClass.simpleName}")
            }
            null
        }
        when (res) {
            is com.manhwalens.app.translate.PageTranslation.Success -> {
                val expanded = com.manhwalens.app.translate.PagePlan.expand(
                    pagePlan, res.blocks, original.associate { it.id to it.english }
                )
                val good = expanded.filter { it.arabic.isNotBlank() }
                good.forEach { tb -> runCatching { painter.onRemote(tb) } }
                PipelineTracer.span(
                    "${res.engineId} relay", SystemClock.elapsedRealtime() - t0,
                    "replaced ${good.size} bubble(s) · ${res.elapsedMs} ms · cache ${res.cacheHits}/${res.cacheHits + res.cacheMisses}"
                )
                // Both clocks begin together; ML Kit paints first and valid remote Arabic upgrades it.
                PipelineTracer.span(
                    "phase 1 / remote", SystemClock.elapsedRealtime() - t0,
                    "ML Kit first bubble at ${painter.firstBubbleMs} ms · ${res.engineId} arrived ${SystemClock.elapsedRealtime() - t0} ms after start"
                )
                Log.i(TAG, "${res.engineId} relay ok — ${good.size} bubbles swapped in ${SystemClock.elapsedRealtime() - t0} ms")
            }
            is com.manhwalens.app.translate.PageTranslation.Failure -> {
                if (res.engineId == "gemini") openGeminiCircuit(res.reason, res.message)
                PipelineTracer.span(
                    "${res.engineId} relay", SystemClock.elapsedRealtime() - t0,
                    "FAILED: ${res.reason} — kept offline overlay"
                )
                Log.i(TAG, "${res.engineId} relay failed (${res.reason}) — keeping ML Kit overlay")
            }
            null -> Unit
        }
    }

    private fun traceCaptureFailed(startedAt: Long, stage: String, e: Throwable) {
        PipelineTracer.record(
            PipelineTracer.Stage.CAPTURE, StageTrace.Status.FAILED,
            SystemClock.elapsedRealtime() - startedAt,
            "stage=$stage · ${ErrorLog.reasonOf(e)}"
        )
    }

    private fun onCaptureFailure(e: Throwable, fatal: Boolean, stage: String) {
        // Part 1d: never leave the in-flight flag set on a failure path.
        captureInFlight.set(false)
        captureStartedAtMs = 0L
        val reason = "$stage · ${ErrorLog.reasonOf(e)}"
        ErrorLog.write(this, "capture:$stage", e)
        Log.e("ManhwaLens", "capture failed at stage=$stage — ${e.javaClass.name}: ${e.message}", e)
        statusPill?.hide()
        if (PipelineTracer.snapshot().none { it.stage == "OVERLAY" }) {
            PipelineTracer.record(
                PipelineTracer.Stage.OVERLAY, StageTrace.Status.SKIPPED, 0L,
                "capture ended at stage=$stage — overlay never reached"
            )
        }
        finishPipelineTrace()
        mainHandler.post {
            statusPill?.showError(getString(R.string.pill_capture_failed))
        }
        if (fatal) {
            // Token/session is untrusted — drop the projection; next tap re-asks consent.
            capturePromoted = false
            releaseProjection("capture failure at stage=$stage")
            mainHandler.post { runCatching { stopForeground(STOP_FOREGROUND_REMOVE) } }
        }
        mainHandler.post {
            Toast.makeText(this, getString(R.string.toast_capture_failed_reason, reason), Toast.LENGTH_LONG).show()
        }
    }

    /** Show the non-blocking trace panel (debug mode) and persist to Last error. */
    private fun finishPipelineTrace() {
        // Back-fill any stage the pipeline never reached so all 7 are always shown.
        val present = PipelineTracer.snapshot().map { it.stage }.toSet()
        for (stage in PipelineTracer.Stage.values()) {
            if (stage.wire !in present) {
                PipelineTracer.record(stage, StageTrace.Status.SKIPPED, 0L,
                    "not reached — pipeline ended earlier")
            }
        }
        PipelineTracer.persistToErrorLog(applicationContext)
    }

    /**
     * Progressive overlay painter (Part C): holds the frame + the filtered blocks,
     * samples each bubble's background lazily on first paint (bubble-sized pixels —
     * cheap), and pushes a growing box list to the overlay as translations land:
     *  - onBlockReady (ML Kit, per block, ≤3 in flight, priority order)
     *  - onRemote (selected remote engine runs beside ML Kit; a valid remote reply wins)
     *  - finish() (final reconciliation once the page translation returns)
     *
     * Thread-safe: paint may arrive from worker threads; overlay ops post to main.
     * [attached] flips true the moment the first box hits the screen.
     */
    private inner class BubblePainter(
        private val bitmap: Bitmap,
        keptBlocks: List<BubbleBlock>,
        private val tapStartMs: Long,
        private val engineId: String
    ) {
        private val lock = Any()
        private val blocksById = keptBlocks.associateBy { it.id }
        /**
         * v0.11.0: block id → its area's bounds. Owner and sibling both resolve their area
         * from its members' bounds, not from the owner's own OCR box.
         */
        private val areaById = HashMap<Int, com.manhwalens.app.ocr.geometry.Rect2D>()
        private val painted = LinkedHashMap<Int, com.manhwalens.app.render.BubbleRenderBox>()
        private val remoteWins = HashSet<Int>()
        /**
         * v0.11.0: one patch area per visual bubble, decided up-front from EVERY kept block
         * (never from arrival order). Only area owners draw Arabic; siblings are erased by
         * the owner's cover, so two Arabic texts can never share a bubble.
         */
        private val areas: List<com.manhwalens.app.render.PatchPlanner.Area> =
            com.manhwalens.app.render.PatchPlanner.areas(
                keptBlocks.map {
                    it.id to com.manhwalens.app.ocr.geometry.Rect2D(
                        it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom
                    )
                },
                bitmap.width, bitmap.height
            )
        init {
            // Every member of an area resolves to the same cover rect.
            for (a in areas) for (id in a.memberIds) areaById[id] = a.cover
        }
        private val paintLedger = com.manhwalens.app.render.PaintLedger(keptBlocks.size)

        @Volatile var attached: Boolean = false
            private set

        /** Set on the worker thread the instant the first attach is queued (race-free visible flag). */
        @Volatile var attachRequested: Boolean = false
            private set

        @Volatile var firstBubbleMs: Long = -1L
            private set

        fun onBlockReady(tb: com.manhwalens.app.translate.TranslatedBlock) = paint(tb, remote = false)
        fun onRemote(tb: com.manhwalens.app.translate.TranslatedBlock) = paint(tb, remote = true)

        private fun paint(tb: com.manhwalens.app.translate.TranslatedBlock, remote: Boolean) {
            if (tb.arabic.isBlank()) return
            val block = blocksById[tb.id] ?: return
            // P0/C2: the painter is the last line of defence. Whatever path produced this text —
            // progressive callback, remote upgrade, final reconciliation, cache — an English
            // echo or a half-translated sentence is never drawn over the art. The guard is the
            // same object every engine is judged by, so a preserved Latin name still passes.
            val verdict = com.manhwalens.app.translate.ArabicValidator.issue(block.text, tb.arabic)
            if (verdict != null) {
                Log.w(TAG, "patch refused #${tb.id} (${verdict.name.lowercase()}): output is not usable Arabic")
                return
            }
            val r = block.bounds
            val left = r.left.toInt().coerceIn(0, bitmap.width); val top = r.top.toInt().coerceIn(0, bitmap.height)
            val right = r.right.toInt().coerceIn(0, bitmap.width); val bottom = r.bottom.toInt().coerceIn(0, bitmap.height)
            val bg = sampleBackground(bitmap, left, top, right, bottom)
            val box = com.manhwalens.app.render.BubbleRenderBox(
                id = tb.id,
                left = left, top = top, right = right, bottom = bottom,
                english = block.text,
                arabic = tb.arabic,
                backgroundColor = bg,
                textColor = com.manhwalens.app.render.ColorSampler.bestTextColorOn(bg)
            )
            // v0.11.0: one visual area = one patch. Every block of an area — owner or folded
            // sibling — renders over the SAME cover rect, using its own Arabic. The Arabic is
            // laid out from the cover's top-centre, so two blocks of one bubble stack into one
            // readable text instead of drawing over each other, and the English of a block
            // whose Arabic failed is erased anyway (that bubble can never stay English).
            val area = areaById[tb.id]
            val cover = area ?: com.manhwalens.app.render.PatchPlanner.coverFor(
                areas, com.manhwalens.app.render.PatchPlanner.ownerOf(areas, tb.id)
            )
            val textBox = if (cover != null) {
                box.copy(
                    left = cover.left.toInt().coerceIn(0, bitmap.width),
                    top = cover.top.toInt().coerceIn(0, bitmap.height),
                    right = cover.right.toInt().coerceIn(0, bitmap.width),
                    bottom = cover.bottom.toInt().coerceIn(0, bitmap.height)
                )
            } else box
            synchronized(lock) {
                if (!remote && remoteWins.contains(tb.id)) return
                if (remote) remoteWins.add(tb.id)
                paintLedger.record(tb.id)
                painted[tb.id] = textBox
            }
            Log.i(TAG, paintLedger.line())
            // Rasterize the patch HERE (worker thread) — small bitmap, bounded work.
            val patch = try {
                com.manhwalens.app.render.PatchSurface.build(
                    applicationContext, bitmap, textBox, cover = cover, band = r
                )
            } catch (oom: OutOfMemoryError) {
                Log.e("ManhwaLens", "patch build OOM for #${textBox.id} — bubble skipped", oom)
                null
            } catch (e: Exception) {
                Log.e("ManhwaLens", "patch build failed for #${textBox.id} — ${e.javaClass.name}: ${e.message}", e)
                null
            }
            val elapsed = SystemClock.elapsedRealtime() - tapStartMs
            if (patch != null && !attached && !attachRequested) attachRequested = true
            mainHandler.post {
                if (patch == null) return@post
                statusPill?.hide()
                val po = patchOverlayEnsure()
                if (!attached) {
                    attached = true
                    firstBubbleMs = elapsed
                    // A fresh tap capture invalidates the previous page's patches.
                    po.beginSession("page change")
                    if (firstBubbleMs >= 0) {
                        PipelineTracer.span(
                            "first bubble shown at", firstBubbleMs,
                            "patch 1 painted (${if (remote) "remote" else engineId})"
                        )
                    }
                    PipelineTracer.record(
                        PipelineTracer.Stage.OVERLAY, StageTrace.Status.OK, 0L,
                        "transparent patch overlay attached (tap→overlay ${firstBubbleMs} ms)"
                    )
                }
                po.upsert(patch, bitmap.width, bitmap.height)
            }
        }

        fun snapshot(): List<com.manhwalens.app.render.BubbleRenderBox> =
            synchronized(lock) { painted.values.sortedBy { it.top } }

        /** Fill anything not painted yet (cache-hit path paints earlier; engine call paints via listener). */
        fun finish(translations: List<com.manhwalens.app.translate.TranslatedBlock>) {
            synchronized(lock) {
                for (t in translations) {
                    if (t.arabic.isBlank() || painted.containsKey(t.id)) continue
                    val block = blocksById[t.id] ?: continue
                    val r = block.bounds
                    val left = r.left.toInt().coerceIn(0, bitmap.width); val top = r.top.toInt().coerceIn(0, bitmap.height)
                    val right = r.right.toInt().coerceIn(0, bitmap.width); val bottom = r.bottom.toInt().coerceIn(0, bitmap.height)
                    val bg = sampleBackground(bitmap, left, top, right, bottom)
                    // Same cover rect the progressive path uses, so a late bubble cannot
                    // repaint a narrower area than its siblings.
                    val area = areaById[t.id]
                    painted[t.id] = com.manhwalens.app.render.BubbleRenderBox(
                        id = t.id,
                        left = area?.left?.toInt() ?: left, top = area?.top?.toInt() ?: top,
                        right = area?.right?.toInt() ?: right, bottom = area?.bottom?.toInt() ?: bottom,
                        english = block.text,
                        arabic = t.arabic,
                        backgroundColor = bg,
                        textColor = com.manhwalens.app.render.ColorSampler.bestTextColorOn(bg)
                    )
                    paintLedger.record(t.id)
                }
            }
            Log.i(TAG, paintLedger.line())
            val snap = snapshot()
            if (snap.isNotEmpty() && !attachRequested) attachRequested = true
            if (snap.isNotEmpty() && patchOverlay?.hasPatches() != true) {
                // Progressive path produced nothing (e.g. all patch builds failed or were
                // winners of a late race) — paint the whole page in one batch instead.
                val patches = buildPatches(bitmap, snap)
                val elapsed = SystemClock.elapsedRealtime() - tapStartMs
                mainHandler.post {
                    if (patches.isNotEmpty()) {
                        patchOverlayEnsure().replaceAll(patches, bitmap.width, bitmap.height)
                        Log.i(TAG, "painter finish: batch-swapped ${patches.size} patch(es) (elapsed=${elapsed} ms)")
                    }
                }
            }
        }
    }

    private fun patchOverlayEnsure(): com.manhwalens.app.overlay.PatchOverlayWindow =
        patchOverlay ?: com.manhwalens.app.overlay.PatchOverlayWindow(this, windowManager).also { patchOverlay = it }

    /**
     * Rasterize ALL boxes into small patch bitmaps (worker-thread safe).
     *
     * v0.11.0: boxes that share one visual area are folded BEFORE rendering — the owner
     * draws the Arabic over the union cover, and its siblings render nothing. Without this
     * a bubble OCR split into two blocks got two overlapping patches whose edges smeared
     * into each other and left half the English uncovered.
     */
    private fun buildPatches(
        source: Bitmap,
        boxes: List<com.manhwalens.app.render.BubbleRenderBox>
    ): List<com.manhwalens.app.render.BubblePatch> {
        val byId = boxes.associateBy { it.id }
        // Blocks that share one area keep their Arabic inside their own band of the cover.
        val blockBounds = boxes.associate { b ->
            b.id to com.manhwalens.app.ocr.geometry.Rect2D(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()
            )
        }
        val areas = com.manhwalens.app.render.PatchPlanner.areas(
            boxes.map {
                it.id to com.manhwalens.app.ocr.geometry.Rect2D(
                    it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()
                )
            },
            source.width, source.height
        )
        val planned = com.manhwalens.app.render.PatchPlanner.renders(areas)
        if (planned.size != boxes.size) {
            com.manhwalens.app.util.PipelineTracer.span(
                "patch fold", 0,
                com.manhwalens.app.render.PatchPlanner.describe(boxes.size, areas)
            )
        }
        val out = ArrayList<com.manhwalens.app.render.BubblePatch>(planned.size)
        for ((ownerId, cover) in planned) {
            val box = byId[ownerId] ?: continue
            try {
                com.manhwalens.app.render.PatchSurface.build(
                    applicationContext, source, box, cover = cover, band = blockBounds[box.id]
                )?.let { out += it }
            } catch (oom: OutOfMemoryError) {
                Log.e("ManhwaLens", "patch build OOM for #${box.id} — bubble skipped", oom)
            } catch (e: Exception) {
                Log.e("ManhwaLens", "patch build failed for #${box.id} — ${e.javaClass.name}: ${e.message}", e)
            }
        }
        return out
    }

    /**
     * Part D — AI cleanup (experimental, cloud): AFTER the local patches are on
     * screen, ask Gemini to re-render the ≤3 LARGEST bubbles with the translated
     * Arabic baked into the bubble art. Translate-now only — never called from
     * the live path. Sends ONLY the cropped bubble (+10 px margin) with the
     * user's key; 15 s timeout, ONE attempt; on any failure the local patch
     * stays (silently). The cleanup result is scaled into the LOCAL patch rect
     * and swapped in place.
     */
    private fun maybeScheduleAiCleanup(frame: Bitmap, boxes: List<com.manhwalens.app.render.BubbleRenderBox>) {
        val settings = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        if (!settings.aiCleanupEnabled) return
        val prefsT = com.manhwalens.app.data.TranslationPrefs(applicationContext)
        if (!prefsT.hasGeminiKey) {
            Log.i(TAG, "ai cleanup: enabled but no Gemini key — kept local patches")
            return
        }
        if (prefsT.geminiCircuit.isOpen()) {
            Log.i(TAG, "ai cleanup: Gemini circuit open (paused) — kept local patches")
            return
        }
        val candidates = com.manhwalens.app.render.LargestBoxes.select(
            boxes.filter { it.arabic.isNotBlank() }, 3
        )
        if (candidates.isEmpty()) return
        // Copy the crop plans now (frame pixels may vanish on the next capture).
        val plans = candidates.map { box ->
            val l = (box.left - 10).coerceIn(0, frame.width - 1)
            val t = (box.top - 10).coerceIn(0, frame.height - 1)
            val r = (box.right + 10).coerceIn(l + 1, frame.width)
            val b = (box.bottom + 10).coerceIn(t + 1, frame.height)
            box to android.graphics.Rect(l, t, r, b)
        }
        ioExecutor.execute {
            val t0 = SystemClock.elapsedRealtime()
            val model = settings.aiCleanupModelId.takeIf { it.isNotBlank() }
                ?: resolveImageCapableModelId(prefsT.geminiKey)
            if (model == null) {
                Log.w(TAG, "ai cleanup: no image-capable model for this key — kept local patches")
                PipelineTracer.span("ai cleanup", 0, "skipped — no image-capable model in account list (cloud)")
                return@execute
            }
            val api = com.manhwalens.app.translate.gemini.HttpUrlConnectionGeminiApi(
                prefsT.geminiKey, AI_CLEANUP_READ_TIMEOUT_MS
            )
            var replaced = 0
            for ((box, rect) in plans) {
                val cropped = try {
                    Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
                } catch (e: Exception) {
                    Log.i(TAG, "ai cleanup: crop failed for #${box.id} — kept local patch (${e.javaClass.simpleName})")
                    null
                } ?: continue
                val jpeg = try {
                    java.io.ByteArrayOutputStream().use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    runCatching { cropped.recycle() }
                }
                if (jpeg == null) continue
                when (val result = com.manhwalens.app.translate.gemini.GeminiImageEditor.editBubble(api, model, jpeg, box.arabic)) {
                    is com.manhwalens.app.translate.gemini.ImageEditResult.Success -> {
                        val decoded = android.graphics.BitmapFactory.decodeByteArray(result.imageBytes, 0, result.imageBytes.size)
                        val target = patchOverlay?.patchRect(box.id)
                        if (decoded != null && target != null) {
                            val tw = (target.right - target.left).toInt().coerceAtLeast(1)
                            val th = (target.bottom - target.top).toInt().coerceAtLeast(1)
                            val scaled = if (decoded.width == tw && decoded.height == th) decoded
                                else Bitmap.createScaledBitmap(decoded, tw, th, true)
                            patchOverlay?.replaceBitmap(box.id, scaled)
                            if (scaled !== decoded) decoded.recycle()
                            replaced++
                        } else {
                            decoded?.let { runCatching { it.recycle() } }
                            Log.i(TAG, "ai cleanup: bubble #${box.id} — patch gone already, result discarded")
                        }
                    }
                    is com.manhwalens.app.translate.gemini.ImageEditResult.Failure -> {
                        Log.i(TAG, "ai cleanup: bubble #${box.id} kept local patch (${result.reason})")
                    }
                }
            }
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "ai cleanup used (cloud) · replaced $replaced/${plans.size} bubble(s) · model=$model · ${ms} ms")
            PipelineTracer.span("ai cleanup", ms, "AI cleanup used (cloud) · replaced $replaced/${plans.size} · model=$model")
        }
    }

    /** In-memory cache for the auto-picked image-capable model (avoids a listModels round-trip per page). */
    @Volatile private var imageModelCache: Pair<String, String>? = null // apiKey → modelId
    @Volatile private var imageModelCacheAtMs: Long = 0L

    /** Pick the first image-capable model from the account's live models list (never hardcoded). Cached 30 min per key. */
    private fun resolveImageCapableModelId(apiKey: String): String? {
        val now = SystemClock.elapsedRealtime()
        imageModelCache?.let { (k, id) -> if (k == apiKey && now - imageModelCacheAtMs < 30 * 60_000L) return id }
        return try {
            val resp = com.manhwalens.app.translate.gemini.HttpUrlConnectionGeminiApi(apiKey).listModels()
            if (resp.code !in 200..299) {
                Log.w(TAG, "ai cleanup: listModels http ${resp.code}")
                null
            } else {
                val list = com.manhwalens.app.translate.gemini.GeminiWire.imageCapableModels(
                    com.manhwalens.app.translate.gemini.GeminiWire.parseModelsBody(resp.body)
                )
                val picked = list.firstOrNull()?.let { com.manhwalens.app.translate.gemini.GeminiWire.shortModelId(it.name) }
                if (picked != null) {
                    imageModelCache = apiKey to picked
                    imageModelCacheAtMs = now
                }
                picked
            }
        } catch (e: Exception) {
            Log.w(TAG, "ai cleanup: listModels failed — ${e.javaClass.name}: ${e.message}")
            null
        }
    }

    /** Keep the tap-capture frame ONLY for Review mode (Live captures never retain frames). */
    private fun retainReviewFrame(frame: Bitmap, meta: CaptureMeta) {
        synchronized(frameLock) {
            val old = lastReviewFrame
            if (old != null && old !== frame) runCatching { if (!old.isRecycled) old.recycle() }
            lastReviewFrame = frame
            lastReviewMeta = meta
        }
    }

    /**
     * Shared long-press / menu / notification semantics: enable = arm Live, disable =
     * disarm + clear patches. Part B: enabling WITHOUT a capture session asks for consent
     * first and remembers the intent ([pendingLiveEnable]) — Live then switches on by itself
     * the moment the session is armed (see [handleConsentToken]).
     */
    private fun setLiveMode(enable: Boolean, source: String = "menu") {
        if (enable && com.manhwalens.app.live.LiveAutoStart.needsConsentBeforeLiveEnable(mediaProjection != null)) {
            pendingLiveEnable = true
            pendingTapMode = null   // an explicit Live ON request — nothing else to replay
            Log.i(TAG, "live enable requested ($source) with no capture session — requesting consent first")
            PipelineTracer.span("live consent", 0, "Live ON requested ($source) — capture consent first")
            launchConsent()
            return
        }
        pendingLiveEnable = false
        val settings = com.manhwalens.app.data.SettingsPrefs(applicationContext)
        settings.liveMode = enable
        settings.liveOptOut = !enable
        if (enable) {
            liveStandby = false
            liveScreenOff = false
            liveNeedsConsentToastShown = false
            liveDriver.reset()
            liveForegroundAllowed = true
            ensureLiveLoopStarted("live enabled ($source)")
            setLiveStatus(com.manhwalens.app.live.LiveStatus.WAITING)
            mainHandler.post { Toast.makeText(this, R.string.live_enabled_toast, Toast.LENGTH_LONG).show() }
            Log.i(TAG, "live mode enabled ($source)")
        } else {
            liveDriver.invalidateSamePage()
            setLiveStatus(com.manhwalens.app.live.LiveStatus.PAUSED)
            mainHandler.post {
                Toast.makeText(this, R.string.live_disabled_toast, Toast.LENGTH_LONG).show()
            }
            Log.i(TAG, "live mode disabled ($source) — existing patches kept; clear via lens menu if needed")
        }
        refreshNotification()
    }

    /** Long-press on the lens: quick Live toggle with haptic feedback + toast (Part C). */
    private fun toggleLiveFromLongPress() {
        val on = !com.manhwalens.app.data.SettingsPrefs(applicationContext).liveMode
        runCatching {
            button?.performHapticFeedback(
                if (on) android.view.HapticFeedbackConstants.LONG_PRESS
                else android.view.HapticFeedbackConstants.KEYBOARD_TAP
            )
        }
        Log.i(TAG, "lens long-pressed — toggling Live to ${if (on) "ON" else "OFF"}")
        setLiveMode(on, "long-press")
    }

    /**
     * Lens menu (Part C): a single tap on the lens opens it next to the button. It is the
     * visible Live ON/OFF surface (v0.9.3 only opened it on a long press, which now toggles
     * Live directly). All window work happens on the main thread.
     */
    private fun openLensMenu() {
        com.manhwalens.app.util.MainThread.run {
            val s = com.manhwalens.app.data.SettingsPrefs(applicationContext)
            val menu = lensMenuWindow
                ?: com.manhwalens.app.overlay.LensMenuWindow(this, windowManager).also { lensMenuWindow = it }
            // v0.9.13: the menu opening means a capture is about a second away — reload the
            // ML Kit model now if it has been idle (a cold first call cost ~650 ms for 2 bubbles).
            if (com.manhwalens.app.translate.MlKitWarmth.ageMs(System.currentTimeMillis()) > 20_000L) {
                runCatching {
                    ioExecutor.execute { com.manhwalens.app.translate.mlkit.MlKitTranslator.keepWarm("menu opened") }
                }
            }
            val screen = screenSize()
            val buttonSizePx = (56 * resources.displayMetrics.density).toInt()
            menu.patchesShowing = { patchOverlay?.hasVisibleOrFading() == true }
            menu.toggle(
                liveOn = s.liveMode,
                patchesOn = patchOverlay?.isArabicVisible() ?: true,
                reviewAvailable = synchronized(frameLock) { lastReviewFrame != null },
                anchor = com.manhwalens.app.overlay.LensMenuWindow.Anchor(
                    x = layoutParams.x,
                    y = layoutParams.y,
                    width = button?.width?.takeIf { it > 0 } ?: buttonSizePx,
                    height = button?.height?.takeIf { it > 0 } ?: buttonSizePx,
                    screenWidth = screen.x,
                    screenHeight = screen.y
                ),
                callbacks = object : com.manhwalens.app.overlay.LensMenuWindow.Callbacks {
                    override fun onToggleLive(enable: Boolean) {
                        setLiveMode(enable, "menu switch")
                        // Consent may have taken over: refresh the switch from the real state.
                        mainHandler.postDelayed({
                            val now = com.manhwalens.app.data.SettingsPrefs(applicationContext)
                            lensMenuWindow?.updateState(now.liveMode, patchOverlay?.isArabicVisible() ?: true)
                        }, 250L)
                    }
                    override fun onTranslateNow() = translateNow()
                    override fun onToggleArabic(showArabic: Boolean) {
                        patchOverlay?.setArabicVisible(showArabic)
                        mainHandler.post {
                            Toast.makeText(
                                this@FloatingButtonService,
                                if (showArabic) R.string.live_toggle_arabic else R.string.live_toggle_original,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    override fun onClearPatches() {
                        patchOverlay?.clearPatches("user clear")
                        // The page is back to the original — the next Stable pass must re-translate.
                        liveDriver.invalidateSamePage()
                        mainHandler.post {
                            Toast.makeText(this@FloatingButtonService, R.string.toast_patches_cleared, Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onReviewCapture() = openReviewMode()
                    override fun onStopEverything() = stopEverything()
                    override fun onOpenSettings() = openSettingsScreen()
                },
                // v0.9.5 Part 2: the menu's own "Live status" line, refreshed once per second.
                liveStatus = { liveStatusLine() }
            )
        }
    }

    /** Review mode — the ONLY consumer of the full-frame viewer (tap captures). */
    private fun openReviewMode() {
        val frame = synchronized(frameLock) { lastReviewFrame }
        val meta = synchronized(frameLock) { lastReviewMeta }
        if (frame == null || meta == null) {
            Toast.makeText(this, R.string.toast_review_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val clone = runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (clone == null) {
            Toast.makeText(this, R.string.toast_review_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val rw = reviewWindow ?: CaptureDebugWindow(this, windowManager).also { reviewWindow = it }
        rw.show(clone, meta, touchable = true)
    }

    private fun openSettingsScreen() {
        // Clear any lingering patches from the manga page so they don't remain
        // visible over the Settings screen (both are the same app, so app-visible
        // gating doesn't fire between them).
        patchOverlay?.clearPatches("settings")
        val intent = Intent(this, com.manhwalens.app.ui.settings.SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "could not open SettingsActivity — ${it.javaClass.name}: ${it.message}", it) }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4) {
            throw CaptureException("unsupported pixelStride=$pixelStride (expected 4 for RGBA_8888)")
        }
        val buffer = plane.buffer
        val pixels = IntArray(width * height)
        val row = ByteArray(width * 4)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row)
            var i = 0
            var p = y * width
            while (i < row.size) {
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                val a = row[i + 3].toInt() and 0xFF
                pixels[p] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i += 4
                p++
            }
        }
        buffer.clear()
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * True only for a protected (FLAG_SECURE) frame: a dense 40×40 sample of the middle 84 %
     * of the frame is PURE black. v0.9.11 — the old 3×3 "darker than 32" test skipped every
     * dark page. See [com.manhwalens.app.util.FrameDarkness.isProtectedBlack].
     */
    private fun isNearlyBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        return try {
            val n = com.manhwalens.app.util.FrameDarkness.DENSE_GRID
            val samples = IntArray(n * n)
            var i = 0
            for (gy in 0 until n) {
                val y = (h * (0.08f + 0.84f * (gy + 0.5f) / n)).toInt().coerceIn(0, h - 1)
                for (gx in 0 until n) {
                    val x = (w * (gx + 0.5f) / n).toInt().coerceIn(0, w - 1)
                    samples[i++] = bitmap.getPixel(x, y)
                }
            }
            lastBlackCheck = "${com.manhwalens.app.util.FrameDarkness.nonBlackCount(samples)}/${samples.size} samples not black"
            com.manhwalens.app.util.FrameDarkness.isProtectedBlack(samples)
        } catch (e: Exception) {
            Log.e(TAG, "black-frame sampling failed — treating as not black — ${e.javaClass.name}: ${e.message}", e)
            false
        }
    }

    /** Result of the last black-frame check, for the trace. */
    @Volatile private var lastBlackCheck = ""

    /**
     * A pure-black frame can also be a stale first frame of the mirror (delivered before any
     * content was composited; a still page never sends another). Detach + re-attach the reader
     * surface — the same legal restart the no-frame watchdog uses — and wait for a fresh frame.
     * Returns it, or null when none arrived.
     */
    private fun refetchFrameAfterBlack(): Bitmap? {
        val display = sessionVirtualDisplay ?: return null
        val surface = sessionReader?.surface ?: return null
        val t0 = SystemClock.elapsedRealtime()
        return try {
            synchronized(frameLock) { latestImage?.close(); latestImage = null }
            display.setSurface(null)
            display.setSurface(surface)
            val deadline = t0 + BLACK_REFETCH_WAIT_MS
            while (lastFrameArrivedAtMs <= t0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(15L)
            if (lastFrameArrivedAtMs > t0) Thread.sleep(CLEAN_CAPTURE_SETTLE_MS)
            val fresh = takeLatestFrame()?.let { image -> try { imageToBitmap(image) } finally { image.close() } }
            PipelineTracer.span(
                "black frame re-fetch", SystemClock.elapsedRealtime() - t0,
                if (fresh != null) "mirror restarted — fresh frame received" else "mirror restarted — no new frame"
            )
            fresh
        } catch (e: Throwable) {
            Log.w(TAG, "black-frame re-fetch failed — ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap, path: String) {
        val file = File(path)
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "Bitmap.compress returned false" }
        }
        Log.i(TAG, "capture saved: ${file.absolutePath} (${file.length()} bytes)")
    }

    private inner class DragToRepositionListener(private val view: View) : View.OnTouchListener {

        private var downRawX = 0f
        private var downRawY = 0f
        private var initialX = 0
        private var initialY = 0
        private var downTimeMs = 0L
        private var dragging = false
        /** Part C: ≥450 ms = long press = quick Live toggle. */
        private val LONG_PRESS_MS = 450L
        private val touchSlop: Int
            get() = android.view.ViewConfiguration.get(this@FloatingButtonService).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    downTimeMs = android.os.SystemClock.uptimeMillis()
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // Dragging starts → an open menu would follow the lens around: close it.
                        lensMenuWindow?.dismiss(com.manhwalens.app.overlay.LensMenuModel.CloseReason.DRAG)
                    }
                    if (dragging) {
                        layoutParams.x = (initialX + dx).toInt()
                        layoutParams.y = (initialY + dy).toInt()
                        clampVertical(view)
                        runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // A drag moves the lens — the menu must never open while dragging.
                        snapToNearestHorizontalEdge(view)
                        lensMenuWindow?.dismiss(com.manhwalens.app.overlay.LensMenuModel.CloseReason.DRAG)
                    } else if (android.os.SystemClock.uptimeMillis() - downTimeMs >= LONG_PRESS_MS) {
                        // Part C: long press = quick Live toggle (haptic + toast).
                        toggleLiveFromLongPress()
                    } else {
                        onTap()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToNearestHorizontalEdge(view)
                    return true
                }
            }
            return false
        }
    }

    private class CaptureException(message: String) : Exception(message)
}
