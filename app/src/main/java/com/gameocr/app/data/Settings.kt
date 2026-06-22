package com.gameocr.app.data

import com.gameocr.app.capture.CaptureRegion
import kotlinx.serialization.Serializable

/** 用户配置：OCR / 翻译后端相关。 */
@Serializable
data class Settings(
    val baseUrl: String = "https://api.deepseek.com/v1/",
    val apiKey: String = "",
    val model: String = "deepseek-v4-flash",
    val sourceLang: SourceLang = SourceLang.AUTO,
    val targetLang: String = "zh-CN",
    val promptTemplate: String = DEFAULT_PROMPT,
    val ocrEngine: OcrEngineKind = OcrEngineKind.ML_KIT_AUTO,
    val captureLoopIntervalMs: Long = 1000L,
    val captureRegion: CaptureRegion? = null,
    val overlayTextSizeSp: Int = 14,
    val overlayAlpha: Float = 0.85f,
    val streamingTranslate: Boolean = true,
    val renderMode: RenderMode = RenderMode.BLOCKS,
    val overlayPlacement: OverlayPlacement = OverlayPlacement.BELOW,
    val overlayTheme: OverlayTheme = OverlayTheme.CLASSIC_DARK,
    /** CUSTOM 主题用：ARGB int，比如 0xE6000000.toInt() 半透明黑。 */
    val customBgColor: Int = 0xE6000000.toInt(),
    val customFgColor: Int = 0xFFFFFFFF.toInt(),
    val customBorderColor: Int = 0x00000000,
    /** 边框粗细（dp，0=无边）。 */
    val customBorderWidth: Int = 0,
    /** 译文相对原文 boundingBox 的水平偏移（px，负数=往左，正数=往右）。 */
    val overlayOffsetX: Int = 0,
    /** 译文相对原文 boundingBox 的垂直额外偏移（px，叠加到 placement 计算结果之上）。 */
    val overlayOffsetY: Int = 0,
    val preprocess: PreprocessOptions = PreprocessOptions(),
    val baiduOcrApiKey: String = "",
    val baiduOcrSecretKey: String = "",
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val tencentRegion: String = "ap-guangzhou",
    val ncnnVerticalJapanese: Boolean = false,
    val paddleModelMirrorUrl: String = "",
    val preferShizukuCapture: Boolean = false,
    val a11yVolumeTrigger: Boolean = false,
    val translatorEngine: TranslatorEngine = TranslatorEngine.OPENAI,
    val deeplApiKey: String = "",
    val deeplPro: Boolean = false,
    /** 悬浮按钮直径（dp）。 */
    val floatingButtonSizeDp: Int = 56
) {
    companion object {
        /**
         * 默认 prompt 用占位符 `{source}` / `{target}`，运行时替换为当前 source/target 语言名称。
         * 这样用户在设置里改语言 chip 后无需重写 prompt。
         */
        const val DEFAULT_PROMPT: String = """你是一名专业的译者，把下面的{source}原文翻译成{target}。要求：
1. 保留人名、地名等专有名词；
2. 自然流畅，避免直译腔；
3. 只输出译文，不加解释、不加引号。
原文：
"""
    }
}

@Serializable
enum class SourceLang(val tag: String, val displayName: String) {
    AUTO("auto", "原文"),
    JA("ja", "日文"),
    ZH("zh", "中文"),
    EN("en", "英文"),
    KO("ko", "韩文")
}

@Serializable
enum class TranslatorEngine {
    /** OpenAI 兼容 LLM（DeepSeek / SiliconFlow / GPT / 自架 Ollama 等）。 */
    OPENAI,
    /** DeepL 翻译 API（专业翻译质量，对日/英/中等 30+ 语言对）。 */
    DEEPL
}

/** 常用目标语言预设（也允许 settings.targetLang 自由填）。 */
object TargetLangPresets {
    val ALL: List<Pair<String, String>> = listOf(
        "中文（简体）" to "zh-CN",
        "中文（繁体）" to "zh-TW",
        "English" to "en",
        "日本語" to "ja",
        "한국어" to "ko"
    )
}

@Serializable
enum class OcrEngineKind {
    ML_KIT_AUTO,      // 自动选 latin / 日 / 中（M0 默认）
    ML_KIT_LATIN,
    ML_KIT_JAPANESE,
    ML_KIT_CHINESE,
    BAIDU,            // 百度通用文字识别（云端，需要 API Key + Secret）
    TENCENT,          // 腾讯云 GeneralBasicOCR（云端，需要 SecretId + SecretKey）
    NCNN_JAPANESE_VERTICAL,  // ChOcrLite NCNN 竖排日文（端侧，按需下载模型）
    PADDLE_ONNX       // PaddleOCR PP-OCRv4 (ONNX Runtime 端侧，按需下载模型)
}

@Serializable
data class PreprocessOptions(
    val upscale2x: Boolean = false,
    val invert: Boolean = false,
    val binarize: Boolean = false
) {
    fun anyEnabled(): Boolean = upscale2x || invert || binarize
}

@Serializable
enum class RenderMode {
    /** 译文紧贴每段原文下方（按 OCR boundingBox）。 */
    BLOCKS,
    /** 屏幕底部整条横幅，列出所有原文 → 译文。 */
    BANNER
}

@Serializable
enum class OverlayPlacement {
    /** 紧贴原文下方，不遮挡原文（默认）。 */
    BELOW,
    /** 覆盖在原文上方，彻底替换显示。 */
    OVERLAP,
    /** 紧贴原文上方（适合下方有 UI 元素时）。 */
    ABOVE
}

@Serializable
enum class OverlayTheme {
    /** 经典深色：黑底白字。 */
    CLASSIC_DARK,
    /** 琥珀黑金：深棕底 + 暖金字（galgame 老派对话框感）。 */
    AMBER_GOLD,
    /** 浅色纸张：米色底 + 深褐字（漫画译文风）。 */
    PAPER_LIGHT,
    /** 半透明霜玻璃：蓝灰底 + 浅蓝字。 */
    FROST_GLASS,
    /** 自定义：bg/fg/border/border 粗细全由用户设置。 */
    CUSTOM
}
