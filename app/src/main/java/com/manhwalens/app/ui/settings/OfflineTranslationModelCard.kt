package com.manhwalens.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Explicitly prepares the dynamically-downloaded EN→AR model before the user goes offline.
 * ML Kit translation models are not bundled in the APK and are removed when the app is
 * uninstalled, so the UI makes the one-time download and current readiness visible.
 */
@Composable
fun OfflineTranslationModelCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { RemoteModelManager.getInstance() }
    val model = remember {
        TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
    }
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var offlineMode by remember { mutableStateOf(com.manhwalens.app.data.TranslationPrefs(context).offlineMode) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("جارٍ التحقق من نموذج الترجمة…") }

    suspend fun refreshStatus() {
        val result = withContext(Dispatchers.IO) {
            runCatching { Tasks.await(manager.isModelDownloaded(model), 20, TimeUnit.SECONDS) }
        }
        ready = result.getOrNull()
        message = when {
            result.isFailure -> "تعذر التحقق من النموذج: ${result.exceptionOrNull()?.message ?: "خطأ غير معروف"}"
            ready == true -> "نموذج الإنجليزية ← العربية جاهز. يمكنك الترجمة دون إنترنت."
            else -> "النموذج غير موجود. نزّله قبل استخدام التطبيق دون إنترنت (حوالي 30 MB)."
        }
    }

    LaunchedEffect(Unit) { refreshStatus() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("الترجمة دون إنترنت", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(6.dp))
            Text(
                "يوفر التطبيق محرك ML Kit المحلي، ويمكنك اختيار Bergamot التجريبي أيضاً. نزّل نموذج المحرك الذي تختاره مرة واحدة، ثم تتم الترجمة على الهاتف دون إرسال النصوص إلى خادم.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("فرض الترجمة دون إنترنت", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "عند التفعيل: تُستخدم المحركات المحلية فقط (ML Kit أو Bergamot عند اختياره)، مع تعطيل Gemini والترقية السحابية.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = offlineMode,
                    onCheckedChange = { enabled ->
                        offlineMode = enabled
                        com.manhwalens.app.data.TranslationPrefs(context).offlineMode = enabled
                    }
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && ready != true,
                    onClick = {
                        scope.launch {
                            busy = true
                            message = "جارٍ تنزيل نموذج الترجمة عبر اتصال الإنترنت…"
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    Tasks.await(
                                        manager.download(
                                            model,
                                            DownloadConditions.Builder().build()
                                        ),
                                        20,
                                        TimeUnit.MINUTES
                                    )
                                    Tasks.await(manager.isModelDownloaded(model), 30, TimeUnit.SECONDS)
                                }
                            }
                            ready = result.getOrNull()
                            message = when {
                                result.isFailure -> "فشل التنزيل أو لم يكتمل: ${result.exceptionOrNull()?.message ?: "خطأ غير معروف"}"
                                ready == true -> "اكتمل تنزيل النموذج. الترجمة الإنجليزية ← العربية جاهزة دون إنترنت."
                                else -> "لم يؤكد ML Kit اكتمال تنزيل النموذج. تحقق من اتصال الإنترنت والمساحة المتاحة ثم أعد المحاولة."
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(if (busy) "جارٍ التنزيل…" else "تنزيل النموذج")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { refreshStatus() } }
                ) {
                    Text("تحديث الحالة")
                }
            }
        }
    }
}
