package com.manhwalens.app.translate.bergamot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal, serialized JNI adapter for English→Arabic. It intentionally stays
 * separate from TranslatorFactory until device benchmarks validate quality,
 * latency, memory and native compatibility.
 */
internal class BergamotEnArTranslator(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    private var service = 0L
    private var model = 0L

    suspend fun translate(texts: List<String>): List<String> = withContext(Dispatchers.Default) {
        require(texts.all { it.length <= 2500 }) { "Each segment must be at most 2500 characters" }
        check(BergamotEnArModel.isInstalled(app)) { "Download the English→Arabic offline model first" }
        synchronized(lock) {
            if (service == 0L) service = BergamotNative.nativeNewService(256)
            if (model == 0L) {
                val dir = BergamotEnArModel.directory(app)
                model = BergamotNative.nativeLoadModel(
                    File(dir, "model.enar.intgemm.alphas.bin").absolutePath,
                    File(dir, "lex.50.50.enar.s2t.bin").absolutePath,
                    arrayOf(File(dir, "vocab.enar.spm").absolutePath),
                    false
                )
            }
            val input = texts.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
            BergamotNative.nativeTranslate(service, model, input).map { it.toString(Charsets.UTF_8) }
        }
    }

    fun release() {
        synchronized(lock) {
            if (model != 0L) BergamotNative.nativeFreeModel(model)
            if (service != 0L) BergamotNative.nativeFreeService(service)
            model = 0L
            service = 0L
        }
    }
}
