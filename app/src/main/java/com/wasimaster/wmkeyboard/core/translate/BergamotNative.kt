package com.wasimaster.wmkeyboard.core.translate

import android.os.Build
import android.os.Process

/**
 * JNI bridge to Mozilla Bergamot (Firefox Translations), pinned to the
 * wmkeyboard bridge build. The .so is fetched by CI, never bundled in source.
 */
internal object BergamotNative {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28 && Process.is64Bit()

    /** Load only on supported devices; unsupported ABIs must not poison class initialization. */
    val available: Boolean by lazy {
        if (!supported) false
        else runCatching { System.loadLibrary("wmbergamot"); true }.getOrDefault(false)
    }

    external fun nativeNewService(cacheSize: Int): Long
    external fun nativeFreeService(handle: Long)
    external fun nativeLoadModel(model: String, lex: String?, vocabs: Array<String>, int8shiftAll: Boolean): Long
    external fun nativeFreeModel(handle: Long)
    external fun nativeTranslate(service: Long, model: Long, texts: Array<ByteArray>): Array<ByteArray>
    external fun nativeUnzstd(source: String, target: String): String?
}
