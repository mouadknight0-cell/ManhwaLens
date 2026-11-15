package com.manhwalens.app.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.manhwalens.app.translate.bergamot.BergamotEnArModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional Firefox Translations model download; not included in the APK. */
@Composable
fun BergamotModelCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(BergamotEnArModel.isInstalled(context)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember {
        mutableStateOf(
            if (ready) "Bergamot English → Arabic model is ready offline."
            else "Optional Firefox translation model · about 25 MB download, about 36.5 MB storage."
        )
    }

    LaunchedEffect(Unit) { ready = BergamotEnArModel.isInstalled(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Bergamot · Firefox Translations", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(6.dp))
                Text(
                    "محرك ترجمة تجريبي يعمل على الهاتف دون اتصال. لا يُحمّل داخل APK؛ نزّل النموذج مرة واحدة. يتطلب Android 9 أو أحدث ومعالجاً بنظام 64-bit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall)
                if (busy) {
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && !ready,
                        onClick = {
                            busy = true
                            progress = 0f
                            message = "جارٍ تنزيل نموذج Bergamot والتحقق من سلامته…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        BergamotEnArModel.download(context) { done, total ->
                                            Handler(Looper.getMainLooper()).post {
                                                progress = if (total > 0) done.toFloat() / total else 0f
                                            }
                                        }
                                    }
                                }
                                ready = result.isSuccess && BergamotEnArModel.isInstalled(context)
                                message = if (ready) {
                                    "اكتمل التنزيل والتحقق. أصبح نموذج الإنجليزية ← العربية جاهزاً دون إنترنت."
                                } else {
                                    "فشل التنزيل: ${result.exceptionOrNull()?.message ?: "تعذر التحقق من النموذج"}. يمكنك إعادة المحاولة."
                                }
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "جارٍ التنزيل…" else if (ready) "النموذج جاهز" else "تنزيل النموذج") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            ready = BergamotEnArModel.isInstalled(context)
                            message = if (ready) "النموذج موجود وجاهز دون إنترنت." else "النموذج غير مثبت."
                        }
                    ) { Text("تحديث الحالة") }
                }
            }
        }
    }
}
