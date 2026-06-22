package com.gameocr.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.gameocr.app.capture.CaptureRegion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("game_ocr_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val BaseUrl = stringPreferencesKey("base_url")
        val ApiKey = stringPreferencesKey("api_key")
        val Model = stringPreferencesKey("model")
        val SourceLang = stringPreferencesKey("source_lang")
        val TargetLang = stringPreferencesKey("target_lang")
        val Prompt = stringPreferencesKey("prompt")
        val OcrEngine = stringPreferencesKey("ocr_engine")
        val LoopInterval = longPreferencesKey("loop_interval_ms")
        val TextSize = intPreferencesKey("overlay_text_size")
        val Alpha = floatPreferencesKey("overlay_alpha")
        val Region = stringPreferencesKey("capture_region_json")
        val Streaming = booleanPreferencesKey("streaming_translate")
        val RenderModeKey = stringPreferencesKey("render_mode")
        val Upscale = booleanPreferencesKey("pre_upscale")
        val Invert = booleanPreferencesKey("pre_invert")
        val Binarize = booleanPreferencesKey("pre_binarize")
        val BaiduKey = stringPreferencesKey("baidu_api_key")
        val BaiduSecret = stringPreferencesKey("baidu_secret_key")
        val A11yVolume = booleanPreferencesKey("a11y_volume_trigger")
        val TencentId = stringPreferencesKey("tencent_secret_id")
        val TencentKey = stringPreferencesKey("tencent_secret_key")
        val TencentRegion = stringPreferencesKey("tencent_region")
        val NcnnVertical = booleanPreferencesKey("ncnn_vertical_ja")
        val PreferShizuku = booleanPreferencesKey("prefer_shizuku")
        val Placement = stringPreferencesKey("overlay_placement")
        val PaddleMirror = stringPreferencesKey("paddle_mirror_url")
        val OffsetX = intPreferencesKey("overlay_offset_x")
        val OffsetY = intPreferencesKey("overlay_offset_y")
        val ThemeKey = stringPreferencesKey("overlay_theme")
        val CustomBg = intPreferencesKey("overlay_custom_bg")
        val CustomFg = intPreferencesKey("overlay_custom_fg")
        val CustomBorder = intPreferencesKey("overlay_custom_border")
        val CustomBorderW = intPreferencesKey("overlay_custom_border_w")
        val TranslatorEng = stringPreferencesKey("translator_engine")
        val DeeplKey = stringPreferencesKey("deepl_key")
        val DeeplPro = booleanPreferencesKey("deepl_pro")
        val FloatingSize = intPreferencesKey("floating_button_size_dp")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs -> prefs.toSettings() }

    suspend fun get(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val next = transform(current)
            prefs[Keys.BaseUrl] = next.baseUrl
            prefs[Keys.ApiKey] = next.apiKey
            prefs[Keys.Model] = next.model
            prefs[Keys.SourceLang] = next.sourceLang.name
            prefs[Keys.TargetLang] = next.targetLang
            prefs[Keys.Prompt] = next.promptTemplate
            prefs[Keys.OcrEngine] = next.ocrEngine.name
            prefs[Keys.LoopInterval] = next.captureLoopIntervalMs
            prefs[Keys.TextSize] = next.overlayTextSizeSp
            prefs[Keys.Alpha] = next.overlayAlpha
            prefs[Keys.Region] = next.captureRegion?.let { json.encodeToString(it) } ?: ""
            prefs[Keys.Streaming] = next.streamingTranslate
            prefs[Keys.RenderModeKey] = next.renderMode.name
            prefs[Keys.Upscale] = next.preprocess.upscale2x
            prefs[Keys.Invert] = next.preprocess.invert
            prefs[Keys.Binarize] = next.preprocess.binarize
            prefs[Keys.BaiduKey] = next.baiduOcrApiKey
            prefs[Keys.BaiduSecret] = next.baiduOcrSecretKey
            prefs[Keys.A11yVolume] = next.a11yVolumeTrigger
            prefs[Keys.TencentId] = next.tencentSecretId
            prefs[Keys.TencentKey] = next.tencentSecretKey
            prefs[Keys.TencentRegion] = next.tencentRegion
            prefs[Keys.NcnnVertical] = next.ncnnVerticalJapanese
            prefs[Keys.PreferShizuku] = next.preferShizukuCapture
            prefs[Keys.Placement] = next.overlayPlacement.name
            prefs[Keys.PaddleMirror] = next.paddleModelMirrorUrl
            prefs[Keys.OffsetX] = next.overlayOffsetX
            prefs[Keys.OffsetY] = next.overlayOffsetY
            prefs[Keys.ThemeKey] = next.overlayTheme.name
            prefs[Keys.CustomBg] = next.customBgColor
            prefs[Keys.CustomFg] = next.customFgColor
            prefs[Keys.CustomBorder] = next.customBorderColor
            prefs[Keys.CustomBorderW] = next.customBorderWidth
            prefs[Keys.TranslatorEng] = next.translatorEngine.name
            prefs[Keys.DeeplKey] = next.deeplApiKey
            prefs[Keys.DeeplPro] = next.deeplPro
            prefs[Keys.FloatingSize] = next.floatingButtonSizeDp
        }
    }

    private fun Preferences.toSettings(): Settings {
        val default = Settings()
        return Settings(
            baseUrl = this[Keys.BaseUrl] ?: default.baseUrl,
            apiKey = this[Keys.ApiKey] ?: default.apiKey,
            model = this[Keys.Model] ?: default.model,
            sourceLang = runCatching { SourceLang.valueOf(this[Keys.SourceLang] ?: "") }
                .getOrDefault(default.sourceLang),
            targetLang = this[Keys.TargetLang] ?: default.targetLang,
            promptTemplate = this[Keys.Prompt] ?: default.promptTemplate,
            ocrEngine = runCatching { OcrEngineKind.valueOf(this[Keys.OcrEngine] ?: "") }
                .getOrDefault(default.ocrEngine),
            captureLoopIntervalMs = this[Keys.LoopInterval] ?: default.captureLoopIntervalMs,
            overlayTextSizeSp = this[Keys.TextSize] ?: default.overlayTextSizeSp,
            overlayAlpha = this[Keys.Alpha] ?: default.overlayAlpha,
            captureRegion = this[Keys.Region]?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<CaptureRegion>(it) }.getOrNull()
            },
            streamingTranslate = this[Keys.Streaming] ?: default.streamingTranslate,
            renderMode = runCatching { RenderMode.valueOf(this[Keys.RenderModeKey] ?: "") }
                .getOrDefault(default.renderMode),
            preprocess = PreprocessOptions(
                upscale2x = this[Keys.Upscale] ?: default.preprocess.upscale2x,
                invert = this[Keys.Invert] ?: default.preprocess.invert,
                binarize = this[Keys.Binarize] ?: default.preprocess.binarize
            ),
            baiduOcrApiKey = this[Keys.BaiduKey] ?: default.baiduOcrApiKey,
            baiduOcrSecretKey = this[Keys.BaiduSecret] ?: default.baiduOcrSecretKey,
            a11yVolumeTrigger = this[Keys.A11yVolume] ?: default.a11yVolumeTrigger,
            tencentSecretId = this[Keys.TencentId] ?: default.tencentSecretId,
            tencentSecretKey = this[Keys.TencentKey] ?: default.tencentSecretKey,
            tencentRegion = this[Keys.TencentRegion] ?: default.tencentRegion,
            ncnnVerticalJapanese = this[Keys.NcnnVertical] ?: default.ncnnVerticalJapanese,
            preferShizukuCapture = this[Keys.PreferShizuku] ?: default.preferShizukuCapture,
            overlayPlacement = runCatching { OverlayPlacement.valueOf(this[Keys.Placement] ?: "") }
                .getOrDefault(default.overlayPlacement),
            paddleModelMirrorUrl = this[Keys.PaddleMirror] ?: default.paddleModelMirrorUrl,
            overlayOffsetX = this[Keys.OffsetX] ?: default.overlayOffsetX,
            overlayOffsetY = this[Keys.OffsetY] ?: default.overlayOffsetY,
            overlayTheme = runCatching { OverlayTheme.valueOf(this[Keys.ThemeKey] ?: "") }
                .getOrDefault(default.overlayTheme),
            customBgColor = this[Keys.CustomBg] ?: default.customBgColor,
            customFgColor = this[Keys.CustomFg] ?: default.customFgColor,
            customBorderColor = this[Keys.CustomBorder] ?: default.customBorderColor,
            customBorderWidth = this[Keys.CustomBorderW] ?: default.customBorderWidth,
            translatorEngine = runCatching { TranslatorEngine.valueOf(this[Keys.TranslatorEng] ?: "") }
                .getOrDefault(default.translatorEngine),
            deeplApiKey = this[Keys.DeeplKey] ?: default.deeplApiKey,
            deeplPro = this[Keys.DeeplPro] ?: default.deeplPro,
            floatingButtonSizeDp = this[Keys.FloatingSize] ?: default.floatingButtonSizeDp
        )
    }
}
