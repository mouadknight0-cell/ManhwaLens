package com.manhwalens.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import com.manhwalens.app.data.OverlayPrefs
import com.manhwalens.app.overlay.FloatingButtonService
import com.manhwalens.app.util.ErrorLog
import com.manhwalens.app.util.SafeMode
import com.manhwalens.app.util.StartupGuard
import java.io.File
import com.manhwalens.app.ui.components.LensGlyph
import com.manhwalens.app.ui.theme.LensVioletSoft
import com.manhwalens.app.ui.theme.ManhwaLensTheme
import com.manhwalens.app.ui.theme.PanelAmber

class MainActivity : ComponentActivity() {

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not, capture flow re-checks in M2 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ManhwaLensTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        requestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(requestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { OverlayPrefs(context) }
    // Crash-loop guard: Safe Mode means "never start services here" — the user exits via the banner.
    val safeMode = remember { SafeMode.active }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var buttonOn by remember {
        mutableStateOf(prefs.enabled && Settings.canDrawOverlays(context) && !safeMode)
    }

    // Re-check permission every time we come back to the app (e.g. from Settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                buttonOn = prefs.enabled && overlayGranted && !SafeMode.active
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // If the user previously left the button enabled, bring it back with the app.
    LaunchedEffect(Unit) {
        if (prefs.enabled && Settings.canDrawOverlays(context) && !SafeMode.active) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, FloatingButtonService::class.java)
                )
            } catch (e: Exception) {
                Log.e("ManhwaLens", "failed to start floating button service — ${e.javaClass.name}: ${e.message}", e)
                prefs.enabled = false
                buttonOn = false
            }
        }
    }

    // One-shot from the service: FGS promotion failed (or overlay revoked at
    // start) → flip the toggle OFF instead of leaving it stuck ON.
    LaunchedEffect(Unit) {
        com.manhwalens.app.data.OverlayPrefs.serviceFailure.collect { reason ->
            if (!reason.isNullOrBlank()) {
                prefs.enabled = false
                buttonOn = false
                com.manhwalens.app.data.OverlayPrefs.serviceFailure.value = null
            }
        }
    }

    fun grantOverlay() {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
            )
        } catch (e: ActivityNotFoundException) {
            // No settings screen on this device; nothing sensible to fall back to.
        }
    }

    fun setButton(on: Boolean) {
        if (on) {
            if (SafeMode.active) {
                Toast.makeText(context, R.string.safe_mode_blocked_toast, Toast.LENGTH_LONG).show()
                return
            }
            if (!Settings.canDrawOverlays(context)) return
            prefs.enabled = true
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, FloatingButtonService::class.java)
                )
            } catch (e: Exception) {
                val reason = com.manhwalens.app.util.ErrorLog.reasonOf(e)
                Log.e("ManhwaLens", "failed to start floating button service — ${e.javaClass.name}: ${e.message}", e)
                Toast.makeText(context, context.getString(R.string.toast_fgs_failed_reason, reason), Toast.LENGTH_LONG).show()
                prefs.enabled = false
                buttonOn = false
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission()
            }
        } else {
            prefs.enabled = false
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
        buttonOn = on
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B0B12),
                        Color(0xFF141024),
                        Color(0xFF0B0B12)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            if (safeMode) {
                SafeModeBanner(
                    errorText = remember { ErrorLog.read(context) },
                    onCopy = { text ->
                        try {
                            val clip = context.getSystemService(ClipboardManager::class.java)
                            clip?.setPrimaryClip(ClipData.newPlainText("manhwalens_safe_mode", text))
                            Toast.makeText(context, R.string.safe_mode_copied, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("ManhwaLens", "safe mode copy failed", e)
                        }
                    },
                    onExit = {
                        try {
                            StartupGuard(File(context.filesDir, StartupGuard.FILE_NAME)).markHealthy()
                            SafeMode.active = false
                        } catch (e: Exception) {
                            Log.e("ManhwaLens", "safe mode exit failed", e)
                        }
                        (context as? Activity)?.recreate()
                    }
                )
                Spacer(Modifier.size(12.dp))
            }
            Header()

            Spacer(Modifier.size(18.dp))

            PermissionCard(granted = overlayGranted, onGrant = ::grantOverlay)

            Spacer(Modifier.size(12.dp))

            FloatingButtonCard(
                granted = overlayGranted,
                enabled = buttonOn,
                onToggle = { setButton(!buttonOn) }
            )

            Spacer(Modifier.size(12.dp))

            SettingsEntryCard(onOpen = {
                context.startActivity(Intent(context, com.manhwalens.app.ui.settings.SettingsActivity::class.java))
            })

            Spacer(Modifier.size(22.dp))
            Text(
                text = stringResource(R.string.footer_version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun Header() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LensGlyph(modifier = Modifier.size(84.dp).padding(top = 8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.labelMedium,
            color = PanelAmber,
            modifier = Modifier.padding(top = 4.dp)
        )
        PhasePill(stringResource(R.string.phase_m5))
    }
}

@Composable
private fun PhasePill(text: String) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = LensVioletSoft,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun StatusChip(granted: Boolean) {
    val bg = if (granted) Color(0xFF1E3A2F) else Color(0xFF3A2E1E)
    val fg = if (granted) Color(0xFF4ADE80) else PanelAmber
    Surface(shape = RoundedCornerShape(percent = 50), color = bg) {
        Text(
            text = if (granted) stringResource(R.string.perm_granted) else stringResource(R.string.perm_needed),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PermissionCard(granted: Boolean, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.perm_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                StatusChip(granted)
            }
            Text(
                text = stringResource(R.string.perm_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (!granted) {
                Button(
                    onClick = onGrant,
                    modifier = Modifier.padding(top = 14.dp)
                ) {
                    Text(stringResource(R.string.perm_grant))
                }
            }
        }
    }
}

@Composable
private fun FloatingButtonCard(granted: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = stringResource(R.string.floating_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        if (granted) R.string.floating_body else R.string.floating_needs_perm
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Switch(
                checked = enabled && granted,
                onCheckedChange = { onToggle() },
                enabled = granted
            )
        }
    }
}

@Composable
private fun SettingsEntryCard(onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.settings_entry_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.settings_entry_summary),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(onClick = onOpen, modifier = Modifier.padding(top = 14.dp)) {
                Text(stringResource(R.string.settings_entry_title))
            }
        }
    }
}




@Composable
private fun SafeModeBanner(errorText: String?, onCopy: (String) -> Unit, onExit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF4A1F1F)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.safe_mode_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFFFB4AB)
            )
            Text(
                text = stringResource(R.string.safe_mode_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = errorText?.lineSequence()?.take(14)?.joinToString("\n")
                    ?: stringResource(R.string.safe_mode_no_log),
                style = MaterialTheme.typography.bodySmall,
                color = PanelAmber,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF2A1414), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            )
            Row(
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { onCopy(errorText ?: "") },
                    enabled = !errorText.isNullOrBlank()
                ) {
                    Text(stringResource(R.string.safe_mode_copy))
                }
                Button(onClick = onExit) {
                    Text(stringResource(R.string.safe_mode_exit))
                }
            }
        }
    }
}
