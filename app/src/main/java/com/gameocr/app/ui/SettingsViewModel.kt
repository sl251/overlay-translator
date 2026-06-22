package com.gameocr.app.ui

import androidx.lifecycle.ViewModel
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.PreprocessOptions
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.SourceLang
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.ocr.PaddleModelInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.collect

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val paddleInstaller: PaddleModelInstaller
) : ViewModel() {

    suspend fun load(): Settings = repo.get()

    @Suppress("LongParameterList")
    suspend fun save(
        baseUrl: String,
        apiKey: String,
        model: String,
        targetLang: String,
        sourceLang: SourceLang,
        prompt: String,
        textSize: Int,
        alpha: Float,
        loopMs: Long,
        streaming: Boolean,
        renderMode: RenderMode,
        placement: OverlayPlacement,
        overlayTheme: OverlayTheme,
        customBg: Int,
        customFg: Int,
        customBorder: Int,
        customBorderW: Int,
        offsetX: Int,
        offsetY: Int,
        ocrEngine: OcrEngineKind,
        baiduKey: String,
        baiduSecret: String,
        tencentId: String,
        tencentKey: String,
        preprocess: PreprocessOptions,
        a11yVolume: Boolean,
        floatingButtonSizeDp: Int
    ) {
        repo.update {
            it.copy(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim(),
                targetLang = targetLang.trim(),
                sourceLang = sourceLang,
                promptTemplate = prompt,
                overlayTextSizeSp = textSize.coerceIn(10, 28),
                overlayAlpha = alpha.coerceIn(0.3f, 1f),
                captureLoopIntervalMs = loopMs.coerceAtLeast(200),
                streamingTranslate = streaming,
                renderMode = renderMode,
                overlayPlacement = placement,
                overlayTheme = overlayTheme,
                customBgColor = customBg,
                customFgColor = customFg,
                customBorderColor = customBorder,
                customBorderWidth = customBorderW,
                overlayOffsetX = offsetX,
                overlayOffsetY = offsetY,
                ocrEngine = ocrEngine,
                baiduOcrApiKey = baiduKey.trim(),
                baiduOcrSecretKey = baiduSecret.trim(),
                tencentSecretId = tencentId.trim(),
                tencentSecretKey = tencentKey.trim(),
                preprocess = preprocess,
                a11yVolumeTrigger = a11yVolume,
                floatingButtonSizeDp = floatingButtonSizeDp.coerceIn(32, 96)
            )
        }
    }

    suspend fun savePaddleMirror(url: String) {
        repo.update { it.copy(paddleModelMirrorUrl = url.trim()) }
    }

    fun paddleModelStatus(): String {
        val files = paddleInstaller.checkInstalled()
        return if (files != null) {
            val total = (files.det.length() + files.rec.length() + files.keys.length()) / 1024
            "PaddleOCR 模型已就绪 ✓ (${total} KB)"
        } else {
            "PaddleOCR 模型未下载（填镜像 URL 后点下载）"
        }
    }

    suspend fun downloadPaddleModels(onProgress: (String) -> Unit) {
        paddleInstaller.downloadAll().collect { p ->
            val mirrorTag = p.mirror.substringAfter("//").substringBefore("/").take(24)
            onProgress(
                when {
                    p.error != null -> "[$mirrorTag] ${p.file} 失败: ${p.error}"
                    p.done -> "✓ ${p.file} 完成 (${p.downloaded / 1024} KB) @ $mirrorTag"
                    p.total > 0 -> {
                        val pct = (p.downloaded * 100 / p.total).toInt()
                        "[$mirrorTag] ${p.file} $pct% (${p.downloaded / 1024}/${p.total / 1024} KB)"
                    }
                    else -> "[$mirrorTag] ${p.file} ${p.downloaded / 1024} KB"
                }
            )
        }
    }

    fun deletePaddleModels() {
        paddleInstaller.deleteAll()
    }

    suspend fun importPaddleFromLocal(uris: List<android.net.Uri>): Int =
        paddleInstaller.importFromLocal(uris)
}
