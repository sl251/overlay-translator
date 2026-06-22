package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TargetLangPresets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * OpenAI 兼容 chat completions（支持 stream / non-stream）。
 *
 * 适配：OpenAI、DeepSeek、Kimi、SiliconFlow、Ollama OpenAI 兼容端点。
 * Base URL 写到 `/v1/`（带斜杠），最终请求 `${baseUrl}chat/completions`。
 */
@Singleton
class OpenAiTranslator @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        validate(settings)

        val cacheKey = cache.key(trimmed, settings.model, settings.targetLang, settings.promptTemplate)
        cache.get(cacheKey)?.let { return it }

        val request = buildRequest(trimmed, settings, stream = false)
        val translated = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw TranslationException("HTTP ${resp.code}: ${raw.take(200)}")
                val parsed = runCatching { json.decodeFromString<ChatResponse>(raw) }
                    .getOrElse { throw TranslationException("解析响应失败: ${raw.take(200)}", it) }
                parsed.choices.firstOrNull()?.message?.content?.trim()
                    ?: throw TranslationException("响应里没有 choices.message.content")
            }
        }
        cache.put(cacheKey, translated)
        return translated
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return@flow
        validate(settings)

        val cacheKey = cache.key(trimmed, settings.model, settings.targetLang, settings.promptTemplate)
        cache.get(cacheKey)?.let {
            emit(it)
            return@flow
        }

        val request = buildRequest(trimmed, settings, stream = true)
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val raw = response.body?.string().orEmpty()
            response.close()
            throw TranslationException("HTTP ${response.code}: ${raw.take(200)}")
        }
        val body = response.body ?: run {
            response.close()
            throw TranslationException("empty response body")
        }

        val acc = StringBuilder()
        try {
            body.source().use { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        json.decodeFromString<ChatStreamChunk>(payload)
                    }.getOrNull()?.choices?.firstOrNull()?.delta?.content ?: continue
                    acc.append(delta)
                    emit(acc.toString())
                }
            }
        } finally {
            response.close()
        }
        if (acc.isNotEmpty()) cache.put(cacheKey, acc.toString())
    }.flowOn(Dispatchers.IO)

    private fun validate(settings: Settings) {
        if (settings.apiKey.isBlank()) throw TranslationException("API Key 未配置")
    }

    private fun buildRequest(text: String, settings: Settings, stream: Boolean): Request {
        // 优先用 chip 预设的人类可读名（"中文（简体）"），fallback 到 code 字符串
        val targetDisplay = TargetLangPresets.ALL
            .firstOrNull { it.second.equals(settings.targetLang, ignoreCase = true) }
            ?.first
            ?: targetCodeToName(settings.targetLang)
            ?: settings.targetLang
        val promptResolved = settings.promptTemplate
            .replace("{target}", targetDisplay)
            .replace("{target_lang}", targetDisplay)
            .replace("{source}", settings.sourceLang.displayName)
            .replace("{source_lang}", settings.sourceLang.displayName)
        val body = ChatRequest(
            model = settings.model,
            messages = listOf(
                ChatMessage(role = "system", content = promptResolved),
                ChatMessage(role = "user", content = text)
            ),
            temperature = 0.3,
            stream = stream
        )
        val payload = json.encodeToString(body)
        val url = ensureSlash(settings.baseUrl) + "chat/completions"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    /** 把 BCP-47 风格的 code（zh-cn / en-us / ja-jp 等）映射成中文展示名。 */
    private fun targetCodeToName(code: String): String? = when (code.trim().lowercase()) {
        "zh", "zh-cn", "zh-hans", "chinese" -> "中文（简体）"
        "zh-tw", "zh-hk", "zh-hant" -> "中文（繁体）"
        "en", "en-us", "en-gb", "english" -> "English"
        "ja", "ja-jp", "japanese" -> "日本語"
        "ko", "ko-kr", "korean" -> "한국어"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "es" -> "Español"
        "ru" -> "Русский"
        else -> null
    }
}
