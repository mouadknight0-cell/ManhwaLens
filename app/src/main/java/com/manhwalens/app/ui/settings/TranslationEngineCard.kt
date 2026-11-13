package com.manhwalens.app.ui.settings

import android.os.Handler
import android.widget.Toast
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.manhwalens.app.R
import com.manhwalens.app.data.TranslationPrefs
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.TranslationBlock
import com.manhwalens.app.translate.TranslationEngine
import com.manhwalens.app.translate.TranslatorFactory
import com.manhwalens.app.translate.gemini.GeminiWire
import com.manhwalens.app.ui.theme.LensVioletSoft

/**
 * M3 settings: pick the translation engine (ML Kit on-device vs Gemini API),
 * paste a Gemini key (stored only in this app's private SharedPreferences —
 * never in source or logs), test it, and clear the shared cache.
 */
@Composable
fun TranslationEngineCard() {
    val context = LocalContext.current
    val prefs = remember { TranslationPrefs(context) }

    var engine by remember { mutableStateOf(prefs.engine) }
    var keyField by remember { mutableStateOf(prefs.geminiKey) }
    var savedTick by remember { mutableStateOf(0) }
    var libreEndpointField by remember { mutableStateOf(prefs.libreTranslateUrl) }
    var libreKeyField by remember { mutableStateOf(prefs.libreTranslateApiKey) }
    var libreSavedTick by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var modelsResult by remember { mutableStateOf<String?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    val settingsPrefs = remember { com.manhwalens.app.data.SettingsPrefs(context) }
    var aiCleanup by remember { mutableStateOf(settingsPrefs.aiCleanupEnabled) }
    var aiImageModel by remember { mutableStateOf(settingsPrefs.aiCleanupModelId) }
    var imageModels by remember { mutableStateOf<List<GeminiWire.GeminiModelInfo>>(emptyList()) }
    var aiListLoading by remember { mutableStateOf(false) }
    var aiListResult by remember { mutableStateOf<String?>(null) }
    var imageModelMenu by remember { mutableStateOf(false) }
    val hasGeminiKey = TranslationPrefs(context).hasGeminiKey
    var cacheLabel by remember { mutableStateOf("") }
    cacheLabel = "${TranslatorFactory.cacheSize()} cached" // cheap read, refreshed on actions

    fun select(e: TranslationEngine) {
        engine = e
        prefs.engine = e
        prefs.geminiCircuit.clear() // a deliberate engine change always gets a fresh try
        testResult = null
    }

    fun runTest() {
        if (testing) return
        testing = true
        testResult = null
        val modelBefore = prefs.geminiModel
        val appContext = context.applicationContext
        Thread {
            val translator = TranslatorFactory.create(appContext)
            val sample = listOf(
                TranslationBlock(1, "Hello! Welcome to our city."),
                TranslationBlock(2, "Take my hand, we are almost there.")
            )
            val r = translator.translatePage(sample)
            val msg = when (r) {
                is PageTranslation.Success ->
                    "${r.engineId} · ${r.elapsedMs} ms · cache ${r.cacheHits}/${r.cacheHits + r.cacheMisses}\n\n" +
                        r.blocks.joinToString("\n") { "#${it.id}  ${it.arabic}" }
                is PageTranslation.Failure ->
                    "${r.reason}\n${r.message}"
            }
            Handler(Looper.getMainLooper()).post {
                testing = false
                testResult = msg
                cacheLabel = "${TranslatorFactory.cacheSize()} cached"
                if (prefs.geminiModel != modelBefore) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_model_switched, prefs.geminiModel),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    fun checkModels() {
        if (checking) return
        val key = prefs.geminiKey
        if (key.isBlank()) {
            modelsResult = context.getString(R.string.gemini_key_placeholder)
            return
        }
        checking = true
        modelsResult = null
        val appContext = context.applicationContext
        Thread {
            val msg = try {
                val resp = com.manhwalens.app.translate.gemini.HttpUrlConnectionGeminiApi(key).listModels()
                if (resp.code in 200..299) {
                    val models = GeminiWire.parseModelsBody(resp.body)
                    if (models.isEmpty()) {
                        appContext.getString(R.string.check_models_none)
                    } else {
                        appContext.getString(R.string.check_models_title) + "\n" +
                            models.joinToString("\n") { it.name }
                    }
                } else {
                    val snippet = resp.body
                        .replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "[redacted]")
                        .take(160)
                    GeminiWire.reasonForHttp(resp.code).name + " · " +
                        GeminiWire.humanForHttp(resp.code, snippet)
                }
            } catch (e: Exception) {
                val reason = when (e) {
                    is java.net.UnknownHostException, is java.net.NoRouteToHostException,
                    is java.net.ConnectException, is java.net.SocketException,
                    is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
                        if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                            com.manhwalens.app.translate.FailureReason.TIMEOUT
                        else com.manhwalens.app.translate.FailureReason.NO_INTERNET
                    else -> com.manhwalens.app.translate.FailureReason.UNKNOWN
                }
                android.util.Log.e("ManhwaLens", "checkModels failed — ${e.javaClass.name}: ${e.message}", e)
                reason.name + " · " + (e.message ?: e.javaClass.simpleName)
            }
            Handler(Looper.getMainLooper()).post {
                checking = false
                modelsResult = msg
            }
        }.start()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.translate_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            EngineOption(
                title = stringResource(R.string.engine_mlkit),
                caption = stringResource(R.string.engine_mlkit_caption),
                selected = engine == TranslationEngine.ML_KIT,
                onSelect = { select(TranslationEngine.ML_KIT) }
            )
            EngineOption(
                title = stringResource(R.string.engine_gemini),
                caption = stringResource(R.string.engine_gemini_caption),
                selected = engine == TranslationEngine.GEMINI,
                onSelect = { select(TranslationEngine.GEMINI) }
            )
            EngineOption(
                title = stringResource(R.string.engine_libre_translate),
                caption = stringResource(R.string.engine_libre_translate_caption),
                selected = engine == TranslationEngine.LIBRE_TRANSLATE,
                onSelect = { select(TranslationEngine.LIBRE_TRANSLATE) }
            )

            if (engine == TranslationEngine.GEMINI) {
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = keyField,
                    onValueChange = { keyField = it; savedTick = 0 },
                    label = { Text(stringResource(R.string.gemini_key_label)) },
                    placeholder = { Text(stringResource(R.string.gemini_key_placeholder)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.gemini_key_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { prefs.geminiKey = keyField; prefs.geminiCircuit.clear(); savedTick = 1 }) {
                        Text(stringResource(R.string.gemini_save))
                    }
                    TextButton(onClick = { prefs.clearGeminiKey(); keyField = ""; savedTick = 0 }) {
                        Text(stringResource(R.string.gemini_clear))
                    }
                    if (savedTick == 1) {
                        Text(
                            text = stringResource(R.string.gemini_key_saved),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.gemini_model_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.size(6.dp))
                Box {
                    OutlinedButton(onClick = { modelMenu = true }) {
                        Text(prefs.geminiModel)
                    }
                    DropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false }
                    ) {
                        GeminiWire.MODEL_OPTIONS.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(if (opt == prefs.geminiModel) "$opt ✓" else opt) },
                                onClick = {
                                    prefs.geminiModel = opt
                                    prefs.geminiCircuit.clear()
                                    modelMenu = false
                                    modelsResult = null
                                }
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.gemini_model_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { checkModels() }, enabled = !checking) {
                    Text(
                        if (checking) stringResource(R.string.check_models_running)
                        else stringResource(R.string.check_models)
                    )
                }
                modelsResult?.let { result ->
                    Spacer(Modifier.size(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(12.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            if (engine == TranslationEngine.LIBRE_TRANSLATE) {
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = libreEndpointField,
                    onValueChange = { libreEndpointField = it; libreSavedTick = false },
                    label = { Text(stringResource(R.string.libre_translate_url_label)) },
                    placeholder = { Text(stringResource(R.string.libre_translate_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = libreKeyField,
                    onValueChange = { libreKeyField = it; libreSavedTick = false },
                    label = { Text(stringResource(R.string.libre_translate_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    text = stringResource(R.string.libre_translate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = {
                        prefs.libreTranslateUrl = libreEndpointField
                        prefs.libreTranslateApiKey = libreKeyField
                        libreSavedTick = true
                        testResult = null
                    }) {
                        Text(stringResource(R.string.libre_translate_save))
                    }
                    TextButton(onClick = {
                        prefs.clearLibreTranslateSettings()
                        libreEndpointField = ""
                        libreKeyField = ""
                        libreSavedTick = false
                        testResult = null
                    }) {
                        Text(stringResource(R.string.libre_translate_clear))
                    }
                    if (libreSavedTick) {
                        Text(
                            text = stringResource(R.string.libre_translate_saved),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }
            }

            // ---------- AI cleanup (Part D, experimental, cloud) ----------
            Spacer(Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.ai_cleanup_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.ai_cleanup_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ai_cleanup_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Switch(
                    checked = aiCleanup && hasGeminiKey,
                    enabled = hasGeminiKey,
                    onCheckedChange = { checked ->
                        aiCleanup = checked
                        settingsPrefs.aiCleanupEnabled = checked
                        Toast.makeText(
                            context,
                            if (checked) R.string.ai_cleanup_switch_toast_on else R.string.ai_cleanup_switch_toast_off,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
            if (!hasGeminiKey) {
                Text(
                    text = stringResource(R.string.ai_cleanup_needs_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.ai_cleanup_model_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.size(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        OutlinedButton(onClick = { imageModelMenu = true }) {
                            Text(
                                if (aiImageModel.isBlank()) stringResource(R.string.ai_cleanup_model_auto)
                                else aiImageModel
                            )
                        }
                        DropdownMenu(
                            expanded = imageModelMenu,
                            onDismissRequest = { imageModelMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_cleanup_model_auto)) },
                                onClick = {
                                    settingsPrefs.aiCleanupModelId = ""
                                    aiImageModel = ""
                                    imageModelMenu = false
                                }
                            )
                            imageModels.forEach { m ->
                                val id = GeminiWire.shortModelId(m.name)
                                DropdownMenuItem(
                                    text = { Text(if (id == aiImageModel) "$id ✓" else id) },
                                    onClick = {
                                        settingsPrefs.aiCleanupModelId = id
                                        aiImageModel = id
                                        imageModelMenu = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (aiListLoading) return@OutlinedButton
                            aiListLoading = true
                            aiListResult = null
                            val appContext = context.applicationContext
                            val key = TranslationPrefs(appContext).geminiKey
                            Thread {
                                var models: List<GeminiWire.GeminiModelInfo> = emptyList()
                                var msg: String? = null
                                try {
                                    val resp = com.manhwalens.app.translate.gemini.HttpUrlConnectionGeminiApi(key).listModels()
                                    if (resp.code in 200..299) {
                                        models = GeminiWire.imageCapableModels(GeminiWire.parseModelsBody(resp.body))
                                        msg = if (models.isEmpty()) appContext.getString(R.string.ai_cleanup_models_none) else null
                                    } else {
                                        val snippet = resp.body
                                            .replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "[redacted]")
                                            .take(120)
                                        msg = appContext.getString(R.string.ai_cleanup_models_failed, snippet)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ManhwaLens", "AI cleanup model list failed — ${e.javaClass.name}: ${e.message}", e)
                                    msg = appContext.getString(R.string.ai_cleanup_models_failed, e.javaClass.simpleName)
                                }
                                Handler(Looper.getMainLooper()).post {
                                    aiListLoading = false
                                    imageModels = models
                                    aiListResult = msg
                                }
                            }.start()
                        },
                        enabled = !aiListLoading
                    ) {
                        Text(
                            if (aiListLoading) stringResource(R.string.ai_cleanup_list_running)
                            else stringResource(R.string.ai_cleanup_list_models)
                        )
                    }
                }
                aiListResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.ai_cleanup_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.size(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { runTest() },
                    enabled = !testing
                ) {
                    Text(
                        if (testing) stringResource(R.string.test_running)
                        else stringResource(R.string.test_translate)
                    )
                }
                TextButton(onClick = {
                    TranslatorFactory.clearCache()
                    cacheLabel = "0 cached"
                    testResult = null
                }) {
                    Text(stringResource(R.string.cache_clear))
                }
            }
            Text(
                text = cacheLabel,
                style = MaterialTheme.typography.labelMedium,
                color = LensVioletSoft,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = stringResource(R.string.test_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            testResult?.let { result ->
                Spacer(Modifier.size(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(12.dp)
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineOption(
    title: String,
    caption: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
