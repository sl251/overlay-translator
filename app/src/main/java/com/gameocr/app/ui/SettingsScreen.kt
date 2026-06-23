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
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.runtime.derivedStateOf
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
import com.gameocr.app.data.TranslatorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var sourceLang by remember { mutableStateOf("auto") }
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
    var baiduEndpoint by remember { mutableStateOf(com.gameocr.app.data.BaiduOcrEndpoint.GENERAL) }
    var tencentId by remember { mutableStateOf("") }
    var tencentKey by remember { mutableStateOf("") }
    var tencentEndpoint by remember { mutableStateOf(com.gameocr.app.data.TencentOcrEndpoint.GENERAL_BASIC) }
    var paddleMirror by remember { mutableStateOf("") }
    var paddleStatus by remember { mutableStateOf("") }
    var paddleDownloading by remember { mutableStateOf(false) }
    var preUpscale by remember { mutableStateOf(false) }
    var preInvert by remember { mutableStateOf(false) }
    var preBinarize by remember { mutableStateOf(false) }
    var a11yVolume by remember { mutableStateOf(false) }
    var floatingSize by remember { mutableStateOf(56f) }
    var allowWrap by remember { mutableStateOf(true) }
    var avoidCollision by remember { mutableStateOf(true) }
    var apiTimeoutSec by remember { mutableStateOf(30f) }
    var mergeAdjacent by remember { mutableStateOf(true) }
    // 星标语言：本地镜像。togglePinLanguage 立即落盘，下次 ON_RESUME / load() 拉回最新；
    // 这里也乐观更新一份本地状态，UI 立刻反映。
    var pinnedLanguages by remember { mutableStateOf<List<String>>(emptyList()) }

    // 用于 dirty 检测：加载时的初始快照
    var initialSnapshot by remember { mutableStateOf<List<Any?>?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    // —— 搜索：顶部输入 → 下拉匹配项 → 点击 animateScrollTo 到对应 section 顶部 ——
    val scrollState = rememberScrollState()
    val anchors = remember { mutableStateMapOf<String, Int>() }
    val onAnchor: (String, Int) -> Unit = { key, y -> anchors[key] = y }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    // dirty 检测：包到 derivedStateOf，避免每次 recompose 都重建 30+ 元素 list。
    // Snapshot state 变化时才重算。
    val currentSnapshot: List<Any?> by remember {
        derivedStateOf {
            listOf(
                baseUrl, apiKey, model, prompt, targetLang, sourceLang,
                translatorEngine, deeplKey, deeplPro,
                textSize, alpha, loopInterval, streaming, renderMode, placement, overlayTheme,
                customBg, customFg, customBorder, customBorderW,
                offsetX, offsetY, ocrEngine,
                baiduKey, baiduSecret, baiduEndpoint, tencentId, tencentKey, tencentEndpoint, paddleMirror,
                preUpscale, preInvert, preBinarize, a11yVolume, floatingSize,
                allowWrap, avoidCollision, apiTimeoutSec, mergeAdjacent
            )
        }
    }
    val dirty by remember {
        derivedStateOf { initialSnapshot != null && currentSnapshot != initialSnapshot }
    }

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
            baiduKey = baiduKey, baiduSecret = baiduSecret, baiduEndpoint = baiduEndpoint,
            tencentId = tencentId, tencentKey = tencentKey, tencentEndpoint = tencentEndpoint,
            preprocess = PreprocessOptions(preUpscale, preInvert, preBinarize),
            a11yVolume = a11yVolume,
            floatingButtonSizeDp = floatingSize.toInt(),
            allowWrap = allowWrap,
            avoidCollision = avoidCollision,
            apiTimeoutSeconds = apiTimeoutSec.toInt(),
            mergeAdjacentBlocks = mergeAdjacent,
            translatorEngine = translatorEngine,
            deeplKey = deeplKey,
            deeplPro = deeplPro,
            paddleMirror = paddleMirror
        )
    }

    val tryBack: () -> Unit = {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    BackHandler { tryBack() }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            text = { Text(stringResource(R.string.settings_unsaved_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    scope.launch { doSave(); onBack() }
                }) { Text(stringResource(R.string.settings_unsaved_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onBack()
                    }) { Text(stringResource(R.string.settings_unsaved_discard)) }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.settings_unsaved_keep_editing))
                    }
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
        baiduEndpoint = s.baiduOcrEndpoint
        tencentId = s.tencentSecretId
        tencentKey = s.tencentSecretKey
        tencentEndpoint = s.tencentOcrEndpoint
        paddleMirror = s.paddleModelMirrorUrl
        // 不阻塞主线程：file.exists() + file.length() 走 IO Dispatcher。先给占位
        // 文字，IO 完成后再覆盖；进设置的瞬间不卡顿。
        paddleStatus = context.getString(R.string.settings_paddle_status_checking)
        preUpscale = s.preprocess.upscale2x
        preInvert = s.preprocess.invert
        preBinarize = s.preprocess.binarize
        a11yVolume = s.a11yVolumeTrigger
        floatingSize = s.floatingButtonSizeDp.toFloat()
        pinnedLanguages = s.pinnedLanguages
        allowWrap = s.overlayAllowWrap
        avoidCollision = s.overlayAvoidCollision
        apiTimeoutSec = s.apiTimeoutSeconds.toFloat()
        mergeAdjacent = s.mergeAdjacentBlocks
        // 捕获初始快照，用于 dirty 检测
        initialSnapshot = listOf(
            baseUrl, apiKey, model, prompt, targetLang, sourceLang,
            translatorEngine, deeplKey, deeplPro,
            textSize, alpha, loopInterval, streaming, renderMode, placement, overlayTheme,
            customBg, customFg, customBorder, customBorderW,
            offsetX, offsetY, ocrEngine,
            baiduKey, baiduSecret, baiduEndpoint, tencentId, tencentKey, tencentEndpoint, paddleMirror,
            preUpscale, preInvert, preBinarize, a11yVolume, floatingSize,
            allowWrap, avoidCollision, apiTimeoutSec, mergeAdjacent
        )
    }

    // paddleStatus 独立异步加载：file.exists() / file.length() 走 IO 线程，避免阻塞首帧。
    LaunchedEffect(Unit) {
        val status = withContext(Dispatchers.IO) { viewModel.paddleModelStatus() }
        paddleStatus = status
    }

    val closeSearch: () -> Unit = {
        searchActive = false
        searchQuery = ""
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                        LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
                    } else {
                        Text(stringResource(R.string.settings_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (searchActive) closeSearch else tryBack) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (searchActive) R.string.settings_search_close else R.string.common_back
                            )
                        )
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.settings_search_btn)
                            )
                        }
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
                text = { Text(stringResource(if (dirty) R.string.settings_save_btn else R.string.settings_saved_btn)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            // 首帧只渲染 spinner，等 LaunchedEffect 把所有 state 填充完毕再 inflate Column。
            // 这把 Column 的一次性 inflate 卡顿延后到 spinner 显示期间，用户主观感受顺滑。
            if (initialSnapshot == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Box
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // —— 翻译后端 ——
            SectionCard(title = stringResource(R.string.settings_section_translator), anchorKey = SectionKeys.TRANSLATE, onAnchor = onAnchor) {
                Text(stringResource(R.string.settings_label_translator_engine), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(translatorEngine, TranslatorEngine.OPENAI, stringResource(R.string.settings_engine_openai_llm)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.DEEPL, stringResource(R.string.settings_engine_deepl)) { translatorEngine = it }
                }

                if (translatorEngine == TranslatorEngine.OPENAI) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.settings_base_url)) },
                        placeholder = { Text(stringResource(R.string.settings_base_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.settings_api_key)) },
                        placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text(stringResource(R.string.settings_model)) },
                        placeholder = { Text(stringResource(R.string.settings_model_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = deeplKey, onValueChange = { deeplKey = it },
                        label = { Text(stringResource(R.string.settings_deepl_api_key)) },
                        placeholder = { Text(stringResource(R.string.settings_deepl_key_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    SwitchRow(stringResource(R.string.settings_deepl_use_pro), deeplPro) { deeplPro = it }
                    Text(
                        stringResource(R.string.settings_deepl_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val onTogglePin: (String) -> Unit = { code ->
                    // 乐观更新本地 + 异步落盘。togglePinLanguage 内部用 repo.update 是原子的。
                    pinnedLanguages = if (pinnedLanguages.contains(code))
                        pinnedLanguages - code else pinnedLanguages + code
                    scope.launch { viewModel.togglePinLanguage(code) }
                }
                LanguagePicker(
                    label = stringResource(R.string.settings_source_lang),
                    currentCode = sourceLang,
                    onSelect = { sourceLang = it },
                    pinned = pinnedLanguages,
                    onTogglePin = onTogglePin
                )
                LanguagePicker(
                    label = stringResource(R.string.settings_target_lang),
                    currentCode = targetLang,
                    onSelect = { targetLang = it },
                    pinned = pinnedLanguages,
                    onTogglePin = onTogglePin
                )
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.settings_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 6
                )

                // Prompt 健康检查：缺占位符时显眼提示，可能时给一键修复按钮
                // （把已硬编码的目标/源语言字样替换为 {target}/{source} 占位符）
                val hasTargetPlaceholder = prompt.contains("{target}") || prompt.contains("{target_lang}")
                val hasSourcePlaceholder = prompt.contains("{source}") || prompt.contains("{source_lang}")
                val targetName = com.gameocr.app.data.Languages.nameOf(context, targetLang)
                val sourceName = com.gameocr.app.data.Languages.nameOf(context, sourceLang)
                val autoName = com.gameocr.app.data.Languages.nameOf(context, com.gameocr.app.data.Languages.AUTO.code)
                val canFixTarget = !hasTargetPlaceholder && targetName.isNotBlank() &&
                    prompt.contains(targetName)
                val canFixSource = !hasSourcePlaceholder && sourceName.isNotBlank() &&
                    sourceName != autoName && prompt.contains(sourceName)
                if (!hasTargetPlaceholder || !hasSourcePlaceholder) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val missingPart = buildString {
                                if (!hasTargetPlaceholder) append("{target}")
                                if (!hasTargetPlaceholder && !hasSourcePlaceholder) append(" / ")
                                if (!hasSourcePlaceholder) append("{source}")
                            }
                            Text(
                                stringResource(R.string.settings_prompt_warn_missing_format, missingPart),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                stringResource(R.string.settings_prompt_warn_hint_format, targetName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (canFixTarget) {
                                TextButton(onClick = {
                                    prompt = prompt.replace(targetName, "{target}")
                                }) { Text(stringResource(R.string.settings_prompt_replace_target_format, targetName)) }
                            }
                            if (canFixSource) {
                                TextButton(onClick = {
                                    prompt = prompt.replace(sourceName, "{source}")
                                }) { Text(stringResource(R.string.settings_prompt_replace_source_format, sourceName)) }
                            }
                        }
                    }
                }

                val defaultPrompt = stringResource(R.string.default_prompt)
                TextButton(onClick = { prompt = defaultPrompt }) {
                    Text(stringResource(R.string.settings_prompt_reset))
                }
                SwitchRow(stringResource(R.string.settings_streaming), streaming) { streaming = it }
            }

            // —— OCR 引擎 ——
            SectionCard(title = stringResource(R.string.settings_section_ocr), anchorKey = SectionKeys.OCR, onAnchor = onAnchor) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_AUTO, stringResource(R.string.settings_ocr_chip_auto)) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_JAPANESE, stringResource(R.string.settings_ocr_chip_japanese)) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_CHINESE, stringResource(R.string.settings_ocr_chip_chinese)) { ocrEngine = it }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_LATIN, stringResource(R.string.settings_ocr_chip_latin)) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.BAIDU, stringResource(R.string.settings_ocr_chip_baidu)) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.TENCENT, stringResource(R.string.settings_ocr_chip_tencent)) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.PADDLE_ONNX, stringResource(R.string.settings_ocr_chip_paddle)) { ocrEngine = it }
                }

                // 各引擎用途说明：用户经常问"自动/日文/中文/拉丁"的差别
                Text(
                    stringResource(R.string.settings_ocr_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (ocrEngine == OcrEngineKind.BAIDU) {
                    OutlinedTextField(
                        value = baiduKey, onValueChange = { baiduKey = it },
                        label = { Text(stringResource(R.string.settings_baidu_api_key)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = baiduSecret, onValueChange = { baiduSecret = it },
                        label = { Text(stringResource(R.string.settings_baidu_secret_key)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text(
                        stringResource(R.string.settings_baidu_endpoint_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.GENERAL_BASIC, stringResource(R.string.settings_baidu_endpoint_standard)) { baiduEndpoint = it }
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.GENERAL, stringResource(R.string.settings_baidu_endpoint_standard_loc)) { baiduEndpoint = it }
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.WEBIMAGE, stringResource(R.string.settings_baidu_endpoint_webimage)) { baiduEndpoint = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.ACCURATE_BASIC, stringResource(R.string.settings_baidu_endpoint_accurate)) { baiduEndpoint = it }
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.ACCURATE, stringResource(R.string.settings_baidu_endpoint_accurate_loc)) { baiduEndpoint = it }
                    }
                    Text(
                        stringResource(
                            R.string.settings_baidu_current_format,
                            stringResource(baiduEndpoint.displayNameRes),
                            stringResource(baiduEndpoint.freeQuotaRes),
                            stringResource(if (baiduEndpoint.hasLocation) R.string.settings_baidu_with_loc_hint else R.string.settings_baidu_no_loc_hint)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (ocrEngine == OcrEngineKind.TENCENT) {
                    OutlinedTextField(
                        value = tencentId, onValueChange = { tencentId = it },
                        label = { Text(stringResource(R.string.settings_tencent_id_label)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = tencentKey, onValueChange = { tencentKey = it },
                        label = { Text(stringResource(R.string.settings_tencent_key_label)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text(
                        stringResource(R.string.settings_tencent_endpoint_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineChip(tencentEndpoint, com.gameocr.app.data.TencentOcrEndpoint.GENERAL_BASIC, stringResource(R.string.settings_tencent_endpoint_general_basic)) { tencentEndpoint = it }
                        EngineChip(tencentEndpoint, com.gameocr.app.data.TencentOcrEndpoint.GENERAL_ACCURATE, stringResource(R.string.settings_tencent_endpoint_general_accurate)) { tencentEndpoint = it }
                        EngineChip(tencentEndpoint, com.gameocr.app.data.TencentOcrEndpoint.RECOGNIZE_AGENT, stringResource(R.string.settings_tencent_endpoint_recognize_agent)) { tencentEndpoint = it }
                    }
                    Text(
                        stringResource(
                            R.string.settings_tencent_current_format,
                            stringResource(tencentEndpoint.displayNameRes),
                            stringResource(tencentEndpoint.descRes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    paddleStatus = context.getString(
                                        R.string.settings_paddle_download_failed_format,
                                        t.message ?: ""
                                    )
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
                                    paddleStatus = context.getString(
                                        R.string.settings_paddle_imported_format,
                                        n, viewModel.paddleModelStatus()
                                    )
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
            SectionCard(title = stringResource(R.string.settings_section_preprocess), anchorKey = SectionKeys.PREPROCESS, onAnchor = onAnchor) {
                SwitchRow(stringResource(R.string.settings_preprocess_upscale), preUpscale) { preUpscale = it }
                SwitchRow(stringResource(R.string.settings_preprocess_invert), preInvert) { preInvert = it }
                SwitchRow(stringResource(R.string.settings_preprocess_binarize), preBinarize) { preBinarize = it }
            }

            // —— 显示 ——
            // 排列原则：能在预览里看到效果的样式项（配色 / 字号 / 透明度）放在上面，
            // 紧跟一个译文样式预览；预览下面才是几何项（显示模式 / 位置 / 微调），
            // 因为它们依赖原文 boundingBox，没有 OCR 上下文无法预览，只能在实际触发翻译时看到。
            SectionCard(title = stringResource(R.string.settings_section_overlay), anchorKey = SectionKeys.OVERLAY, onAnchor = onAnchor) {
                // —— 影响预览的样式项 ——
                Text(stringResource(R.string.settings_overlay_theme_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(overlayTheme, OverlayTheme.CLASSIC_DARK, stringResource(R.string.settings_theme_classic_dark)) { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.AMBER_GOLD, stringResource(R.string.settings_theme_amber_gold)) { overlayTheme = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(overlayTheme, OverlayTheme.PAPER_LIGHT, stringResource(R.string.settings_theme_paper_light)) { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.FROST_GLASS, stringResource(R.string.settings_theme_frost_glass)) { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.CUSTOM, stringResource(R.string.settings_theme_custom)) { overlayTheme = it }
                }

                if (overlayTheme == OverlayTheme.CUSTOM) {
                    CustomThemeEditor(
                        bg = customBg, onBgChange = { customBg = it },
                        fg = customFg, onFgChange = { customFg = it },
                        border = customBorder, onBorderChange = { customBorder = it },
                        borderW = customBorderW, onBorderWChange = { customBorderW = it }
                    )
                }

                Text(stringResource(R.string.settings_textsize_label_format, textSize.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 10f..28f, steps = 17)

                Text(stringResource(R.string.settings_alpha_label_format, (alpha * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.3f..1f)

                // —— 译文样式预览 ——
                // 紧跟在上面 3 个可调样式项之后；只反映 theme / 字号 / 透明度 / 自定义色 / 边框。
                // 与 OverlayManager 的颜色映射保持一致；改这里时记得同步 [overlayThemeColors]。
                OverlayPreviewCard(
                    theme = overlayTheme,
                    customBg = customBg,
                    customFg = customFg,
                    customBorder = customBorder,
                    customBorderW = customBorderW,
                    textSize = textSize,
                    alpha = alpha
                )

                // —— 几何项（预览看不到，只能实际触发翻译时看到效果）——
                Text(stringResource(R.string.settings_render_mode_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(renderMode, RenderMode.BLOCKS, stringResource(R.string.settings_render_blocks_chip)) { renderMode = it }
                    EngineChip(renderMode, RenderMode.BANNER, stringResource(R.string.settings_render_banner_chip)) { renderMode = it }
                }

                if (renderMode == RenderMode.BLOCKS) {
                    Text(stringResource(R.string.settings_placement_label), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineChip(placement, OverlayPlacement.BELOW, stringResource(R.string.settings_placement_below_chip)) { placement = it }
                        EngineChip(placement, OverlayPlacement.OVERLAP, stringResource(R.string.settings_placement_overlap_chip)) { placement = it }
                        EngineChip(placement, OverlayPlacement.ABOVE, stringResource(R.string.settings_placement_above_chip)) { placement = it }
                    }

                    Text(stringResource(R.string.settings_offset_x_format, offsetX.toInt()), style = MaterialTheme.typography.labelLarge)
                    Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -200f..200f)

                    Text(stringResource(R.string.settings_offset_y_format, offsetY.toInt()), style = MaterialTheme.typography.labelLarge)
                    Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -100f..100f)

                    SwitchRow(stringResource(R.string.settings_allow_wrap), allowWrap) { allowWrap = it }
                    SwitchRow(stringResource(R.string.settings_avoid_collision), avoidCollision) { avoidCollision = it }
                    Text(
                        stringResource(R.string.settings_avoid_collision_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SwitchRow(stringResource(R.string.settings_merge_adjacent), mergeAdjacent) { mergeAdjacent = it }
                    Text(
                        stringResource(R.string.settings_merge_adjacent_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(stringResource(R.string.settings_floating_size_format, floatingSize.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = floatingSize,
                    onValueChange = { floatingSize = it },
                    valueRange = 32f..96f,
                    steps = (96 - 32) / 4 - 1
                )
            }

            // —— 触发器 ——
            SectionCard(title = stringResource(R.string.settings_section_trigger), anchorKey = SectionKeys.TRIGGER, onAnchor = onAnchor) {
                OutlinedTextField(
                    value = loopInterval,
                    onValueChange = { loopInterval = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_loop_interval_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                SwitchRow(stringResource(R.string.settings_a11y_volume_label), a11yVolume) { a11yVolume = it }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_btn_open_a11y)) }
            }

            // —— 网络（全局，跨 OCR / 翻译）——
            SectionCard(title = stringResource(R.string.settings_section_network), anchorKey = SectionKeys.NETWORK, onAnchor = onAnchor) {
                Text(
                    stringResource(R.string.settings_api_timeout_format, apiTimeoutSec.toInt()),
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = apiTimeoutSec,
                    onValueChange = { apiTimeoutSec = it },
                    valueRange = 5f..120f,
                    steps = 22
                )
                Text(
                    stringResource(R.string.settings_api_timeout_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 给 FAB 留出底部空间，避免最后一项被遮挡
            Box(modifier = Modifier.size(80.dp))
            }

            // 搜索下拉：浮在 Column 之上。匹配项点击后滚到对应 section 顶部并关闭搜索。
            if (searchActive && searchQuery.isNotBlank()) {
                val matches = remember(searchQuery) {
                    SETTING_ITEMS.filter { it.matches(context, searchQuery) }.take(10)
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .heightIn(max = 320.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    if (matches.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_search_no_match),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn {
                            items(matches) { entry ->
                                ListItem(
                                    headlineContent = { Text(stringResource(entry.itemLabelRes)) },
                                    supportingContent = { Text(stringResource(entry.sectionLabelRes)) },
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    modifier = Modifier.clickable {
                                        val y = anchors[entry.sectionKey] ?: 0
                                        scope.launch { scrollState.animateScrollTo(y) }
                                        closeSearch()
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 译文样式实时预览卡。展示一段假的"原文 + 译文"，按当前 theme/字号/透明度/自定义色/边框渲染。
 *
 * 与 [com.gameocr.app.overlay.OverlayManager] 的视觉保持一致：
 * - 主题颜色映射见 [overlayThemeColors]（务必与 OverlayManager 同步）
 * - alpha 整体应用到 box（模拟 view.setAlpha 的效果，叠加自身像素 alpha）
 * - 棋盘格底色用 linear gradient 模拟实际屏幕背景，让透明度变化肉眼可见
 */
@Composable
private fun OverlayPreviewCard(
    theme: OverlayTheme,
    customBg: Int,
    customFg: Int,
    customBorder: Int,
    customBorderW: Float,
    textSize: Float,
    alpha: Float
) {
    val colors = overlayThemeColors(theme, customBg, customFg, customBorder, customBorderW.toInt())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_overlay_preview_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1F2937), Color(0xFF374151), Color(0xFF1F2937))
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(alpha)
                    .background(
                        Color(colors.bg),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .let { m ->
                        if (colors.borderDp > 0) {
                            m.border(
                                colors.borderDp.dp,
                                Color(colors.border),
                                RoundedCornerShape(6.dp)
                            )
                        } else m
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    stringResource(R.string.settings_overlay_preview_sample),
                    color = Color(colors.fg),
                    fontSize = textSize.sp
                )
            }
        }
    }
}

/** 主题 → ARGB 颜色映射。与 [com.gameocr.app.overlay.OverlayManager] 内的硬编码必须保持一致。 */
private data class ThemeColors(val bg: Int, val fg: Int, val border: Int, val borderDp: Int)

private fun overlayThemeColors(
    theme: OverlayTheme,
    customBg: Int,
    customFg: Int,
    customBorder: Int,
    customBorderW: Int
): ThemeColors = when (theme) {
    OverlayTheme.CLASSIC_DARK ->
        ThemeColors(bg = 0xE6000000.toInt(), fg = 0xFFFFFFFF.toInt(), border = 0, borderDp = 0)
    OverlayTheme.AMBER_GOLD ->
        ThemeColors(bg = 0xF0241608.toInt(), fg = 0xFFFFD27F.toInt(), border = 0xFFB8860B.toInt(), borderDp = 2)
    OverlayTheme.PAPER_LIGHT ->
        ThemeColors(bg = 0xF0F5EFE0.toInt(), fg = 0xFF3E2A1F.toInt(), border = 0xFFB68850.toInt(), borderDp = 1)
    OverlayTheme.FROST_GLASS ->
        ThemeColors(bg = 0xCC1E293B.toInt(), fg = 0xFFE0F2FE.toInt(), border = 0xFF60A5FA.toInt(), borderDp = 1)
    OverlayTheme.CUSTOM ->
        ThemeColors(bg = customBg, fg = customFg, border = customBorder, borderDp = customBorderW.coerceAtLeast(0))
}

/** 搜索可用的 section key 常量。和 [SETTING_ITEMS] 的 sectionKey 对齐。 */
private object SectionKeys {
    const val TRANSLATE = "translate"
    const val OCR = "ocr"
    const val PREPROCESS = "preprocess"
    const val OVERLAY = "overlay"
    const val TRIGGER = "trigger"
    const val NETWORK = "network"
}

/**
 * 搜索索引条目。sectionLabel/itemLabel 走 res id 跟随系统语言；keywords 同时塞中英文，
 * 让用户用任何一种语言搜索都能命中（i18n 后用户可能习惯输入哪种都说不定）。
 */
private data class SearchEntry(
    val sectionKey: String,
    @androidx.annotation.StringRes val sectionLabelRes: Int,
    @androidx.annotation.StringRes val itemLabelRes: Int,
    val keywords: List<String> = emptyList()
) {
    fun matches(context: android.content.Context, q: String): Boolean {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return false
        return context.getString(itemLabelRes).lowercase().contains(s) ||
            context.getString(sectionLabelRes).lowercase().contains(s) ||
            keywords.any { it.lowercase().contains(s) }
    }
}

/**
 * 设置项可搜索索引。新增设置项时同步加一行；匹配后跳到所在 section 顶部。
 * keywords 混合中英文：英文系统下用户用英文输入仍能搜到中文 section / 反之亦然。
 */
private val SETTING_ITEMS: List<SearchEntry> = listOf(
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_translator_engine, listOf("OpenAI", "DeepL", "LLM", "翻译引擎")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_base_url, listOf("base url")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_api_key, listOf("api key")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_model_name, listOf("model", "模型名")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_deepl_api_key, listOf("deepl")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_deepl_pro, listOf("deepl pro")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_source_lang, listOf("source", "源语言")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_target_lang, listOf("target", "目标语言")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_prompt, listOf("prompt", "提示词", "system")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_streaming, listOf("streaming", "流式")),

    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_ocr_switch, listOf("ML Kit", "百度", "腾讯", "Paddle", "OCR engine")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_paddle_download, listOf("ONNX", "v5", "镜像", "mirror")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_baidu_api_key, listOf("baidu", "百度")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_tencent_secret, listOf("tencent", "腾讯")),

    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_upscale, listOf("upscale", "放大", "上采样")),
    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_invert, listOf("invert", "反色", "暗底白字")),
    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_binarize, listOf("binarize", "otsu", "二值化")),

    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_render_mode, listOf("紧贴", "横幅", "banner", "render", "display mode")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_placement, listOf("下方", "上方", "覆盖", "below", "above", "overlap", "placement")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_offset, listOf("offset", "微调")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_overlay_theme, listOf("深色", "浅色", "纸张", "霜玻璃", "琥珀", "theme", "dark", "light", "frost", "amber")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_custom_theme, listOf("custom", "border", "自定义", "边框")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_text_size, listOf("font size", "字号", "字体大小")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_alpha, listOf("alpha", "opacity", "透明度")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_floating_size, listOf("floating", "圆球", "悬浮")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_merge_adjacent, listOf("merge", "合并", "重叠", "拆段")),

    SearchEntry(SectionKeys.TRIGGER, R.string.settings_section_trigger, R.string.settings_search_item_loop_interval, listOf("loop", "循环")),
    SearchEntry(SectionKeys.TRIGGER, R.string.settings_section_trigger, R.string.settings_search_item_a11y_volume, listOf("无障碍", "a11y", "accessibility", "volume", "音量")),

    SearchEntry(SectionKeys.NETWORK, R.string.settings_section_network, R.string.settings_search_item_api_timeout, listOf("timeout", "超时", "网络", "network")),
)

@Composable
private fun SectionCard(
    title: String,
    anchorKey: String? = null,
    onAnchor: ((String, Int) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val baseModifier = Modifier.fillMaxWidth()
    val cardModifier = if (anchorKey != null && onAnchor != null) {
        baseModifier.onGloballyPositioned { coords ->
            onAnchor(anchorKey, coords.positionInParent().y.toInt())
        }
    } else baseModifier
    Card(
        modifier = cardModifier,
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
        ArgbPicker(stringResource(R.string.settings_custom_color_bg), bg, onBgChange)
        ArgbPicker(stringResource(R.string.settings_custom_color_fg), fg, onFgChange)
        ArgbPicker(stringResource(R.string.settings_custom_color_border), border, onBorderChange)
        Text(stringResource(R.string.settings_custom_color_border_w_format, borderW.toInt()), style = MaterialTheme.typography.labelLarge)
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
        // 当前模型版本占位行：未来支持多版本切换时换成 DropdownMenu，这里先展示当前唯一版本
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.settings_paddle_model_version_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.settings_paddle_model_name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Text(
            stringResource(R.string.settings_paddle_desc),
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
            ) {
                Text(stringResource(
                    if (downloading) R.string.settings_paddle_btn_processing else R.string.settings_paddle_btn_auto_download
                ))
            }
            OutlinedButton(
                enabled = !downloading,
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.settings_paddle_btn_local_import)) }
        }

        OutlinedTextField(
            value = mirror, onValueChange = onMirrorChange,
            label = { Text(stringResource(R.string.settings_paddle_mirror_label)) },
            placeholder = { Text(stringResource(R.string.settings_paddle_mirror_placeholder)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.settings_paddle_btn_delete)) }
    }
}
