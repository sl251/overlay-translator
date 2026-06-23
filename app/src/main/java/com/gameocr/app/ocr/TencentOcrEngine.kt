package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 腾讯云 OCR - 通用印刷体识别（GeneralBasicOCR）。
 *
 * 签名走 TC3-HMAC-SHA256 v3：
 *   StringToSign = "TC3-HMAC-SHA256\n${date}\n${credentialScope}\n${hashed(CanonicalRequest)}"
 *   CanonicalRequest = "${method}\n${path}\n${query}\n${headers}\n${signedHeaders}\n${hashed(payload)}"
 *
 * 端点：ocr.tencentcloudapi.com（不分 region，但 Region header 必传，默认 ap-guangzhou）。
 */
@Singleton
class TencentOcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val s = settingsRepository.get()
        if (s.tencentSecretId.isBlank() || s.tencentSecretKey.isBlank()) {
            throw IllegalStateException(appContext.getString(R.string.err_tencent_no_keys))
        }
        val region = s.tencentRegion.ifBlank { "ap-guangzhou" }
        val imgB64 = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
        val payload = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("ImageBase64", imgB64)
        })

        val timedClient = client.withApiTimeout(s.apiTimeoutSeconds)
        val endpoint = s.tencentOcrEndpoint
        val resp = withContext(Dispatchers.IO) {
            doSignedCall(
                httpClient = timedClient,
                secretId = s.tencentSecretId,
                secretKey = s.tencentSecretKey,
                service = SERVICE,
                host = HOST,
                region = region,
                action = endpoint.action,
                version = VERSION,
                payload = payload
            )
        }
        if (resp.response?.error != null) {
            throw RuntimeException("Tencent OCR ${resp.response.error.code} (${endpoint.name}): ${resp.response.error.message}")
        }
        val items = resp.response?.textDetections.orEmpty()
        return items.mapIndexed { i, item ->
            val poly = item.polygon
            val box = if (poly.isNullOrEmpty()) Rect(0, 80 + i * 60, bitmap.width, 80 + (i + 1) * 60)
            else Rect(
                poly.minOf { it.x ?: 0 },
                poly.minOf { it.y ?: 0 },
                poly.maxOf { it.x ?: 0 },
                poly.maxOf { it.y ?: 0 }
            )
            TextBlock(
                text = item.detectedText.orEmpty(),
                boundingBox = box,
                confidence = (item.confidence ?: 100).toFloat() / 100f,
                recognizedLanguage = item.language ?: "auto"
            )
        }
    }

    private fun doSignedCall(
        httpClient: okhttp3.OkHttpClient,
        secretId: String,
        secretKey: String,
        service: String,
        host: String,
        region: String,
        action: String,
        version: String,
        payload: String
    ): TencentOcrResponse {
        val timestamp = System.currentTimeMillis() / 1000L
        val date = utcDate(timestamp)
        val credentialScope = "$date/$service/tc3_request"

        // 1. Canonical Request
        val payloadHash = sha256Hex(payload)
        val canonicalHeaders =
            "content-type:application/json; charset=utf-8\nhost:$host\nx-tc-action:${action.lowercase()}\n"
        val signedHeaders = "content-type;host;x-tc-action"
        val canonicalRequest = listOf(
            "POST",
            "/",
            "",
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        // 2. String to Sign
        val stringToSign = listOf(
            "TC3-HMAC-SHA256",
            timestamp.toString(),
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        // 3. Signature
        val secretDate = hmacSha256(("TC3$secretKey").toByteArray(), date)
        val secretService = hmacSha256(secretDate, service)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = bytesToHex(hmacSha256(secretSigning, stringToSign))

        val authorization =
            "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://$host/")
            .header("Authorization", authorization)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Host", host)
            .header("X-TC-Action", action)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("X-TC-Version", version)
            .header("X-TC-Region", region)
            .post(body)
            .build()

        return httpClient.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("Tencent HTTP ${r.code}: ${raw.take(200)}")
            runCatching { json.decodeFromString<TencentOcrResponse>(raw) }
                .getOrElse {
                    throw RuntimeException(
                        appContext.getString(R.string.err_tencent_parse_failed_format, raw.take(200)),
                        it
                    )
                }
        }
    }

    private fun utcDate(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return sdf.format(Date(epochSeconds * 1000))
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    override fun close() { /* 共享 OkHttp，不释放 */ }

    companion object {
        private const val HOST = "ocr.tencentcloudapi.com"
        private const val SERVICE = "ocr"
        private const val VERSION = "2018-11-19"
    }

    @Serializable
    private data class TencentOcrResponse(@SerialName("Response") val response: ResponseBody? = null)

    @Serializable
    private data class ResponseBody(
        @SerialName("TextDetections") val textDetections: List<TextDetection>? = null,
        @SerialName("Language") val language: String? = null,
        @SerialName("RequestId") val requestId: String? = null,
        @SerialName("Error") val error: ErrorBody? = null
    )

    @Serializable
    private data class ErrorBody(
        @SerialName("Code") val code: String? = null,
        @SerialName("Message") val message: String? = null
    )

    @Serializable
    private data class TextDetection(
        @SerialName("DetectedText") val detectedText: String? = null,
        @SerialName("Confidence") val confidence: Int? = null,
        @SerialName("Polygon") val polygon: List<Coord>? = null,
        @SerialName("AdvancedInfo") val advancedInfo: String? = null,
        @SerialName("ItemPolygon") val itemPolygon: Coord? = null,
        @SerialName("Language") val language: String? = null
    )

    @Serializable
    private data class Coord(@SerialName("X") val x: Int? = null, @SerialName("Y") val y: Int? = null)
}
