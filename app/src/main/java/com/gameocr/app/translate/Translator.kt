package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

interface Translator {
    /**
     * 翻译一段文本（非流式）。失败抛异常。返回 null 表示空输入。
     */
    suspend fun translate(source: String, settings: Settings): String?

    /**
     * 流式翻译。每次发射当前已累积的完整译文（便于 UI 一次性 setText 全量更新）。
     * 失败抛异常；空输入返回空 Flow。
     */
    fun translateStream(source: String, settings: Settings): Flow<String>

    /**
     * 该引擎是否倾向批处理：true 表示 CaptureService 应优先用 [translateBatch] 把一帧多段
     * 合并为单次 API 调用（如 DeepL 免费档限频严格，必须批处理）。false 表示该引擎在配额
     * 上不需要批处理，且对流式体验敏感（如 OpenAI 兼容 LLM 用户依赖逐 token 流式）。
     */
    val prefersBatch: Boolean get() = false

    /**
     * 批量翻译。默认实现是并发调单条 [translate]；引擎若支持原生批 API 应 override 用单
     * 次 HTTP 处理多段（DeepL 的 v2/translate 支持 form 里多个 `text` 参数即此目的）。
     *
     * 返回值列表长度与 [sources] 一致，索引一一对应；某条失败或空输入用 null 占位，不影响
     * 其它项。
     */
    suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> {
        if (sources.isEmpty()) return emptyList()
        return coroutineScope {
            sources.map { src ->
                async { runCatching { translate(src, settings) }.getOrNull() }
            }.awaitAll()
        }
    }
}

class TranslationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
