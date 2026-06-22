package com.gameocr.app.translate

import com.gameocr.app.data.Settings
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
}

class TranslationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
