package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.SourceLang
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
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        if (settings.deeplApiKey.isBlank()) throw TranslationException("DeepL API Key 未配置")

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

        val translated = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val message = parseError(raw) ?: raw.take(200)
                    throw TranslationException("DeepL HTTP ${resp.code}: $message")
                }
                val parsed = runCatching { json.decodeFromString<DeepLResponse>(raw) }
                    .getOrElse { throw TranslationException("DeepL 解析失败: ${raw.take(200)}", it) }
                parsed.translations.firstOrNull()?.text?.trim()
                    ?: throw TranslationException("DeepL 响应无 translations.text")
            }
        }
        cache.put(cacheKey, translated)
        return translated
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val full = translate(source, settings) ?: return@flow
        emit(full)
    }.flowOn(Dispatchers.IO)

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

    private fun mapSourceLang(s: SourceLang): String? = when (s) {
        SourceLang.AUTO -> null
        SourceLang.JA -> "JA"
        SourceLang.ZH -> "ZH"
        SourceLang.EN -> "EN"
        SourceLang.KO -> "KO"
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
}
