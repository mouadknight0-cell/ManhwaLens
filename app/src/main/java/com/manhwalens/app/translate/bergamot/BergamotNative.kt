package com.manhwalens.app.translate.bergamot

import android.os.Build
import android.os.Process

/**
 * JNI bridge to Mozilla Bergamot (Firefox Translations), pinned to the
 * wmkeyboard bridge build. The .so is fetched by CI, never bundled in source.
 */
internal object BergamotNative {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28 && Process.is64Bit()

    init {
        check(supported) { "Bergamot requires Android 9+ and a 64-bit process" }
        System.loadLibrary("wmbergamot")
    }

    external fun nativeNewService(cacheSize: Int): Long
    external fun nativeFreeService(handle: Long)
    external fun nativeLoadModel(model: String, lex: String?, vocabs: Array<String>, int8shiftAll: Boolean): Long
    external fun nativeFreeModel(handle: Long)
    external fun nativeTranslate(service: Long, model: Long, texts: Array<ByteArray>): Array<ByteArray>
    external fun nativeUnzstd(source: String, target: String): String?
}
