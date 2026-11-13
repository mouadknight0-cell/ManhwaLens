package com.manhwalens.app.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.manhwalens.app.R
import com.manhwalens.app.data.SettingsPrefs
import com.manhwalens.app.util.ErrorLog
import com.manhwalens.app.ui.theme.LensVioletSoft
import com.manhwalens.app.ui.theme.PanelAmber

/**
 * Settings screen — single scrolling page:
 *  Appearance (Arabic font size) · Translation (engine + key) · Automation
 *  (auto-capture on scroll) · About (version, privacy policy).
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    var showLastCapture by remember { mutableStateOf(false) }
    var showLiveSelfCheck by remember { mutableStateOf(false) }

    if (showPrivacy) {
        PrivacyPolicyScreen(onBack = { showPrivacy = false })
        return
    }

    // v0.9.5 Part 2: Settings → Diagnostics → Live self-check.
    if (showLiveSelfCheck) {
        LiveSelfCheckScreen(onBack = { showLiveSelfCheck = false })
        return
    }

    if (showLastCapture) {
        LastCaptureDialog(onDismiss = { showLastCapture = false })
    }

    val prefs = remember { SettingsPrefs(context) }
    var fontScale by remember { mutableStateOf(prefs.fontScale) }
    var autoCapture by remember { mutableStateOf(prefs.autoCaptureOnScroll) }
    var debugMode by remember { mutableStateOf(prefs.debugMode) }
    var liveMode by remember { mutableStateOf(prefs.liveMode) }
    var liveApps by remember { mutableStateOf(prefs.liveAllowedApps) }
    var ignoreTop by remember { mutableStateOf(prefs.ignoreTopPct) }
    var ignoreBottom by remember { mutableStateOf(prefs.ignoreBottomPct) }
    var liveStableMs by remember { mutableStateOf(prefs.liveStableWindowMs) }
    var liveSensitivity by remember { mutableStateOf(prefs.liveSensitivity) }
    var lensTapMode by remember { mutableStateOf(prefs.lensTapMode) }
    var ignorePhrases by remember { mutableStateOf(prefs.ignorePhrases) }
    val translationPrefs = remember { com.manhwalens.app.data.TranslationPrefs(context) }
    var glossaryText by remember { mutableStateOf(translationPrefs.glossary) }
    var mergeLines by remember { mutableStateOf(translationPrefs.mergeBubbleLines) }
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkResult by remember { mutableStateOf<String?>(null) }
    val benchmarkReport = benchmarkResult
    if (benchmarkReport != null) {
        BenchmarkDialog(
            report = benchmarkReport,
            onCopy = {
                runCatching {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("benchmark", benchmarkReport))
                    Toast.makeText(context, R.string.benchmark_copied, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { benchmarkResult = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("←  " + stringResource(R.string.settings_back), color = LensVioletSoft)
            }
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.size(14.dp))

        // ---------- Appearance ----------
        Section(stringResource(R.string.appearance_title)) {
            Text(
                text = stringResource(R.string.font_size_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChip(stringResource(R.string.font_small), fontScale == SettingsPrefs.SMALL) {
                    fontScale = SettingsPrefs.SMALL; prefs.fontScale = SettingsPrefs.SMALL
                }
                FontChip(stringResource(R.string.font_medium), fontScale == SettingsPrefs.MEDIUM) {
                    fontScale = SettingsPrefs.MEDIUM; prefs.fontScale = SettingsPrefs.MEDIUM
                }
                FontChip(stringResource(R.string.font_large), fontScale == SettingsPrefs.LARGE) {
                    fontScale = SettingsPrefs.LARGE; prefs.fontScale = SettingsPrefs.LARGE
                }
            }
            Text(
                text = stringResource(R.string.font_size_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Translation (existing card) ----------
        TranslationEngineCard()
        Spacer(Modifier.size(12.dp))

        // ---------- Names & terms (v0.9.14) ----------
        Section(stringResource(R.string.glossary_title)) {
            Text(
                text = stringResource(R.string.glossary_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = glossaryText,
                onValueChange = { value ->
                    glossaryText = value
                    translationPrefs.glossary = value
                    translationPrefs.refreshGlossary()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                minLines = 5,
                placeholder = { Text("Wei Wuxian = وي ووشيان") }
            )
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.merge_lines_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.merge_lines_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(
                    checked = mergeLines,
                    onCheckedChange = { checked ->
                        mergeLines = checked
                        translationPrefs.mergeBubbleLines = checked
                        translationPrefs.refreshGlossary()
                    }
                )
            }
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Live mode (Part C) ----------
        Section(stringResource(R.string.live_mode_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.live_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.live_mode_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(
                    checked = liveMode,
                    onCheckedChange = { checked ->
                        liveMode = checked
                        prefs.liveMode = checked
                        prefs.liveOptOut = !checked
                        Toast.makeText(
                            context,
                            if (checked) R.string.live_enabled_toast else R.string.live_disabled_toast,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.live_apps_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.live_apps_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            androidx.compose.material3.OutlinedTextField(
                value = liveApps,
                onValueChange = { value ->
                    liveApps = value
                    prefs.liveAllowedApps = value
                },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.live_apps_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Spacer(Modifier.size(6.dp))
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent("android.settings.USAGE_ACCESS_SETTINGS")) }
                    .onFailure { Toast.makeText(context, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show() }
            }) {
                Text(stringResource(R.string.live_apps_grant))
            }
            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.live_stability_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.live_stability_value, liveStableMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = LensVioletSoft
                )
            }
            Text(
                text = stringResource(R.string.live_stability_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            androidx.compose.material3.Slider(
                value = liveStableMs.toFloat(),
                onValueChange = {
                    val v = it.toInt().coerceIn(200, 1000)
                    liveStableMs = v
                    prefs.liveStableWindowMs = v
                },
                valueRange = 200f..1000f,
                steps = 15
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.live_sensitivity_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.live_sensitivity_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensitivityChip(stringResource(R.string.live_sensitivity_low), liveSensitivity == SettingsPrefs.SENS_LOW) {
                    liveSensitivity = SettingsPrefs.SENS_LOW; prefs.liveSensitivity = SettingsPrefs.SENS_LOW
                }
                SensitivityChip(stringResource(R.string.live_sensitivity_medium), liveSensitivity == SettingsPrefs.SENS_MEDIUM) {
                    liveSensitivity = SettingsPrefs.SENS_MEDIUM; prefs.liveSensitivity = SettingsPrefs.SENS_MEDIUM
                }
                SensitivityChip(stringResource(R.string.live_sensitivity_high), liveSensitivity == SettingsPrefs.SENS_HIGH) {
                    liveSensitivity = SettingsPrefs.SENS_HIGH; prefs.liveSensitivity = SettingsPrefs.SENS_HIGH
                }
            }
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Tap on the lens (v0.9.4 Part C) ----------
        Section(stringResource(R.string.lens_tap_title)) {
            Text(
                text = stringResource(R.string.lens_tap_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensitivityChip(stringResource(R.string.lens_tap_menu), lensTapMode == SettingsPrefs.TAP_MENU) {
                    lensTapMode = SettingsPrefs.TAP_MENU; prefs.lensTapMode = SettingsPrefs.TAP_MENU
                }
                SensitivityChip(stringResource(R.string.lens_tap_translate), lensTapMode == SettingsPrefs.TAP_TRANSLATE) {
                    lensTapMode = SettingsPrefs.TAP_TRANSLATE; prefs.lensTapMode = SettingsPrefs.TAP_TRANSLATE
                }
            }
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Frame edges (Part D) ----------
        Section(stringResource(R.string.edge_ignore_title)) {
            Text(
                text = stringResource(R.string.edge_ignore_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.edge_ignore_top, ignoreTop.toInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            androidx.compose.material3.Slider(
                value = ignoreTop,
                onValueChange = {
                    ignoreTop = it
                    prefs.ignoreTopPct = it
                },
                valueRange = 0f..30f,
                steps = 29
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.edge_ignore_bottom, ignoreBottom.toInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            androidx.compose.material3.Slider(
                value = ignoreBottom,
                onValueChange = {
                    ignoreBottom = it
                    prefs.ignoreBottomPct = it
                },
                valueRange = 0f..30f,
                steps = 29
            )
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Ignore list (Part C) ----------
        Section(stringResource(R.string.ignore_list_title)) {
            Text(
                text = stringResource(R.string.ignore_list_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = ignorePhrases,
                onValueChange = { value ->
                    ignorePhrases = value
                    prefs.ignorePhrases = value
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                minLines = 5
            )
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Automation (optional extra trigger; Live mode needs nothing here) ----------
        Section(stringResource(R.string.automation_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.auto_capture_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.auto_capture_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(
                    checked = autoCapture,
                    onCheckedChange = { checked ->
                        autoCapture = checked
                        prefs.autoCaptureOnScroll = checked
                    }
                )
            }
            Spacer(Modifier.size(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B26)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.faster_scroll_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = PanelAmber
                    )
                    Text(
                        text = stringResource(R.string.faster_scroll_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.faster_scroll_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LensVioletSoft
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }.onFailure {
                                Toast.makeText(context, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text(stringResource(R.string.faster_scroll_appinfo))
                        }
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                .onFailure { Toast.makeText(context, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show() }
                        }) {
                            Text(stringResource(R.string.auto_capture_open))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Diagnostics ----------
        Section(stringResource(R.string.diagnostics_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.debug_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.debug_mode_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(
                    checked = debugMode,
                    onCheckedChange = { checked ->
                        debugMode = checked
                        prefs.debugMode = checked
                    }
                )
            }
            Spacer(Modifier.size(10.dp))
            // ---- Live self-check (v0.9.5 Part 2) ----
            Text(
                text = stringResource(R.string.self_check_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(6.dp))
            OutlinedButton(onClick = { showLiveSelfCheck = true }) {
                Text(stringResource(R.string.self_check_button))
            }
            Spacer(Modifier.size(14.dp))
            Text(
                text = stringResource(R.string.benchmark_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(6.dp))
            OutlinedButton(
                enabled = !benchmarkRunning,
                onClick = {
                    benchmarkRunning = true
                    Thread {
                        val report = try {
                            // v0.9.9: same shared, pre-warmed client the pipeline uses (no
                            // second engine instance); warmUp() makes sure the model is there.
                            val policy = com.manhwalens.app.translate.TranslatorFactory.ensureBatchPolicy(context)
                            val mlkit = com.manhwalens.app.translate.mlkit.MlKitTranslator
                            if (!mlkit.isModelReady() && !mlkit.warmUp()) {
                                throw IllegalStateException("EN→AR model is not downloaded yet (needs internet once)")
                            }
                            val result = com.manhwalens.app.translate.BenchmarkRunner.run(
                                translateOne = { text -> mlkit.translateRaw(text) }
                            )
                            // The benchmark is the strongest proof available on this device:
                            // adopt its verdict, unless joined mode is serving a failure ban.
                            val verdict = result.recommend()
                            policy.recordParallelLevel(result.bestPerBlockLevel())
                            val current = policy.current()
                            val currentRow = result.strategies.firstOrNull { it.strategy != null && it.strategy == current }
                            val adopted = when {
                                currentRow != null && !currentRow.mappedOk -> {
                                    policy.noteJoinedFailure(currentRow.strategy!!, "benchmark: ${currentRow.detail}")
                                    "runtime separator ${currentRow.strategy.label} FAILED here → that separator is banned for 7 days"
                                }
                                policy.isJoinedBlocked() -> "joined stays disabled (earlier mismatches) — verdict not applied"
                                verdict.strategy != null && policy.isBanned(verdict.strategy) -> {
                                    policy.recordChoice(null, "benchmark: best separator is banned after a real-page mismatch")
                                    "best separator ${verdict.strategy.label} is banned (failed on a real page) → parallel"
                                }
                                else -> {
                                    policy.recordChoice(verdict.strategy, "benchmark: " + verdict.why)
                                    "verdict applied"
                                }
                            }
                            result.toText(
                                listOf(
                                    "Runtime now: " + policy.describe(),
                                    "($adopted)",
                                    "Warm state: " + com.manhwalens.app.translate.MlKitWarmth.describe(System.currentTimeMillis()),
                                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
                                )
                            )
                        } catch (e: Exception) {
                            "Benchmark could not run: ${e.javaClass.simpleName} — ${e.message}"
                        }
                        // Kept for the self-check "Copy report" (survives the dialog being closed).
                        runCatching {
                            java.io.File(
                                context.filesDir,
                                com.manhwalens.app.overlay.FloatingButtonService.BENCHMARK_REPORT_FILE
                            ).writeText(report)
                        }
                        runCatching { com.manhwalens.app.util.ErrorLog.writeRaw(context, "benchmark", report) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            benchmarkRunning = false
                            benchmarkResult = report
                        }
                    }.start()
                }
            ) {
                Text(if (benchmarkRunning) stringResource(R.string.benchmark_running) else stringResource(R.string.benchmark_button))
            }
            Spacer(Modifier.size(10.dp))
            val captureFile = remember { java.io.File(context.filesDir, "manhwalens-last-capture.jpg") }
            if (captureFile.exists()) {
                OutlinedButton(onClick = { showLastCapture = true }) {
                    Text(stringResource(R.string.view_last_capture))
                }
            } else {
                Text(
                    text = stringResource(R.string.view_last_capture_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Last error ----------
        Section(stringResource(R.string.last_error_title)) {
            val lastError = remember { ErrorLog.read(context) }
            if (lastError == null) {
                Text(
                    text = stringResource(R.string.last_error_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 14
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = {
                    try {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("ManhwaLens error", lastError))
                        Toast.makeText(context, R.string.toast_error_copied, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("ManhwaLens", "clipboard copy failed — ${e.javaClass.name}: ${e.message}", e)
                    }
                }) {
                    Text(stringResource(R.string.copy_error_button))
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.last_error_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(12.dp))

        // ---------- Pipeline trace (debug panel content, available in-app) ----------
        Section(stringResource(R.string.pipeline_trace_title)) {
            val pipelineTrace = remember { ErrorLog.read(context) }
            if (pipelineTrace == null) {
                Text(
                    text = stringResource(R.string.pipeline_trace_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = pipelineTrace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 14,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = {
                    try {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("ManhwaLens pipeline trace", pipelineTrace))
                        Toast.makeText(context, R.string.toast_trace_copied, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("ManhwaLens", "clipboard copy failed — ${e.javaClass.name}: ${e.message}", e)
                    }
                }) {
                    Text(stringResource(R.string.copy_trace_button))
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.pipeline_trace_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(12.dp))

        // ---------- About ----------
        Section(stringResource(R.string.about_title)) {
            val version = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrDefault("?")
            }
            Text(
                text = stringResource(R.string.version_label) + ":  " + version,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(6.dp))
            HorizontalDivider(color = Color(0xFF23232F))
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = { showPrivacy = true }) {
                Text(stringResource(R.string.privacy_policy_title), color = LensVioletSoft)
            }
            Text(
                text = stringResource(R.string.privacy_policy_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.size(28.dp))
    }
}

@Composable
private fun BenchmarkDialog(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(Color(0xEE14141D))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.benchmark_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFFC45E)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE8E6F5),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy) { Text(stringResource(R.string.benchmark_copy)) }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.benchmark_close), color = LensVioletSoft)
                }
            }
        }
    }
}

@Composable
private fun LastCaptureDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(
                java.io.File(context.filesDir, "manhwalens-last-capture.jpg").absolutePath
            )
        }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(Color(0xEE14141D))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = stringResource(R.string.view_last_capture_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE8E6F5)
            )
            Spacer(Modifier.size(8.dp))
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.view_last_capture_title),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = stringResource(R.string.view_last_capture_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA9A6BC)
                )
            }
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = LensVioletSoft)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(10.dp))
            content()
        }
    }
}

@Composable
private fun SensitivityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) LensVioletSoft else Color(0xFF1F1F2A)
    val content = if (selected) Color(0xFF14141D) else Color(0xFFC9C6DA)
    androidx.compose.material3.Button(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content
        ),
        modifier = Modifier.heightIn(min = 40.dp)
    ) {
        Text(label)
    }
}

@Composable
private fun FontChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF8B7CF6) else Color(0xFF23232F)
    val fg = if (selected) Color.White else Color(0xFFA9A6BC)
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        color = bg,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("←  " + stringResource(R.string.settings_back), color = LensVioletSoft)
        }
        Text(
            text = stringResource(R.string.privacy_policy_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.privacy_policy_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(28.dp))
    }
}
