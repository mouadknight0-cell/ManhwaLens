package com.manhwalens.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manhwalens.app.R
import com.manhwalens.app.live.LiveSelfCheck
import com.manhwalens.app.overlay.FloatingButtonService
import com.manhwalens.app.ui.theme.LensVioletSoft
import kotlinx.coroutines.delay

/**
 * Settings → Diagnostics → **Live self-check** (v0.9.5 · Part 2).
 *
 * Twelve PASS/FAIL/WAIT stages in pipeline order, refreshed every second while Live runs, so
 * one screen says exactly which stage stopped: permission → session → first frame → frames/s →
 * loop → last gate → tracker verdict → stash → OCR → translation → patches painted → last
 * removal reason. "Copy report" puts the same data plus the last 60 MLCapture/ManhwaLens log
 * lines (key-scrubbed) on the clipboard.
 *
 * All data comes from [FloatingButtonService.liveSelfCheckInput]; when the service is not
 * running every stage degrades to WAIT/FAIL with an explicit reason instead of crashing.
 */
@Composable
fun LiveSelfCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var stages by remember { mutableStateOf(emptyList<LiveSelfCheck.Stage>()) }
    var overall by remember { mutableStateOf(LiveSelfCheck.Result.WAIT) }
    var statusLine by remember { mutableStateOf("service not running") }
    var extraLines by remember { mutableStateOf(emptyList<String>()) }
    var report by remember { mutableStateOf<String?>(null) }
    var serviceAlive by remember { mutableStateOf(false) }

    // Refresh every second (the same cadence as the live loop's own diagnostics).
    LaunchedEffect(Unit) {
        while (true) {
            val svc = FloatingButtonService.instance
            serviceAlive = svc != null
            if (svc != null) {
                val input = runCatching { svc.liveSelfCheckInput() }.getOrNull()
                if (input != null) {
                    val built = LiveSelfCheck.stages(input)
                    stages = built
                    overall = LiveSelfCheck.overall(built)
                    statusLine = runCatching { svc.liveStatusLine() }.getOrDefault("Live: ?")
                    extraLines = runCatching { svc.liveSelfCheckExtraLines() }.getOrDefault(emptyList())
                } else {
                    statusLine = "service present but no snapshot"
                }
            } else {
                stages = emptyList()
                statusLine = "service not running — toggle the floating lens on, then Live ON"
                extraLines = emptyList()
            }
            delay(1_000L)
        }
    }

    val currentReport = report
    if (currentReport != null) {
        ReportDialog(
            report = currentReport,
            onCopy = {
                runCatching {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("manhwalens-live-self-check", currentReport))
                    android.widget.Toast.makeText(context, R.string.self_check_copied, android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { report = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("←  " + stringResource(R.string.settings_back), color = LensVioletSoft)
        }
        Text(
            text = stringResource(R.string.self_check_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.self_check_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(10.dp))

        // ---- overall + live status line ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xEE14141D))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = overall.wire,
                color = colorOf(overall),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = statusLine,
                    color = Color(0xFF9B8CFF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (serviceAlive) "refreshing every 1 s" else "no floating-lens service",
                    color = Color(0xFF8F8CA6),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.size(10.dp))

        OutlinedButton(
            onClick = {
                val svc = FloatingButtonService.instance
                report = if (svc != null) {
                    runCatching { svc.liveSelfCheckReport() }
                        .getOrElse { "report failed: ${it.javaClass.simpleName}: ${it.message}" }
                } else {
                    // No service → still produce a pasteable report from the in-memory ring.
                    buildString {
                        append("ManhwaLens Live self-check — floating lens service NOT running\n")
                        append("Toggle the lens on in the app, choose Live ON, then reopen this screen.\n")
                        append("— last ").append(com.manhwalens.app.util.LiveLog.recentLines(60).size)
                        append(" log lines —\n")
                        com.manhwalens.app.util.LiveLog.recentLines(60).forEach { append(it).append('\n') }
                        runCatching { FloatingButtonService.translationDiagnosticsLines(context) }
                            .getOrDefault(emptyList())
                            .forEach { append(it).append('\n') }
                    }.trim()
                }
            }
        ) {
            Text(stringResource(R.string.self_check_copy))
        }
        Spacer(Modifier.size(12.dp))

        // ---- the 12 stages ----
        if (stages.isEmpty()) {
            Text(
                text = stringResource(R.string.self_check_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        stages.forEach { stage ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stage.result.wire,
                    color = colorOf(stage.result),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.size(width = 46.dp, height = 18.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${stage.id}. ${stage.name}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stage.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (extraLines.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.self_check_state),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(4.dp))
            extraLines.forEach { line ->
                Text(
                    text = line,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

private fun colorOf(result: LiveSelfCheck.Result): Color = when (result) {
    LiveSelfCheck.Result.PASS -> Color(0xFF4ADE80)
    LiveSelfCheck.Result.FAIL -> Color(0xFFFF6E6E)
    LiveSelfCheck.Result.WAIT -> Color(0xFFFFC45E)
}

@Composable
private fun ReportDialog(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xEE14141D))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.self_check_report_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFFC45E)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE8E6F5),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy) { Text(stringResource(R.string.self_check_copy)) }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.benchmark_close), color = LensVioletSoft)
                }
            }
        }
    }
}
