package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** 按 [Settings.translatorEngine] 路由到 OpenAI 兼容 或 DeepL。 */
@Singleton
class RoutingTranslator @Inject constructor(
    private val openAi: OpenAiTranslator,
    private val deepl: DeepLTranslator
) : Translator {
    override suspend fun translate(source: String, settings: Settings): String? =
        engineFor(settings).translate(source, settings)

    override fun translateStream(source: String, settings: Settings): Flow<String> =
        engineFor(settings).translateStream(source, settings)

    /** RoutingTranslator 把 prefersBatch 直接转发到当前 settings 选中的引擎。 */
    override val prefersBatch: Boolean
        get() = false // 不能静态判断；调用方应该用 [prefersBatchFor]

    fun prefersBatchFor(settings: Settings): Boolean = engineFor(settings).prefersBatch

    override suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> =
        engineFor(settings).translateBatch(sources, settings)

    private fun engineFor(settings: Settings): Translator = when (settings.translatorEngine) {
        TranslatorEngine.OPENAI -> openAi
        TranslatorEngine.DEEPL -> deepl
    }
}
