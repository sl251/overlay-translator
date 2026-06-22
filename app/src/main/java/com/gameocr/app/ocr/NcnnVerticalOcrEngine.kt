package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import com.gameocr.app.data.OcrEngineKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * ChOcrLite NCNN 竖排日文识别（端侧）。
 *
 * 工程上需要：
 * 1. JNI 层：NCNN 库（libncnn.so + libchocrlite.so，~6MB per ABI）
 * 2. 模型：DBNet 检测 + CRNN 识别 + AngleNet 方向（合计 ~30MB .param / .bin）
 *
 * 体积太大不适合直接打包，本类做"按需下载到 filesDir/models + 校验后 System.loadLibrary"
 * 的雏形：
 * - [ensureReady]：检查 .so + 模型是否就位，否则触发下载
 * - [download]：从用户配置的 [modelMirrorUrl] 拉文件（GitHub release / 自架镜像）
 * - [recognize]：模型未就位时抛 [ModelNotReadyException]；就位后调 native 方法
 *
 * 本期未打包 .so 与 native 实现，[recognize] 会直接抛 NotImplementedError 提示用户。
 * 完整接入请参考 benjaminwan/ChOcrLiteAndroidDBNet 的 JNI 接口。
 */
@Singleton
class NcnnVerticalOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient
) : OcrEngine {

    /** 用户可配置的镜像地址，留作 settings 扩展项；默认指向 ChOcrLite 上游 release。 */
    @Volatile var modelMirrorUrl: String =
        "https://github.com/benjaminwan/ChOcrLiteAndroidDBNet/releases/download/1.0/models.zip"

    private val modelsDir: File by lazy { File(context.filesDir, "models/ncnn").apply { mkdirs() } }
    @Volatile private var nativeLoaded = false

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        ensureReady()
        // TODO 接入 native：System.loadLibrary("chocrlite") + nativeDetect + nativeRecognize
        throw NotImplementedError(
            "NCNN 竖排日文引擎已就位但 native 层未在本期实现。" +
                "接入步骤见 README『NCNN 路径』章节。"
        )
    }

    suspend fun ensureReady() {
        val required = listOf("dbnet.param", "dbnet.bin", "crnn_lite_lstm.param", "crnn_lite_lstm.bin", "keys.txt")
        val missing = required.filterNot { File(modelsDir, it).exists() }
        if (missing.isEmpty() && nativeLoaded) return
        if (missing.isNotEmpty()) throw ModelNotReadyException("缺少模型文件：$missing；请在设置里点击『下载模型』")
        if (!nativeLoaded) {
            runCatching { System.loadLibrary("chocrlite") }
                .onSuccess { nativeLoaded = true }
                .onFailure { throw ModelNotReadyException("native 库 libchocrlite.so 未打包 (本期未实施)") }
        }
    }

    /** 触发模型下载到 [modelsDir]。返回成功下载到本地的文件数。 */
    suspend fun download(): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(modelMirrorUrl).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val tmpZip = File(modelsDir, "_models.zip")
            FileOutputStream(tmpZip).use { out -> r.body?.byteStream()?.copyTo(out) }
            // 简化：只下载，解压逻辑（zip → param/bin）在真正接入时补
            Timber.i("Downloaded NCNN models bundle: ${tmpZip.length() / 1024} KB")
            1
        }
    }

    override fun close() { /* native 句柄释放留待实现 */ }
}

class ModelNotReadyException(message: String) : RuntimeException(message)
