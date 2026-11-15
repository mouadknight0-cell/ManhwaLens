package com.manhwalens.app.translate.bergamot

import android.content.Context
import com.wasimaster.wmkeyboard.core.translate.BergamotNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Mozilla Firefox Translations English→Arabic model, release 3.0.
 * Download size ~25.2 MB; unpacked size ~36.5 MB. Every compressed and
 * unpacked file is SHA-256 checked before it can be used.
 */
internal object BergamotEnArModel {
    private const val BASE = "https://firefox-settings-attachments.cdn.mozilla.net/main-workspace/translations-models-v2/"
    private const val VERSION = "3.0"
    private const val COMPLETE = ".complete"

    private data class Asset(
        val name: String,
        val path: String,
        val packedSize: Long,
        val packedSha: String,
        val rawSize: Long,
        val rawSha: String
    )

    private val assets = listOf(
        Asset("model.enar.intgemm.alphas.bin", "0d56ac93-c0ac-4854-b48e-09e38d51dace.zst", 22272394L,
            "41dc919c1110508ad7f03ee53bbb28a54abd61d1b15bbc3fe51c5df5c9331016", 31561787L,
            "ae659d7045fc2e5d6ba50583586a5d94ee4358d92c019847e37b57a0e627faa8"),
        Asset("lex.50.50.enar.s2t.bin", "6b7c1061-729a-44e3-858d-409a7061a704.zst", 1204697L,
            "f5fe1d8d145e38b4c6fd9f7a35d0d5779388fa135743561e1d25e982d7191222", 3139692L,
            "2b7194817c5dd9225ca90c0d908cc92e1d6d1f45625781a5feb037ac568d6a61"),
        Asset("vocab.enar.spm", "371728a2-8c02-4528-bd76-4cd1c4e8f05e.zst", 351089L,
            "9f68a65a64d4c64ea41d94024160e1443ff51b6c7ba0a2f93e7702beaa0b1d25", 863591L,
            "8d93c54aa5e2044c416ec680b5ff9af0227bd698521666e8b1a1ea1b041fbae8")
    )

    fun directory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, "bergamot/en-ar/$VERSION")

    fun isInstalled(context: Context): Boolean {
        val dir = directory(context)
        return File(dir, COMPLETE).isFile && assets.all { File(dir, it.name).length() == it.rawSize }
    }

    suspend fun download(context: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val dir = directory(context)
            check(BergamotNative.supported && BergamotNative.available) { "Bergamot runtime requires Android 9+ and a supported 64-bit ARM device" }
            dir.mkdirs()
            File(dir, COMPLETE).delete()
            val total = assets.sumOf { it.packedSize }
            var completed = 0L
            assets.forEach { asset ->
                val target = File(dir, asset.name)
                if (target.isFile && target.length() == asset.rawSize && sha256(target) == asset.rawSha) {
                    completed += asset.packedSize
                    onProgress(completed, total)
                    return@forEach
                }
                val packed = File(dir, "${asset.name}.zst.part")
                downloadFile(BASE + asset.path, packed, asset.packedSize)
                check(sha256(packed) == asset.packedSha) { "Checksum mismatch: ${asset.name} download" }
                val unpacked = File(dir, "${asset.name}.tmp")
                val error = BergamotNative.nativeUnzstd(packed.absolutePath, unpacked.absolutePath)
                packed.delete()
                check(error == null && unpacked.length() == asset.rawSize && sha256(unpacked) == asset.rawSha) {
                    "Checksum or decompression failure: ${asset.name}"
                }
                check(unpacked.renameTo(target)) { "Could not install ${asset.name}" }
                completed += asset.packedSize
                onProgress(completed, total)
            }
            File(dir, COMPLETE).writeText(VERSION)
        }

    private fun downloadFile(address: String, output: File, expectedSize: Long) {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Model server returned HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                output.outputStream().buffered().use { out -> input.copyTo(out) }
            }
            check(output.length() == expectedSize) { "Unexpected model file size" }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
