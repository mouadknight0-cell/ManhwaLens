package com.manhwalens.app.capture

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.manhwalens.app.R
import com.manhwalens.app.overlay.FloatingButtonService
import com.manhwalens.app.util.ErrorLog

/**
 * No-display consent trampoline for the MediaProjection token.
 *
 * Launched from the floating overlay (which requires SYSTEM_ALERT_WINDOW, an
 * Android 10+ exemption for background activity starts). On grant it hands the
 * token to FloatingButtonService, which promotes itself to a foreground
 * service of type mediaProjection BEFORE calling getMediaProjection() — the
 * required order on Android 14+.
 */
class CaptureConsentActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    if (result.data != null) {
                        Log.i(TAG, "consent granted; forwarding token to capture service")
                        startService(
                            Intent(this, FloatingButtonService::class.java)
                                .setAction(FloatingButtonService.ACTION_START_WITH_TOKEN)
                                .putExtra(FloatingButtonService.EXTRA_RESULT_CODE, result.resultCode)
                                .putExtra(FloatingButtonService.EXTRA_DATA, result.data)
                        )
                    } else {
                        Log.w(TAG, "consent result OK but data is null")
                        Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_LONG).show()
                    }
                }
                else -> {
                    // RESULT_CANCELED — user denied (or dismissed) the dialog.
                    Log.w(TAG, "consent denied or dismissed (resultCode=${result.resultCode})")
                    Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_LONG).show()
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 8+: capture consent requires the caller to be able to show UI;
        // we are a translucent activity, that is enough.
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        Log.i(TAG, "requesting screen capture consent")
        try {
            launcher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            ErrorLog.write(this, "consent-dialog", e)
            Log.e("ManhwaLens", "failed to launch MediaProjection consent dialog — ${e.javaClass.name}: ${e.message}", e)
            Toast.makeText(this, getString(R.string.toast_capture_failed_reason, ErrorLog.reasonOf(e)), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        private const val TAG = "MLCapture"
    }
}
