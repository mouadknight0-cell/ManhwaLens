package com.manhwalens.app.ui.settings

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
import com.manhwalens.app.util.TranslationPairLog
import com.manhwalens.app.ui.theme.LensVioletSoft
import com.manhwalens.app.ui.theme.PanelAmber

/**
 * Settings screen — single scrolling page:
 *  Appearance · Translation engines and saved translations · Diagnostics · About.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    var showLastCapture by remember { mutableStateOf(false) }
    var showLiveSelfCheck by remember { mutableStateOf(false) }
    var showClearTranslationCache by remember { mutableStateOf(false) }
    var cacheEntryCount by remember { mutableStateOf(com.manhwalens.app.translate.TranslatorFactory.cacheSize(context)) }

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

    if (showClearTranslationCache) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearTranslationCache = false },
            title = { Text(stringResource(R.string.translation_cache_reset_title)) },
            text = { Text(stringResource(R.string.translation_cache_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    com.manhwalens.app.translate.TranslatorFactory.clearCache(context)
                    cacheEntryCount = 0
                    showClearTranslationCache = false
                    Toast.makeText(context, R.string.translation_cache_reset_done, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.translation_cache_reset_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearTranslationCache = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    val prefs = remember { SettingsPrefs(context) }
    var fontScale by remember { mutableStateOf(prefs.fontScale) }
    var debugMode by remember { mutableStateOf(prefs.debugMode) }
    var pairLogCount by remember { mutableStateOf(TranslationPairLog.count(context)) }
    var lensTapMode by remember { mutableStateOf(prefs.lensTapMode) }
    val translationPrefs = remember { com.manhwalens.app.data.TranslationPrefs(context) }
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

        // Prepare the EN→AR model before going offline.
        OfflineTranslationModelCard()
        Spacer(Modifier.size(12.dp))
        BergamotModelCard()
        Spacer(Modifier.size(12.dp))

        // Translation result cache — separate from glossary, name memory and diagnostics.
        Section(stringResource(R.string.translation_cache_section)) {
            Text(
                text = stringResource(R.string.translation_cache_summary, cacheEntryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = { showClearTranslationCache = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.translation_cache_reset_action))
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
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.translation_pairs_note, pairLogCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        pairLogCount = TranslationPairLog.count(context)
                        val report = TranslationPairLog.readReport(context)
                        if (report.isNullOrBlank()) {
                            pairLogCount = 0
                            Toast.makeText(context, R.string.translation_pairs_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching {
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("ManhwaLens translation pairs", report))
                            }.onSuccess {
                                Toast.makeText(context, R.string.translation_pairs_copied, Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text(stringResource(R.string.translation_pairs_copy)) }
                TextButton(
                    onClick = {
                        TranslationPairLog.clear(context)
                        pairLogCount = 0
                        Toast.makeText(context, R.string.translation_pairs_cleared, Toast.LENGTH_SHORT).show()
                    }
                ) { Text(stringResource(R.string.translation_pairs_clear), color = MaterialTheme.colorScheme.error) }
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
                            // Keep the shared ML Kit client exclusive for the whole batching
                            // diagnostic. Parallel worker callbacks bypass the read gate only
                            // because they are owned by this exclusive diagnostic lease.
                            val policy = com.manhwalens.app.translate.TranslatorFactory.ensureBatchPolicy(context)
                            val mlkit = com.manhwalens.app.translate.mlkit.MlKitTranslator
                            mlkit.withDiagnosticLock {
                                if (!mlkit.warmUpForBenchmark("ML Kit batching benchmark")) {
                                    throw IllegalStateException("EN→AR model is not downloaded yet (needs internet once)")
                                }
                                val raw = mlkit::translateRawForExclusiveDiagnostic
                                val result = com.manhwalens.app.translate.BenchmarkRunner.run(translateOne = raw)
                                // Fixed EN→AR reference corpus: report guard acceptance and lexical
                                // overlap separately; it does not change the selected engine.
                                val qualityReport = com.manhwalens.app.translate.TranslationQualityBenchmark.run(
                                    translateOne = raw
                                )
                                // The batching benchmark intentionally adopts its measured verdict.
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
                                        "ML Kit batching policy now: " + policy.describe(),
                                        "($adopted)",
                                        "The selected offline translation engine is unchanged.",
                                        "Warm state: " + com.manhwalens.app.translate.MlKitWarmth.describe(System.currentTimeMillis()),
                                        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
                                        "",
                                        qualityReport.toText()
                                    )
                                )
                            }
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
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.bergamot_benchmark_summary),
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
                            if (!com.wasimaster.wmkeyboard.core.translate.BergamotNative.supported ||
                                !com.wasimaster.wmkeyboard.core.translate.BergamotNative.available) {
                                throw IllegalStateException("Bergamot native runtime is not supported on this device")
                            }
                            if (!com.manhwalens.app.translate.bergamot.BergamotEnArModel.isInstalled(context)) {
                                throw IllegalStateException("Download the Bergamot EN→AR model in Offline translation settings first")
                            }
                            val bergamot = com.manhwalens.app.translate.bergamot.BergamotEnArTranslator(context)
                            try {
                                com.manhwalens.app.translate.EngineComparisonBenchmark.runSingleEngine(
                                    engineName = "Bergamot",
                                    translateBatch = { inputs ->
                                        kotlinx.coroutines.runBlocking { bergamot.translate(inputs) }
                                    },
                                    dispatchDescription = "one native multi-input request per pass; Bergamot only"
                                ).toText() +
                                    "\\n\\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
                            } finally {
                                bergamot.release()
                            }
                        } catch (e: Exception) {
                            "Bergamot-only benchmark could not run: ${e.javaClass.simpleName} — ${e.message}"
                        }
                        runCatching {
                            java.io.File(context.filesDir, com.manhwalens.app.overlay.FloatingButtonService.BENCHMARK_REPORT_FILE).writeText(report)
                        }
                        runCatching { com.manhwalens.app.util.ErrorLog.writeRaw(context, "bergamot_only_benchmark", report) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            benchmarkRunning = false
                            benchmarkResult = report
                        }
                    }.start()
                }
            ) {
                Text(if (benchmarkRunning) stringResource(R.string.benchmark_running) else stringResource(R.string.bergamot_benchmark_button))
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.engine_comparison_summary),
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
                            val mlkit = com.manhwalens.app.translate.mlkit.MlKitTranslator
                            if (!com.wasimaster.wmkeyboard.core.translate.BergamotNative.supported ||
                                !com.wasimaster.wmkeyboard.core.translate.BergamotNative.available) {
                                throw IllegalStateException("Bergamot native runtime is not supported on this device")
                            }
                            if (!com.manhwalens.app.translate.bergamot.BergamotEnArModel.isInstalled(context)) {
                                throw IllegalStateException("Download the Bergamot EN→AR model in Offline translation settings first")
                            }
                            val bergamot = com.manhwalens.app.translate.bergamot.BergamotEnArTranslator(context)
                            try {
                                // Hold the diagnostic lock across ML Kit pre-warm AND every measured
                                // pass so a pending background separator self-test cannot overlap.
                                val comparison = com.manhwalens.app.translate.mlkit.MlKitTranslator.withDiagnosticLock {
                                    if (!mlkit.warmUpForBenchmark("ML Kit vs Bergamot comparison")) {
                                        throw IllegalStateException("ML Kit EN→AR model is not ready; connect to Wi-Fi once and retry")
                                    }
                                    val batchPolicy = com.manhwalens.app.translate.TranslatorFactory.ensureBatchPolicy(context)
                                    val mlKitParallelLevel = batchPolicy.parallelLevel()
                                    val joinedStrategy = batchPolicy.current()
                                    val dispatchDescription = joinedStrategy?.let {
                                        "joined ${it.label} when safe and within 3000 characters; fallback parallel x$mlKitParallelLevel"
                                    } ?: "parallel x$mlKitParallelLevel (saved ML Kit policy)"
                                    val observedDispatches = java.util.Collections.synchronizedSet(mutableSetOf<String>())
                                    val comparisonResult = com.manhwalens.app.translate.EngineComparisonBenchmark.run(
                                        translateMlKitBatch = { inputs ->
                                            val result = com.manhwalens.app.translate.MlKitDiagnosticBatch.translate(
                                                inputs = inputs,
                                                parallelLevel = mlKitParallelLevel,
                                                joinedStrategy = joinedStrategy,
                                                translateOne = mlkit::translateRawForExclusiveDiagnostic
                                            )
                                            observedDispatches.add(result.dispatch)
                                            result.outputs
                                        },
                                        translateBergamotBatch = { inputs ->
                                            kotlinx.coroutines.runBlocking { bergamot.translate(inputs) }
                                        },
                                        mlKitDispatchDescription = dispatchDescription
                                    )
                                    comparisonResult to synchronized(observedDispatches) {
                                        observedDispatches.joinToString("; ")
                                    }
                                }
                                comparison.first.toText() +
                                    "\nML Kit dispatch observed: " + comparison.second +
                                    "\n\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
                            } finally {
                                bergamot.release()
                            }
                        } catch (e: Exception) {
                            "Engine comparison could not run: ${e.javaClass.simpleName} — ${e.message}"
                        }
                        runCatching {
                            java.io.File(context.filesDir, com.manhwalens.app.overlay.FloatingButtonService.BENCHMARK_REPORT_FILE).writeText(report)
                        }
                        runCatching { com.manhwalens.app.util.ErrorLog.writeRaw(context, "engine_comparison", report) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            benchmarkRunning = false
                            benchmarkResult = report
                        }
                    }.start()
                }
            ) {
                Text(if (benchmarkRunning) stringResource(R.string.benchmark_running) else stringResource(R.string.engine_comparison_button))
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
            val lastError = remember { ErrorLog.readLastError(context) }
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
