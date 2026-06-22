package com.gameocr.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.PreprocessOptions
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.SourceLang
import com.gameocr.app.data.TargetLangPresets
import com.gameocr.app.data.TranslatorEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var targetLang by remember { mutableStateOf("zh-CN") }
    var sourceLang by remember { mutableStateOf(SourceLang.AUTO) }
    var translatorEngine by remember { mutableStateOf(TranslatorEngine.OPENAI) }
    var deeplKey by remember { mutableStateOf("") }
    var deeplPro by remember { mutableStateOf(false) }
    var textSize by remember { mutableStateOf(14f) }
    var alpha by remember { mutableStateOf(0.85f) }
    var loopInterval by remember { mutableStateOf("1000") }
    var streaming by remember { mutableStateOf(true) }
    var renderMode by remember { mutableStateOf(RenderMode.BLOCKS) }
    var placement by remember { mutableStateOf(OverlayPlacement.BELOW) }
    var overlayTheme by remember { mutableStateOf(OverlayTheme.CLASSIC_DARK) }
    var customBg by remember { mutableStateOf(0xE6000000.toInt()) }
    var customFg by remember { mutableStateOf(0xFFFFFFFF.toInt()) }
    var customBorder by remember { mutableStateOf(0) }
    var customBorderW by remember { mutableStateOf(0f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var ocrEngine by remember { mutableStateOf(OcrEngineKind.ML_KIT_AUTO) }
    var baiduKey by remember { mutableStateOf("") }
    var baiduSecret by remember { mutableStateOf("") }
    var tencentId by remember { mutableStateOf("") }
    var tencentKey by remember { mutableStateOf("") }
    var paddleMirror by remember { mutableStateOf("") }
    var paddleStatus by remember { mutableStateOf("") }
    var paddleDownloading by remember { mutableStateOf(false) }
    var preUpscale by remember { mutableStateOf(false) }
    var preInvert by remember { mutableStateOf(false) }
    var preBinarize by remember { mutableStateOf(false) }
    var a11yVolume by remember { mutableStateOf(false) }
    var floatingSize by remember { mutableStateOf(56f) }

    // 用于 dirty 检测：加载时的初始快照
    var initialSnapshot by remember { mutableStateOf<List<Any?>?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val currentSnapshot: List<Any?> = listOf(
        baseUrl, apiKey, model, prompt, targetLang, sourceLang,
        translatorEngine, deeplKey, deeplPro,
        textSize, alpha, loopInterval, streaming, renderMode, placement, overlayTheme,
        customBg, customFg, customBorder, customBorderW,
        offsetX, offsetY, ocrEngine,
        baiduKey, baiduSecret, tencentId, tencentKey, paddleMirror,
        preUpscale, preInvert, preBinarize, a11yVolume, floatingSize
    )
    val dirty = initialSnapshot != null && currentSnapshot != initialSnapshot

    val doSave: suspend () -> Unit = {
        viewModel.save(
            baseUrl = baseUrl, apiKey = apiKey, model = model,
            targetLang = targetLang, sourceLang = sourceLang, prompt = prompt,
            textSize = textSize.toInt(), alpha = alpha,
            loopMs = loopInterval.toLongOrNull() ?: 1000L,
            streaming = streaming, renderMode = renderMode, placement = placement,
            overlayTheme = overlayTheme,
            customBg = customBg, customFg = customFg,
            customBorder = customBorder, customBorderW = customBorderW.toInt(),
            offsetX = offsetX.toInt(), offsetY = offsetY.toInt(),
            ocrEngine = ocrEngine,
            baiduKey = baiduKey, baiduSecret = baiduSecret,
            tencentId = tencentId, tencentKey = tencentKey,
            preprocess = PreprocessOptions(preUpscale, preInvert, preBinarize),
            a11yVolume = a11yVolume,
            floatingButtonSizeDp = floatingSize.toInt()
        )
    }

    val tryBack: () -> Unit = {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    BackHandler { tryBack() }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("有未保存的修改") },
            text = { Text("是否保存当前修改后返回？") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    scope.launch { doSave(); onBack() }
                }) { Text("保存并返回") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onBack()
                    }) { Text("不保存") }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text("继续编辑") }
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        val s = viewModel.load()
        baseUrl = s.baseUrl
        apiKey = s.apiKey
        model = s.model
        prompt = s.promptTemplate
        targetLang = s.targetLang
        sourceLang = s.sourceLang
        translatorEngine = s.translatorEngine
        deeplKey = s.deeplApiKey
        deeplPro = s.deeplPro
        textSize = s.overlayTextSizeSp.toFloat()
        alpha = s.overlayAlpha
        loopInterval = s.captureLoopIntervalMs.toString()
        streaming = s.streamingTranslate
        renderMode = s.renderMode
        placement = s.overlayPlacement
        overlayTheme = s.overlayTheme
        customBg = s.customBgColor
        customFg = s.customFgColor
        customBorder = s.customBorderColor
        customBorderW = s.customBorderWidth.toFloat()
        offsetX = s.overlayOffsetX.toFloat()
        offsetY = s.overlayOffsetY.toFloat()
        ocrEngine = s.ocrEngine
        baiduKey = s.baiduOcrApiKey
        baiduSecret = s.baiduOcrSecretKey
        tencentId = s.tencentSecretId
        tencentKey = s.tencentSecretKey
        paddleMirror = s.paddleModelMirrorUrl
        paddleStatus = viewModel.paddleModelStatus()
        preUpscale = s.preprocess.upscale2x
        preInvert = s.preprocess.invert
        preBinarize = s.preprocess.binarize
        a11yVolume = s.a11yVolumeTrigger
        floatingSize = s.floatingButtonSizeDp.toFloat()
        // 捕获初始快照，用于 dirty 检测
        initialSnapshot = listOf(
            baseUrl, apiKey, model, prompt, targetLang, sourceLang,
            translatorEngine, deeplKey, deeplPro,
            textSize, alpha, loopInterval, streaming, renderMode, placement, overlayTheme,
            customBg, customFg, customBorder, customBorderW,
            offsetX, offsetY, ocrEngine,
            baiduKey, baiduSecret, tencentId, tencentKey, paddleMirror,
            preUpscale, preInvert, preBinarize, a11yVolume, floatingSize
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = tryBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { doSave(); onBack() } },
                icon = { Icon(Icons.Default.Save, contentDescription = null) },
                text = { Text(if (dirty) "保存" else "已保存") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— 翻译后端 ——
            SectionCard(title = "翻译后端") {
                Text("翻译引擎", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(translatorEngine, TranslatorEngine.OPENAI, "OpenAI 兼容 LLM") { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.DEEPL, "DeepL") { translatorEngine = it }
                }

                if (translatorEngine == TranslatorEngine.OPENAI) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.siliconflow.cn/v1/  /  https://api.deepseek.com/v1/") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text("模型名") },
                        placeholder = { Text("deepseek-v4-flash / gpt-4o-mini / glm-4-flash") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = deeplKey, onValueChange = { deeplKey = it },
                        label = { Text("DeepL API Key") },
                        placeholder = { Text("Free 版末尾带 :fx，Pro 版没有") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    SwitchRow("使用 Pro 版端点 (api.deepl.com)", deeplPro) { deeplPro = it }
                    Text(
                        "提示：Free 版每月 50 万字符免费，注册见 https://www.deepl.com/pro-api。" +
                            "DeepL 不支持流式，会一次性输出。中文（繁体）需要 Pro 版。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("源语言", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(sourceLang, SourceLang.AUTO, "自动") { sourceLang = it }
                    EngineChip(sourceLang, SourceLang.JA, "日") { sourceLang = it }
                    EngineChip(sourceLang, SourceLang.EN, "英") { sourceLang = it }
                    EngineChip(sourceLang, SourceLang.ZH, "中") { sourceLang = it }
                    EngineChip(sourceLang, SourceLang.KO, "韩") { sourceLang = it }
                }

                Text("目标语言（点 chip 自动填充，也可手动改）", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetLangPresets.ALL.take(3).forEach { (label, code) ->
                        FilterChip(
                            selected = targetLang == code,
                            onClick = { targetLang = code },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetLangPresets.ALL.drop(3).forEach { (label, code) ->
                        FilterChip(
                            selected = targetLang == code,
                            onClick = { targetLang = code },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = targetLang, onValueChange = { targetLang = it },
                    label = { Text("目标语言代码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text("Prompt 模板（{source} {target} 会被替换）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 6
                )
                TextButton(onClick = { prompt = com.gameocr.app.data.Settings.DEFAULT_PROMPT }) {
                    Text("恢复默认 prompt（含占位符）")
                }
                SwitchRow("流式翻译（边译边显示）", streaming) { streaming = it }
            }

            // —— OCR 引擎 ——
            SectionCard(title = "OCR 引擎") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_AUTO, "自动") { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_JAPANESE, "日文") { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_CHINESE, "中文") { ocrEngine = it }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_LATIN, "拉丁") { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.BAIDU, "百度云") { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.TENCENT, "腾讯云") { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.PADDLE_ONNX, "PaddleOCR") { ocrEngine = it }
                }

                if (ocrEngine == OcrEngineKind.BAIDU) {
                    OutlinedTextField(
                        value = baiduKey, onValueChange = { baiduKey = it },
                        label = { Text("百度 API Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = baiduSecret, onValueChange = { baiduSecret = it },
                        label = { Text("百度 Secret Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }

                if (ocrEngine == OcrEngineKind.TENCENT) {
                    OutlinedTextField(
                        value = tencentId, onValueChange = { tencentId = it },
                        label = { Text("腾讯云 SecretId") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = tencentKey, onValueChange = { tencentKey = it },
                        label = { Text("腾讯云 SecretKey") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }

                if (ocrEngine == OcrEngineKind.PADDLE_ONNX) {
                    PaddleSection(
                        status = paddleStatus,
                        downloading = paddleDownloading,
                        mirror = paddleMirror,
                        onMirrorChange = { paddleMirror = it },
                        onDownload = {
                            scope.launch {
                                paddleDownloading = true
                                try {
                                    viewModel.savePaddleMirror(paddleMirror)
                                    viewModel.downloadPaddleModels { msg -> paddleStatus = msg }
                                    paddleStatus = viewModel.paddleModelStatus()
                                } catch (t: Throwable) {
                                    paddleStatus = "下载失败: ${t.message}"
                                } finally {
                                    paddleDownloading = false
                                }
                            }
                        },
                        onImport = { uris ->
                            scope.launch {
                                paddleDownloading = true
                                try {
                                    val n = viewModel.importPaddleFromLocal(uris)
                                    paddleStatus = "导入 $n 个文件 → ${viewModel.paddleModelStatus()}"
                                } finally {
                                    paddleDownloading = false
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                viewModel.deletePaddleModels()
                                paddleStatus = viewModel.paddleModelStatus()
                            }
                        }
                    )
                }
            }

            // —— 图像预处理 ——
            SectionCard(title = "图像预处理 (OCR 前)") {
                SwitchRow("2× 上采样 (提升小字识别率)", preUpscale) { preUpscale = it }
                SwitchRow("颜色反转 (暗底白字时打开)", preInvert) { preInvert = it }
                SwitchRow("Otsu 二值化 (复杂背景)", preBinarize) { preBinarize = it }
            }

            // —— 显示 ——
            SectionCard(title = "译文显示") {
                Text("显示模式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(renderMode, RenderMode.BLOCKS, "紧贴原文") { renderMode = it }
                    EngineChip(renderMode, RenderMode.BANNER, "底部横幅") { renderMode = it }
                }

                if (renderMode == RenderMode.BLOCKS) {
                    Text("译文位置", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineChip(placement, OverlayPlacement.BELOW, "下方") { placement = it }
                        EngineChip(placement, OverlayPlacement.OVERLAP, "覆盖") { placement = it }
                        EngineChip(placement, OverlayPlacement.ABOVE, "上方") { placement = it }
                    }

                    Text("水平微调 (X): ${offsetX.toInt()} px", style = MaterialTheme.typography.labelLarge)
                    Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -200f..200f)

                    Text("垂直微调 (Y): ${offsetY.toInt()} px", style = MaterialTheme.typography.labelLarge)
                    Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -100f..100f)
                }

                Text("译文配色", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(overlayTheme, OverlayTheme.CLASSIC_DARK, "经典深色") { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.AMBER_GOLD, "琥珀黑金") { overlayTheme = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(overlayTheme, OverlayTheme.PAPER_LIGHT, "浅色纸张") { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.FROST_GLASS, "霜玻璃") { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.CUSTOM, "自定义") { overlayTheme = it }
                }

                if (overlayTheme == OverlayTheme.CUSTOM) {
                    CustomThemeEditor(
                        bg = customBg, onBgChange = { customBg = it },
                        fg = customFg, onFgChange = { customFg = it },
                        border = customBorder, onBorderChange = { customBorder = it },
                        borderW = customBorderW, onBorderWChange = { customBorderW = it }
                    )
                }

                Text("字号: ${textSize.toInt()} sp", style = MaterialTheme.typography.labelLarge)
                Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 10f..28f, steps = 17)

                Text("透明度: ${(alpha * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.3f..1f)
            }

            // —— 触发器 ——
            SectionCard(title = "循环 / 触发器") {
                Text("悬浮按钮大小: ${floatingSize.toInt()} dp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = floatingSize,
                    onValueChange = { floatingSize = it },
                    valueRange = 32f..96f,
                    steps = (96 - 32) / 4 - 1
                )

                OutlinedTextField(
                    value = loopInterval,
                    onValueChange = { loopInterval = it.filter { c -> c.isDigit() } },
                    label = { Text("自动循环间隔 (毫秒)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                SwitchRow("音量键触发（需启用无障碍服务）", a11yVolume) { a11yVolume = it }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开系统无障碍设置") }
            }

            // 给 FAB 留出底部空间，避免最后一项被遮挡
            Box(modifier = Modifier.size(80.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        Text(text = label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun <T> EngineChip(current: T, target: T, label: String, onSelect: (T) -> Unit) {
    FilterChip(
        selected = current == target,
        onClick = { onSelect(target) },
        label = { Text(label) }
    )
}

@Composable
private fun CustomThemeEditor(
    bg: Int, onBgChange: (Int) -> Unit,
    fg: Int, onFgChange: (Int) -> Unit,
    border: Int, onBorderChange: (Int) -> Unit,
    borderW: Float, onBorderWChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArgbPicker("背景色", bg, onBgChange)
        ArgbPicker("文字色", fg, onFgChange)
        ArgbPicker("边框色", border, onBorderChange)
        Text("边框粗细: ${borderW.toInt()} dp", style = MaterialTheme.typography.labelLarge)
        Slider(value = borderW, onValueChange = onBorderWChange, valueRange = 0f..6f, steps = 5)
    }
}

@Composable
private fun ArgbPicker(label: String, argb: Int, onChange: (Int) -> Unit) {
    val a = ((argb ushr 24) and 0xFF)
    val r = ((argb ushr 16) and 0xFF)
    val g = ((argb ushr 8) and 0xFF)
    val b = (argb and 0xFF)
    fun pack(na: Int, nr: Int, ng: Int, nb: Int): Int =
        ((na and 0xFF) shl 24) or ((nr and 0xFF) shl 16) or ((ng and 0xFF) shl 8) or (nb and 0xFF)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(argb),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    )
            )
            Text(
                "$label  #${"%08X".format(argb)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        SmallSlider("A", a) { onChange(pack(it, r, g, b)) }
        SmallSlider("R", r) { onChange(pack(a, it, g, b)) }
        SmallSlider("G", g) { onChange(pack(a, r, it, b)) }
        SmallSlider("B", b) { onChange(pack(a, r, g, it)) }
    }
}

@Composable
private fun SmallSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.size(width = 16.dp, height = 24.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.size(width = 32.dp, height = 24.dp)
        )
    }
}

@Composable
private fun PaddleSection(
    status: String,
    downloading: Boolean,
    mirror: String,
    onMirrorChange: (String) -> Unit,
    onDownload: () -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onDelete: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onImport(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "PP-OCRv4 端侧模型 (~16MB)。首选「自动下载」（已内置 HuggingFace 镜像），" +
                "下不动就「从手机文件导入」（自己下三个文件后选）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 状态行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Box(modifier = Modifier.size(8.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = !downloading,
                onClick = onDownload,
                modifier = Modifier.weight(1f)
            ) { Text(if (downloading) "处理中…" else "自动下载") }
            OutlinedButton(
                enabled = !downloading,
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text("本地导入") }
        }

        OutlinedTextField(
            value = mirror, onValueChange = onMirrorChange,
            label = { Text("自定义镜像 URL (可选)") },
            placeholder = { Text("末尾带 / 的 HTTP 镜像地址") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth()
        ) { Text("删除已下载模型") }
    }
}
