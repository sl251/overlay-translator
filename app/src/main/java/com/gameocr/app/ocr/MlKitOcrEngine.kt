package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.data.OcrEngineKind
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit on-device Text Recognition v2。日 / 中 / 拉丁三个识别器懒加载。
 *
 * AUTO 模式：先跑日文识别器，若没拿到含日文假名的字符则回落 latin。
 * 这套简单策略覆盖了 galgame / 漫画 / 英文菜单的主要场景，更精细的语言判定留到 M2。
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japanese: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val input = InputImage.fromBitmap(bitmap, 0)
        return when (kind) {
            OcrEngineKind.ML_KIT_LATIN -> runRecognizer(latin, input, "latin")
            OcrEngineKind.ML_KIT_JAPANESE -> runRecognizer(japanese, input, "ja")
            OcrEngineKind.ML_KIT_CHINESE -> runRecognizer(chinese, input, "zh")
            OcrEngineKind.ML_KIT_AUTO -> autoRecognize(input)
            OcrEngineKind.BAIDU,
            OcrEngineKind.TENCENT,
            OcrEngineKind.NCNN_JAPANESE_VERTICAL,
            OcrEngineKind.PADDLE_ONNX -> autoRecognize(input) // 路由层应当已转走，这里兜底
        }
    }

    private suspend fun autoRecognize(input: InputImage): List<TextBlock> {
        val ja = runRecognizer(japanese, input, "ja")
        if (ja.any { containsKana(it.text) }) return ja
        // 没检出日文假名 → 用 latin（覆盖英 + 数字 + 拉丁系）
        val latinResult = runRecognizer(latin, input, "latin")
        if (latinResult.isNotEmpty()) return latinResult
        // latin 也没出 → 中文兜底
        return runRecognizer(chinese, input, "zh")
    }

    private suspend fun runRecognizer(
        recognizer: TextRecognizer,
        input: InputImage,
        lang: String
    ): List<TextBlock> = suspendCancellableCoroutine { cont ->
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.mapNotNull { block ->
                    val bbox = block.boundingBox ?: return@mapNotNull null
                    TextBlock(
                        text = block.text,
                        boundingBox = bbox,
                        confidence = 1f,
                        recognizedLanguage = block.recognizedLanguage ?: lang
                    )
                }
                cont.resume(blocks)
            }
            .addOnFailureListener { e ->
                Timber.w(e, "ML Kit recognize failed ($lang)")
                cont.resumeWithException(e)
            }
    }

    private fun containsKana(s: String): Boolean = s.any { c ->
        // 平假名 3040-309F；片假名 30A0-30FF；半角片假名 FF65-FF9F
        (c in '぀'..'ゟ') ||
            (c in '゠'..'ヿ') ||
            (c in '･'..'ﾟ')
    }

    override fun close() {
        runCatching { latin.close() }
        runCatching { japanese.close() }
        runCatching { chinese.close() }
    }
}
