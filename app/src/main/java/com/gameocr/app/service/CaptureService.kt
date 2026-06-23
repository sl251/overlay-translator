package com.gameocr.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.capture.FrameDeduper
import com.gameocr.app.capture.MediaProjectionScreenshotter
import com.gameocr.app.capture.Screenshotter
import com.gameocr.app.capture.ShizukuScreenshotter
import com.gameocr.app.shizuku.ShizukuCapabilities
import com.gameocr.app.data.LogRepository
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.ocr.BitmapPreprocessor
import com.gameocr.app.ocr.OcrEngine
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.overlay.FloatingButtonManager
import com.gameocr.app.overlay.OverlayManager
import com.gameocr.app.translate.TranslationException
import com.gameocr.app.translate.Translator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 截屏前台服务：所有截屏 + OCR + 翻译 + 悬浮窗显示都在这里串。
 *
 * Android 14+ 要求 MediaProjection 必须 (1) 先 startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)，
 * (2) 之后才能拿 MediaProjectionManager.getMediaProjection(token)。本服务严格按这个顺序。
 */
@AndroidEntryPoint
class CaptureService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var translator: Translator
    @Inject lateinit var shizukuCapabilities: ShizukuCapabilities
    @Inject lateinit var logRepository: LogRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureLock = Mutex()

    private var screenshotter: Screenshotter? = null
    private var projection: MediaProjection? = null
    private var floatingButton: FloatingButtonManager? = null
    private var overlay: OverlayManager? = null

    private val deduper = FrameDeduper()
    private var loopJob: Job? = null
    // 订阅 SettingsRepository.settings flow，让设置页保存后所有显示项立即生效
    // （悬浮按钮大小、配色主题、字号、透明度、紧贴文位置 …）。原先只在 captureOnce
    // 时读 settings，导致用户必须停止/重启服务或触发一次截屏才能看到改动。
    private var settingsCollectJob: Job? = null
    @Volatile private var loopMode: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕方向变了把圆球 clamp 到可见区域
        floatingButton?.onConfigurationChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSelf()
            ACTION_TRIGGER_ONCE -> triggerOnce()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // 如果已经启动过，先 cleanup 旧资源避免悬浮窗叠加 / 截屏链路泄漏。
        // 用户重复点"启动"按钮、或者切换 Shizuku ↔ MediaProjection 路径都走这条。
        cleanupCapture()

        // 先判断要走的截屏路径：用户启用 Shizuku 且就绪 → Shizuku；否则 → MediaProjection
        val useShizuku = intent.getBooleanExtra(EXTRA_USE_SHIZUKU, false) &&
            shizukuCapabilities.availability(this) == ShizukuCapabilities.Availability.READY

        // 前台服务：Android 14+ 必须显式传非零 type，否则 InvalidForegroundServiceTypeException。
        // MediaProjection 路径走 MEDIA_PROJECTION；Shizuku 路径走 SPECIAL_USE。
        val fgType = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            useShizuku -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(
            this,
            CaptureNotification.NOTIF_ID,
            CaptureNotification.build(this),
            fgType
        )

        if (useShizuku) {
            screenshotter = ShizukuScreenshotter()
            Timber.i("CaptureService started with Shizuku path")
        } else {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (data == null) {
                Timber.w("MediaProjection result data is null")
                stopSelf()
                return
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            val mp = projection
            if (mp == null) {
                Timber.w("getMediaProjection returned null")
                stopSelf()
                return
            }
            screenshotter = MediaProjectionScreenshotter(this, mp)
            Timber.i("CaptureService started with MediaProjection path")
        }

        overlay = OverlayManager(this)
        floatingButton = FloatingButtonManager(
            this,
            onSingleTap = { triggerOnce() },
            onLongPress = { toggleLoopMode() }
        )
        // 异步读 settings 应用大小后再 show，避免阻塞 startForeground 流程
        scope.launch {
            val s = settingsRepository.get()
            floatingButton?.sizeDp = s.floatingButtonSizeDp
            mainScope.launch { floatingButton?.show() }
        }

        // 订阅 settings 热更新：跳过首次 emit（避免和上面 show() 流程重叠初始化），之后任何
        // 改动都立即应用到 overlay 与悬浮按钮。一次性 collect，cleanupCapture 时取消。
        settingsCollectJob?.cancel()
        settingsCollectJob = scope.launch {
            var first = true
            settingsRepository.settings.collect { s ->
                if (first) { first = false; return@collect }
                applyOverlayConfig(s)
            }
        }

        CaptureServiceState.setRunning(true)
    }

    private fun triggerOnce() {
        // 立刻给视觉反馈，避免几秒空窗
        mainScope.launch { overlay?.showLoadingHint() }
        scope.launch { captureOnce() }
    }

    private fun toggleLoopMode() {
        if (loopMode) {
            loopMode = false
            loopJob?.cancel()
            loopJob = null
            Timber.i("Loop mode OFF")
            logRepository.info(LogRepository.Category.CAPTURE, getString(R.string.log_msg_loop_off))
            toast(getString(R.string.toast_loop_off))
        } else {
            loopMode = true
            loopJob = scope.launch {
                while (isActive && loopMode) {
                    captureOnce()
                    val s = settingsRepository.get()
                    val interval = if (s.captureLoopIntervalMs <= 0) 1000L else s.captureLoopIntervalMs
                    delay(interval)
                }
            }
            Timber.i("Loop mode ON")
            logRepository.info(LogRepository.Category.CAPTURE, getString(R.string.log_msg_loop_on))
            toast(getString(R.string.toast_loop_on))
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        val duration = if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
        mainScope.launch {
            android.widget.Toast.makeText(this@CaptureService, msg, duration).show()
        }
    }

    /** 截断过长的错误信息：toast 显示完整 stack trace 体验差，截到 ~140 字以内可读即可。 */
    private fun shortError(t: Throwable): String {
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return if (raw.length > 140) raw.take(140) + "…" else raw
    }

    private suspend fun captureOnce() {
        if (!captureLock.tryLock()) return
        try {
            val shotter = screenshotter ?: return
            val full = shotter.capture()
            if (full == null) {
                // 截屏链路返回 null（MediaProjection token 失效 / Shizuku 调用失败等），
                // 之前直接 return，用户只看到圈转一下；现在显式提示。
                Timber.w("Screenshot capture returned null")
                logRepository.error(LogRepository.Category.CAPTURE, getString(R.string.log_msg_capture_failed))
                toast(getString(R.string.toast_capture_failed), long = true)
                return
            }
            val settings = settingsRepository.get()
            applyOverlayConfig(settings)

            val region = settings.captureRegion
            val workBitmap = cropIfNeeded(full, region) ?: run {
                full.recycle()
                return
            }
            if (workBitmap !== full) full.recycle()

            if (loopMode && deduper.shouldSkip(workBitmap)) {
                workBitmap.recycle()
                return
            }

            val preprocessed = BitmapPreprocessor.apply(workBitmap, settings.preprocess)
            val rawBlocks = try {
                ocrEngine.recognize(preprocessed, settings.ocrEngine)
            } catch (t: Throwable) {
                Timber.w(t, "OCR failed")
                logRepository.error(
                    LogRepository.Category.OCR,
                    getString(R.string.log_msg_ocr_failed_format, settings.ocrEngine.name),
                    t
                )
                // 提示给用户：不然只看到 loading 圈转一下就消失，必须翻日志才知道是 OCR 失败。
                toast(
                    getString(R.string.toast_ocr_failed_format, settings.ocrEngine.name, shortError(t)),
                    long = true
                )
                if (preprocessed !== workBitmap) preprocessed.recycle()
                workBitmap.recycle()
                return
            }
            if (preprocessed !== workBitmap) preprocessed.recycle()
            workBitmap.recycle()
            // 把所有 box 拼成"#1 原文 / #2 原文 / ..."一条日志，避免一次 OCR 写多条
            if (rawBlocks.isNotEmpty()) {
                val joined = rawBlocks.mapIndexed { i, b -> "#${i + 1} ${b.text}" }.joinToString(" | ")
                logRepository.info(
                    LogRepository.Category.OCR,
                    getString(R.string.log_msg_ocr_results_format, rawBlocks.size, settings.ocrEngine.name, joined)
                )
            } else {
                logRepository.info(
                    LogRepository.Category.OCR,
                    getString(R.string.log_msg_ocr_no_result_format, settings.ocrEngine.name)
                )
            }

            // 预处理 upscale 会让 boundingBox 坐标变成 2 倍，渲染前缩回
            val blocks = if (settings.preprocess.upscale2x) {
                rawBlocks.map { tb ->
                    val r = tb.boundingBox
                    tb.copy(boundingBox = android.graphics.Rect(r.left / 2, r.top / 2, r.right / 2, r.bottom / 2))
                }
            } else rawBlocks
            if (blocks.isEmpty()) return

            when (settings.renderMode) {
                RenderMode.BLOCKS -> renderBlocks(blocks, settings)
                RenderMode.BANNER -> renderBanner(blocks, settings)
            }
        } finally {
            // 兜底：所有提前 return / 异常路径下都要把 loading 圈关掉，避免"一直转圈"。
            // 正常完成时 showBlocks/showFullScreen 内部已经 dismiss 过；幂等调用没害。
            mainScope.launch { overlay?.dismissLoading() }
            captureLock.unlock()
        }
    }

    private fun cropIfNeeded(src: Bitmap, region: CaptureRegion?): Bitmap? {
        if (region == null || !region.isValid()) return src
        val l = region.left.coerceIn(0, src.width)
        val t = region.top.coerceIn(0, src.height)
        val r = region.right.coerceIn(0, src.width)
        val b = region.bottom.coerceIn(0, src.height)
        if (r - l <= 8 || b - t <= 8) return src
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    private suspend fun renderBlocks(blocks: List<TextBlock>, settings: Settings) {
        // 先把所有原文块以占位"…"显示在原文下方
        withContext(Dispatchers.Main) {
            overlay?.showBlocks(blocks.map { it to "…" })
        }
        // 引擎支持批处理（如 DeepL）→ 一次 HTTP 译多段，避免限频。否则保留逐段流式
        // 调用 translateOne（OpenAI 兼容 LLM 用户依赖逐 token 流式更新体验）。
        val routing = translator as? com.gameocr.app.translate.RoutingTranslator
        val useBatch = routing?.prefersBatchFor(settings) ?: translator.prefersBatch
        if (useBatch) {
            scope.launch { batchTranslateBlocks(blocks, settings) }
        } else {
            scope.launch {
                blocks.mapIndexed { idx, block ->
                    async {
                        translateOne(block.text, settings) { partial ->
                            mainScope.launch { overlay?.updateBlockText(idx, partial) }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun batchTranslateBlocks(blocks: List<TextBlock>, settings: Settings) {
        val sources = blocks.map { it.text }
        val translated = try {
            withContext(Dispatchers.IO) { translator.translateBatch(sources, settings) }
        } catch (t: Throwable) {
            Timber.w(t, "Batch translate failed")
            logRepository.error(
                LogRepository.Category.TRANSLATE,
                getString(R.string.log_msg_batch_translate_failed_format, settings.translatorEngine.name),
                t
            )
            // 整批失败：在所有 box 上显示失败标记
            withContext(Dispatchers.Main) {
                blocks.indices.forEach { idx ->
                    overlay?.updateBlockText(idx, "[!] " + (t.message ?: ""))
                }
            }
            return
        }
        translated.forEachIndexed { idx, dst ->
            val src = sources[idx]
            val finalText = dst ?: src // null 走回退（DeepL 没翻出来）
            mainScope.launch { overlay?.updateBlockText(idx, finalText) }
            if (dst != null) {
                logRepository.pair(LogRepository.Category.TRANSLATE, src, finalText)
            }
        }
    }

    private suspend fun renderBanner(blocks: List<TextBlock>, settings: Settings) {
        // 横幅模式：等所有翻译完成后整屏一次性显示。这里也走批处理（如果引擎支持）。
        val routing = translator as? com.gameocr.app.translate.RoutingTranslator
        val useBatch = routing?.prefersBatchFor(settings) ?: translator.prefersBatch
        val pairs = withContext(Dispatchers.IO) {
            if (useBatch) {
                val sources = blocks.map { it.text }
                val translated = runCatching { translator.translateBatch(sources, settings) }
                    .getOrElse { t ->
                        logRepository.error(
                            LogRepository.Category.TRANSLATE,
                            getString(R.string.log_msg_batch_translate_failed_format, settings.translatorEngine.name),
                            t
                        )
                        List(sources.size) { "[!] " + (t.message ?: "") }
                    }
                blocks.mapIndexed { i, b ->
                    val dst = (translated.getOrNull(i) as? String) ?: b.text
                    logRepository.pair(LogRepository.Category.TRANSLATE, b.text, dst)
                    b.text to dst
                }
            } else {
                blocks.map { block ->
                    val dst = runCatching {
                        translator.translate(block.text, settings) ?: block.text
                    }.getOrElse { t ->
                        Timber.w(t, "Translate failed")
                        logRepository.error(
                            LogRepository.Category.TRANSLATE,
                            getString(R.string.log_msg_translate_failed_simple),
                            t
                        )
                        "[!] " + (t.message ?: "")
                    }
                    logRepository.pair(LogRepository.Category.TRANSLATE, block.text, dst)
                    block.text to dst
                }
            }
        }
        withContext(Dispatchers.Main) { overlay?.showFullScreen(pairs) }
    }

    private suspend fun translateOne(
        text: String,
        settings: Settings,
        onPartial: (String) -> Unit
    ) {
        try {
            if (settings.streamingTranslate) {
                // 流式：累计 partial 用于落日志（流末尾的 partial 才是完整译文）
                var lastPartial = ""
                translator.translateStream(text, settings)
                    .catch { e ->
                        onPartial("[!] " + (e.message ?: ""))
                        logRepository.error(
                            LogRepository.Category.TRANSLATE,
                            getString(R.string.log_msg_stream_translate_failed_format, settings.translatorEngine.name),
                            e
                        )
                    }
                    .onEach {
                        lastPartial = it
                        onPartial(it)
                    }
                    .collect()
                if (lastPartial.isNotBlank()) {
                    logRepository.pair(LogRepository.Category.TRANSLATE, text, lastPartial)
                }
            } else {
                val dst = translator.translate(text, settings) ?: text
                onPartial(dst)
                logRepository.pair(LogRepository.Category.TRANSLATE, text, dst)
            }
        } catch (e: TranslationException) {
            onPartial("[!] " + (e.message ?: ""))
            logRepository.error(
                LogRepository.Category.TRANSLATE,
                getString(R.string.log_msg_translate_failed_format, settings.translatorEngine.name),
                e
            )
        } catch (t: Throwable) {
            Timber.w(t, "Translate unexpected error")
            onPartial("[!]")
            logRepository.error(
                LogRepository.Category.TRANSLATE,
                getString(R.string.log_msg_translate_exception_format, settings.translatorEngine.name),
                t
            )
        }
    }

    private fun applyOverlayConfig(settings: Settings) {
        overlay?.apply {
            textSizeSp = settings.overlayTextSizeSp
            alpha = settings.overlayAlpha
            regionOffset = settings.captureRegion?.let { Point(it.left, it.top) } ?: Point(0, 0)
            placement = settings.overlayPlacement
            offsetX = settings.overlayOffsetX
            offsetY = settings.overlayOffsetY
            theme = settings.overlayTheme
            customBg = settings.customBgColor
            customFg = settings.customFgColor
            customBorder = settings.customBorderColor
            customBorderWidthDp = settings.customBorderWidth
            allowWrap = settings.overlayAllowWrap
            avoidCollision = settings.overlayAvoidCollision
        }
        // 圆球大小也同步（已 show 后改 sizeDp 调 applyResize 即时生效）
        floatingButton?.let {
            if (it.sizeDp != settings.floatingButtonSizeDp) {
                it.sizeDp = settings.floatingButtonSizeDp
                mainScope.launch { it.applyResize() }
            }
        }
    }

    /** 释放截屏相关资源（不停 Service），用于 handleStart 重入时去重 + onDestroy 兜底。 */
    private fun cleanupCapture() {
        loopMode = false
        loopJob?.cancel()
        loopJob = null
        settingsCollectJob?.cancel()
        settingsCollectJob = null
        overlay?.clear()
        overlay = null
        floatingButton?.hide()
        floatingButton = null
        screenshotter?.release()
        screenshotter = null
        projection?.stop()
        projection = null
        deduper.reset()
    }

    override fun onDestroy() {
        cleanupCapture()
        scope.cancel()
        mainScope.cancel()
        CaptureServiceState.setRunning(false)
        Timber.i("CaptureService destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gameocr.app.action.START"
        const val ACTION_STOP = "com.gameocr.app.action.STOP"
        const val ACTION_TRIGGER_ONCE = "com.gameocr.app.action.TRIGGER_ONCE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_USE_SHIZUKU = "extra_use_shizuku"

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
    }
}
