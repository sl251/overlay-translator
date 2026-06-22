package com.gameocr.app.ocr

import android.content.Context
import com.gameocr.app.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * PaddleOCR PP-OCRv4 模型安装器。
 *
 * 三个文件 ([FILE_DET]/[FILE_REC]/[FILE_KEYS]) 按下载源列表依次尝试：
 * - 1. 用户在 settings 自定义的镜像 URL（如果填了）
 * - 2. 内置社区镜像（HuggingFace / ghproxy / jsdelivr）
 *
 * 每个文件独立 fallback，第一个 HTTP 200 的源就用。所有源都挂才报错。
 *
 * 这样用户开箱即用，不需要手动填 URL；只有所有公开源都挂掉时才需要自架镜像。
 */
@Singleton
class PaddleModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {

    val modelsDir: File by lazy { File(context.filesDir, "models/paddle").apply { mkdirs() } }

    data class InstalledFiles(val det: File, val rec: File, val keys: File)

    fun checkInstalled(): InstalledFiles? {
        val det = File(modelsDir, FILE_DET)
        val rec = File(modelsDir, FILE_REC)
        val keys = File(modelsDir, FILE_KEYS)
        return if (det.exists() && rec.exists() && keys.exists()) InstalledFiles(det, rec, keys) else null
    }

    data class Progress(
        val file: String,
        val mirror: String,
        val downloaded: Long,
        val total: Long,
        val done: Boolean,
        val error: String? = null
    )

    /** 下载 3 个文件，按文件级别 fallback；emit 进度。 */
    fun downloadAll(): Flow<Progress> = channelFlow {
        val userMirror = settingsRepository.get().paddleModelMirrorUrl.trim().takeIf { it.isNotBlank() }

        val plans: List<Triple<String, File, List<String>>> = listOf(
            Triple(FILE_DET, File(modelsDir, FILE_DET), urlsFor(userMirror, FILE_DET, DEFAULT_DET_URLS)),
            Triple(FILE_REC, File(modelsDir, FILE_REC), urlsFor(userMirror, FILE_REC, DEFAULT_REC_URLS)),
            Triple(FILE_KEYS, File(modelsDir, FILE_KEYS), urlsFor(userMirror, FILE_KEYS, DEFAULT_KEYS_URLS))
        )

        for ((name, dest, urls) in plans) {
            var ok = false
            var lastErr: String? = null
            for (url in urls) {
                val mirror = url.substringAfter("//").substringBefore("/")
                try {
                    downloadOne(url, dest, channel, name, mirror)
                    ok = true
                    send(Progress(name, mirror, dest.length(), dest.length(), true))
                    break
                } catch (t: Throwable) {
                    lastErr = "${t.javaClass.simpleName}: ${t.message}"
                    Timber.w(t, "镜像失败: $url")
                    send(Progress(name, mirror, 0, 0, false, error = lastErr))
                }
            }
            if (!ok) {
                send(Progress(name, "(all failed)", 0, 0, false, error = lastErr ?: "未知错误"))
                throw RuntimeException("$name 所有镜像都失败: $lastErr")
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun urlsFor(userMirror: String?, file: String, defaults: List<String>): List<String> {
        val list = mutableListOf<String>()
        if (userMirror != null) list += ensureSlash(userMirror) + file
        list += defaults
        return list
    }

    private suspend fun downloadOne(
        url: String,
        dest: File,
        channel: SendChannel<Progress>,
        name: String,
        mirror: String
    ) = runInterruptible {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        Timber.i("Trying: $url")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val body = r.body ?: throw RuntimeException("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1
            var downloaded = 0L
            var lastReported = 0L
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        // 节流：每 200KB 报一次，避免 UI 刷新过频
                        if (downloaded - lastReported >= 200 * 1024) {
                            lastReported = downloaded
                            channel.trySend(Progress(name, mirror, downloaded, total, false))
                        }
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw RuntimeException("rename ${tmp.name} → ${dest.name} 失败")
    }

    fun deleteAll() {
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 从用户选的本地文件 Uri 导入模型。按文件名自动识别：
     * - 名字含 `det` + 扩展名 .onnx → 检测模型
     * - 名字含 `rec` + 扩展名 .onnx → 识别模型
     * - 扩展名 .txt → 字典
     *
     * 识别不出来就跳过。返回已成功导入的文件数。
     */
    suspend fun importFromLocal(uris: List<android.net.Uri>): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        var imported = 0
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            val lower = name.lowercase()
            val target = when {
                lower.endsWith(".onnx") && "det" in lower -> FILE_DET
                lower.endsWith(".onnx") && "rec" in lower -> FILE_REC
                lower.endsWith(".txt") -> FILE_KEYS
                else -> null
            } ?: continue
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(File(modelsDir, target)).use { output ->
                        input.copyTo(output)
                    }
                }
                imported++
                Timber.i("Imported $name → $target")
            } catch (t: Throwable) {
                Timber.w(t, "Import failed: $name")
            }
        }
        imported
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    companion object {
        const val FILE_DET = "det.onnx"
        const val FILE_REC = "rec.onnx"
        const val FILE_KEYS = "keys.txt"

        /**
         * 内置 PP-OCRv5 mobile 真实可用的下载源（已 adb 真机 200 OK 验证）：
         * - det: HuggingFace bukuroo/PPOCRv5-ONNX 的 mobile det (4.5MB)
         * - rec: HuggingFace bukuroo/PPOCRv5-ONNX 的 mobile rec (15.7MB)
         * - keys: HuggingFace bukuroo/PPOCRv5-ONNX 的 v5 字典 (90KB，v5 词表跟 v4 不同)
         *
         * v5 mobile 比 v4 mobile 准确率有明显提升，特别是密集排版和复杂背景。
         */
        private val DEFAULT_DET_URLS = listOf(
            "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5-mobile-det.onnx",
            "https://hf-mirror.com/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5-mobile-det.onnx"
        )
        private val DEFAULT_REC_URLS = listOf(
            "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5-mobile-rec.onnx",
            "https://hf-mirror.com/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5-mobile-rec.onnx"
        )
        private val DEFAULT_KEYS_URLS = listOf(
            "https://huggingface.co/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5_dict.txt",
            "https://hf-mirror.com/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5_dict.txt"
        )
    }
}
