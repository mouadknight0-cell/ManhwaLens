package com.manhwalens.app.translate.bergamot

import android.content.Context
import com.wasimaster.wmkeyboard.core.translate.BergamotNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Serialized JNI adapter for the optional English→Arabic engine. Device benchmarks
 * are still required before Bergamot can replace ML Kit as the recommended default.
 */
internal class BergamotEnArTranslator(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    private var service = 0L
    private var model = 0L
    private var warmedUp = false

    suspend fun translate(texts: List<String>): List<String> = withContext(Dispatchers.Default) {
        require(texts.all { it.length <= 2500 }) { "Each segment must be at most 2500 characters" }
        check(BergamotNative.supported && BergamotNative.available) { "Bergamot runtime is unavailable on this device" }
        check(BergamotEnArModel.isInstalled(app)) { "Download the English→Arabic offline model first" }
        synchronized(lock) {
            ensureReadyLocked()
            val input = texts.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
            val output = BergamotNative.nativeTranslate(service, model, input)
            check(output.size == texts.size) {
                "Bergamot returned ${output.size} translations for ${texts.size} source blocks"
            }
            warmedUp = true
            output.map { it.toString(Charsets.UTF_8) }
        }
    }

    /**
     * Load the native model and run one tiny throwaway translation before the reader
     * starts a page. Called from Settings on a background dispatcher after download.
     * This moves JNI/model startup cost out of the first visible translation.
     */
    fun warmUp() {
        check(BergamotNative.supported && BergamotNative.available) { "Bergamot runtime is unavailable on this device" }
        check(BergamotEnArModel.isInstalled(app)) { "Download the English→Arabic offline model first" }
        synchronized(lock) {
            if (warmedUp) return
            ensureReadyLocked()
            val output = BergamotNative.nativeTranslate(
                service, model, arrayOf("Hello.".toByteArray(Charsets.UTF_8))
            )
            check(output.size == 1) { "Bergamot warm-up returned an invalid result" }
            warmedUp = true
        }
    }

    /** Must be called while [lock] is held. */
    private fun ensureReadyLocked() {
        if (service == 0L) {
            service = BergamotNative.nativeNewService(256)
            check(service != 0L) { "Bergamot could not create its translation service" }
        }
        if (model == 0L) {
            val dir = BergamotEnArModel.directory(app)
            model = BergamotNative.nativeLoadModel(
                File(dir, "model.enar.intgemm.alphas.bin").absolutePath,
                File(dir, "lex.50.50.enar.s2t.bin").absolutePath,
                arrayOf(File(dir, "vocab.enar.spm").absolutePath),
                false
            )
            check(model != 0L) { "Bergamot could not load the English→Arabic model" }
        }
    }

    fun release() {
        synchronized(lock) {
            if (model != 0L) BergamotNative.nativeFreeModel(model)
            if (service != 0L) BergamotNative.nativeFreeService(service)
            model = 0L
            service = 0L
            warmedUp = false
        }
    }
}
