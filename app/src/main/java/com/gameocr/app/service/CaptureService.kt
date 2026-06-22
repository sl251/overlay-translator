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
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.capture.FrameDeduper
import com.gameocr.app.capture.MediaProjectionScreenshotter
import com.gameocr.app.capture.Screenshotter
import com.gameocr.app.capture.ShizukuScreenshotter
import com.gameocr.app.shizuku.ShizukuCapabilities
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureLock = Mutex()

    private var screenshotter: Screenshotter? = null
    private var projection: MediaProjection? = null
    private var floatingButton: FloatingButtonManager? = null
    private var overlay: OverlayManager? = null

    private val deduper = FrameDeduper()
    private var loopJob: Job? = null
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
            toast("自动翻译已关闭")
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
            toast("自动翻译已开启（每 1 秒一次，再次长按关闭）")
        }
    }

    private fun toast(msg: String) {
        mainScope.launch {
            android.widget.Toast.makeText(this@CaptureService, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun captureOnce() {
        if (!captureLock.tryLock()) return
        try {
            val shotter = screenshotter ?: return
            val full = shotter.capture() ?: return
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
                if (preprocessed !== workBitmap) preprocessed.recycle()
                workBitmap.recycle()
                return
            }
            if (preprocessed !== workBitmap) preprocessed.recycle()
            workBitmap.recycle()

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
        // 流式或并行非流式翻译
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

    private suspend fun renderBanner(blocks: List<TextBlock>, settings: Settings) {
        // 横幅模式：等所有翻译完成后整屏一次性显示
        val pairs = withContext(Dispatchers.IO) {
            blocks.map { block ->
                val dst = runCatching {
                    translator.translate(block.text, settings) ?: block.text
                }.getOrElse { t ->
                    Timber.w(t, "Translate failed")
                    "[失败] " + (t.message ?: "")
                }
                block.text to dst
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
                translator.translateStream(text, settings)
                    .catch { e -> onPartial("[失败] " + (e.message ?: "")) }
                    .onEach { onPartial(it) }
                    .collect()
            } else {
                val dst = translator.translate(text, settings) ?: text
                onPartial(dst)
            }
        } catch (e: TranslationException) {
            onPartial("[失败] " + (e.message ?: ""))
        } catch (t: Throwable) {
            Timber.w(t, "Translate unexpected error")
            onPartial("[异常]")
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
