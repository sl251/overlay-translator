package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DeepL 翻译 API。
 *
 * 端点：
 * - Free: https://api-free.deepl.com/v2/translate
 * - Pro : https://api.deepl.com/v2/translate
 *
 * 鉴权：Header `Authorization: DeepL-Auth-Key <key>`
 * Body 用 x-www-form-urlencoded：`text=...&source_lang=JA&target_lang=ZH`
 *
 * DeepL 不支持流式（v2 没有 SSE），[translateStream] 退化到非流式一次性 emit。
 */
@Singleton
class DeepLTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        if (settings.deeplApiKey.isBlank()) {
            throw TranslationException(appContext.getString(R.string.err_deepl_no_api_key))
        }

        val targetCode = mapTargetLang(settings.targetLang)
        val cacheKey = cache.key(trimmed, "deepl-$targetCode", targetCode, "")
        cache.get(cacheKey)?.let { return it }

        val endpoint = if (settings.deeplPro) "https://api.deepl.com/v2/translate"
        else "https://api-free.deepl.com/v2/translate"

        val formBuilder = FormBody.Builder()
            .add("text", trimmed)
            .add("target_lang", targetCode)
        val sourceCode = mapSourceLang(settings.sourceLang)
        if (sourceCode != null) formBuilder.add("source_lang", sourceCode)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "DeepL-Auth-Key ${settings.deeplApiKey}")
            .header("Accept", "application/json")
            .post(formBuilder.build())
            .build()

        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val translated = withContext(Dispatchers.IO) {
            timedClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val message = parseError(raw) ?: raw.take(200)
                    throw TranslationException("DeepL HTTP ${resp.code}: $message")
                }
                val parsed = runCatching { json.decodeFromString<DeepLResponse>(raw) }
                    .getOrElse {
                        throw TranslationException(
                            appContext.getString(R.string.err_deepl_parse_failed_format, raw.take(200)),
                            it
                        )
                    }
                parsed.translations.firstOrNull()?.text?.trim()
                    ?: throw TranslationException(appContext.getString(R.string.err_deepl_no_translation))
            }
        }
        cache.put(cacheKey, translated)
        return translated
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val full = translate(source, settings) ?: return@flow
        emit(full)
    }.flowOn(Dispatchers.IO)

    /**
     * DeepL 免费档限频严格（429 容易触发），开启批处理可让 CaptureService 把一帧 OCR
     * 出的 N 段合并成 1 次 HTTP 请求，N 段计 1 次配额。
     */
    override val prefersBatch: Boolean get() = true

    /**
     * DeepL v2/translate 单请求支持多个 `text` 参数，返回 translations 数组对应。
     * 缓存：每条 text 单独算 cache key；已命中的不进 HTTP，剩下的拼成单次请求。
     */
    override suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> {
        if (sources.isEmpty()) return emptyList()
        if (settings.deeplApiKey.isBlank()) {
            throw TranslationException(appContext.getString(R.string.err_deepl_no_api_key))
        }

        val targetCode = mapTargetLang(settings.targetLang)
        val sourceCode = mapSourceLang(settings.sourceLang)

        // 先按 cache 命中拆分
        val result = arrayOfNulls<String>(sources.size)
        val pending = mutableListOf<Int>() // 需要发请求的索引
        for ((i, raw) in sources.withIndex()) {
            val t = raw.trim()
            if (t.isEmpty()) {
                result[i] = null
                continue
            }
            val key = cache.key(t, "deepl-$targetCode", targetCode, "")
            val hit = cache.get(key)
            if (hit != null) result[i] = hit
            else pending.add(i)
        }
        if (pending.isEmpty()) return result.toList()

        val endpoint = if (settings.deeplPro) "https://api.deepl.com/v2/translate"
        else "https://api-free.deepl.com/v2/translate"

        val formBuilder = FormBody.Builder()
        pending.forEach { idx -> formBuilder.add("text", sources[idx].trim()) }
        formBuilder.add("target_lang", targetCode)
        if (sourceCode != null) formBuilder.add("source_lang", sourceCode)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "DeepL-Auth-Key ${settings.deeplApiKey}")
            .header("Accept", "application/json")
            .post(formBuilder.build())
            .build()

        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val parsed = withContext(Dispatchers.IO) {
            timedClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val message = parseError(raw) ?: raw.take(200)
                    throw TranslationException("DeepL HTTP ${resp.code}: $message")
                }
                runCatching { json.decodeFromString<DeepLResponse>(raw) }
                    .getOrElse {
                        throw TranslationException(
                            appContext.getString(R.string.err_deepl_parse_failed_format, raw.take(200)),
                            it
                        )
                    }
            }
        }
        // translations 数量应等于 pending 数量；若 DeepL 返回少了用 null 占位
        for ((order, idx) in pending.withIndex()) {
            val translated = parsed.translations.getOrNull(order)?.text?.trim() ?: continue
            result[idx] = translated
            // 回写 cache
            val key = cache.key(sources[idx].trim(), "deepl-$targetCode", targetCode, "")
            cache.put(key, translated)
        }
        return result.toList()
    }

    override suspend fun testConnection(settings: Settings): TestResult {
        if (settings.deeplApiKey.isBlank()) {
            return TestResult(false, appContext.getString(R.string.err_deepl_no_api_key))
        }
        val endpoint = if (settings.deeplPro) "https://api.deepl.com/v2/usage"
        else "https://api-free.deepl.com/v2/usage"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "DeepL-Auth-Key ${settings.deeplApiKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        return runCatching {
            withContext(Dispatchers.IO) {
                timedClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // 403 常见原因：Free key 调了 Pro endpoint，或反之 —— 明确提示用户切开关
                        val hint = if (resp.code == 403) {
                            " (" + appContext.getString(R.string.settings_test_deepl_403_hint) + ")"
                        } else ""
                        val msg = parseError(raw) ?: raw.take(200)
                        return@use TestResult(false, "HTTP ${resp.code}: $msg$hint")
                    }
                    val usage = runCatching { json.decodeFromString<DeepLUsage>(raw) }
                        .getOrElse {
                            return@use TestResult(
                                false,
                                appContext.getString(R.string.err_deepl_parse_failed_format, raw.take(200))
                            )
                        }
                    val used = usage.characterCount
                    val limit = usage.characterLimit
                    val remainPct = if (limit > 0) ((limit - used) * 100 / limit).toInt() else 0
                    TestResult(
                        true,
                        appContext.getString(
                            R.string.settings_test_ok_deepl_format,
                            formatNumber(used),
                            formatNumber(limit),
                            remainPct
                        )
                    )
                }
            }
        }.getOrElse { e ->
            TestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun formatNumber(n: Long): String = "%,d".format(n)

    /** 把内部 targetLang 字符串映射成 DeepL 期望格式（大写、特殊处理 ZH-HANT）。 */
    private fun mapTargetLang(code: String): String = when (code.trim().lowercase()) {
        "zh-cn", "zh", "chinese" -> "ZH"
        "zh-tw", "zh-hant" -> "ZH-HANT" // 仅 Pro 支持
        "en", "en-us" -> "EN-US"
        "en-gb" -> "EN-GB"
        "ja", "japanese" -> "JA"
        "ko", "korean" -> "KO"
        "fr" -> "FR"
        "de" -> "DE"
        "es" -> "ES"
        "ru" -> "RU"
        else -> code.uppercase()
    }

    /**
     * BCP-47 → DeepL source_lang。DeepL 只接受少数大写代码且不带 region；其它语言一律
     * 走 auto-detect（null）让 DeepL 自动识别。
     */
    private fun mapSourceLang(s: String): String? {
        val core = s.substringBefore('-').lowercase()
        return when (core) {
            "auto" -> null
            "ja", "zh", "en", "ko", "de", "fr", "es", "ru", "it", "pt", "pl",
            "nl", "tr", "uk", "id", "bg", "cs", "da", "el", "et", "fi", "hu",
            "lt", "lv", "ro", "sk", "sl", "sv" -> core.uppercase()
            else -> null
        }
    }

    private fun parseError(body: String): String? = runCatching {
        json.decodeFromString<DeepLError>(body).message
    }.getOrNull()

    @Serializable
    private data class DeepLResponse(
        val translations: List<Translation> = emptyList()
    )

    @Serializable
    private data class Translation(
        val text: String? = null,
        @SerialName("detected_source_language") val detectedSourceLanguage: String? = null
    )

    @Serializable
    private data class DeepLError(val message: String? = null)

    /** `GET /v2/usage` 响应 schema：当前周期已用 / 总额度（Free 档默认 500k）。 */
    @Serializable
    private data class DeepLUsage(
        @SerialName("character_count") val characterCount: Long = 0,
        @SerialName("character_limit") val characterLimit: Long = 0
    )
}
