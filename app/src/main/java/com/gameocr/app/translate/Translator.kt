package com.gameocr.app.translate

import android.graphics.Bitmap
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.TextBlock
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

    /**
     * 连通性测试。用于设置页"测试连接"按钮：验证 key/baseUrl/model 配置可用，并尽可能
     * 顺带返回有用信息（DeepL：剩余字符额度；OpenAI 兼容：拉到的 model 列表）。
     *
     * 实现约定：不抛异常——任何失败都包装成 [TestResult](success=false, message=...)。
     */
    suspend fun testConnection(settings: Settings): TestResult =
        TestResult(false, "testConnection not implemented")

    /**
     * 端到端引擎能力：true 表示该 Translator 既能 OCR 又能翻译，CaptureService 应跳过
     * [com.gameocr.app.ocr.OcrEngine] 阶段，直接调 [ocrAndTranslate]。
     *
     * 目前只有有道图片翻译（YOUDAO_PICTRANS）走这条路。
     */
    val isEndToEnd: Boolean get() = false

    /**
     * 端到端"OCR + 翻译"。仅 [isEndToEnd] = true 的引擎实现；其它默认抛 [NotImplementedError]
     * 不会被 CaptureService 调用到（流程会先判 isEndToEnd 走原 OCR + translate 路径）。
     *
     * 返回 List<Pair<TextBlock, String>>：每段原文 box + 对应译文，box 坐标系跟现有 OCR
     * 引擎一致（屏幕像素，未做 upscale 还原由调用方处理）。
     */
    suspend fun ocrAndTranslate(bitmap: Bitmap, settings: Settings): List<Pair<TextBlock, String>> =
        throw NotImplementedError("ocrAndTranslate not supported by this translator")
}

/**
 * 翻译引擎连通性测试结果。
 *
 * @property success true=可用
 * @property message 给用户看的简短文案（成功如"OK · 已用 X / Y 字符"；失败如"HTTP 401: ..."）
 * @property models OpenAI 兼容 `GET /v1/models` 拉到的 id 列表，便于 UI 让用户从下拉中选；
 *                  DeepL / 失败 / 模型探活模式都返回空列表
 */
data class TestResult(
    val success: Boolean,
    val message: String,
    val models: List<String> = emptyList()
)

class TranslationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
