package com.gameocr.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * 百度通用文字识别（云端兜底）。
 *
 * 端点：
 * - OAuth：POST https://aip.baidubce.com/oauth/2.0/token  (client_credentials)
 * - 通用识别：POST https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic?access_token=xxx
 *   body x-www-form-urlencoded: image=<base64 jpg>
 *
 * 复杂背景下识别率比 ML Kit on-device 稳，限频 2 QPS（免费档），按需切换。
 */
@Singleton
class BaiduOcrEngine @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) : OcrEngine {

    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val settings = settingsRepository.get()
        if (settings.baiduOcrApiKey.isBlank() || settings.baiduOcrSecretKey.isBlank()) {
            throw IllegalStateException("百度 OCR 未配置 API Key / Secret Key")
        }
        val token = obtainToken(settings.baiduOcrApiKey, settings.baiduOcrSecretKey)
        val imageBase64 = withContext(Dispatchers.Default) { encodeJpeg(bitmap) }

        val url = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic".toHttpUrl()
            .newBuilder().addQueryParameter("access_token", token).build()
        val form = FormBody.Builder().add("image", imageBase64).build()
        val req = Request.Builder().url(url).post(form).build()

        val resp = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw RuntimeException("Baidu OCR HTTP ${r.code}: ${raw.take(200)}")
                json.decodeFromString<BaiduOcrResponse>(raw)
            }
        }
        if (resp.errorCode != null && resp.errorCode != 0) {
            throw RuntimeException("Baidu OCR err ${resp.errorCode}: ${resp.errorMsg.orEmpty()}")
        }
        // 百度通用识别不返回 boundingBox（除非用 general 高级版），整段当一个 block
        return resp.wordsResult.orEmpty().mapIndexed { i, w ->
            TextBlock(
                text = w.words.orEmpty(),
                boundingBox = Rect(0, 80 + i * 60, bitmap.width, 80 + (i + 1) * 60),
                confidence = 1f,
                recognizedLanguage = "auto"
            )
        }
    }

    private suspend fun obtainToken(apiKey: String, secret: String): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiresAt - 60_000 }?.let { return@withLock it }

        val url = "https://aip.baidubce.com/oauth/2.0/token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "client_credentials")
            .addQueryParameter("client_id", apiKey)
            .addQueryParameter("client_secret", secret)
            .build()
        val req = Request.Builder().url(url).post(FormBody.Builder().build()).build()
        val token = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw RuntimeException("Baidu token HTTP ${r.code}: ${raw.take(200)}")
                val parsed = json.decodeFromString<BaiduTokenResponse>(raw)
                cachedToken = parsed.accessToken
                tokenExpiresAt = now + parsed.expiresIn * 1000L
                parsed.accessToken
            }
        }
        Timber.i("Baidu token refreshed, ttl=${(tokenExpiresAt - now) / 1000}s")
        token
    }

    private fun encodeJpeg(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        // 百度文档单图 < 4MB，压到 85 质量即可
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun close() { /* OkHttp 共享，不在这里关 */ }

    @Serializable
    private data class BaiduTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long
    )

    @Serializable
    private data class BaiduOcrResponse(
        @SerialName("words_result") val wordsResult: List<BaiduWords>? = null,
        @SerialName("words_result_num") val wordsResultNum: Int? = null,
        @SerialName("log_id") val logId: Long? = null,
        @SerialName("error_code") val errorCode: Int? = null,
        @SerialName("error_msg") val errorMsg: String? = null
    )

    @Serializable
    private data class BaiduWords(val words: String? = null)
}
