package com.gameocr.app.translate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI 兼容 chat completions 请求 / 响应 DTO（M0 非流式）。 */
@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
internal data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
internal data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList()
)

@Serializable
internal data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

/** SSE 流式片段。OpenAI 兼容 stream=true 时每个 `data: {...}` 行的 schema。 */
@Serializable
internal data class ChatStreamChunk(
    val id: String? = null,
    val choices: List<ChatStreamChoice> = emptyList()
)

@Serializable
internal data class ChatStreamChoice(
    val index: Int = 0,
    val delta: ChatStreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class ChatStreamDelta(
    val role: String? = null,
    val content: String? = null
)
