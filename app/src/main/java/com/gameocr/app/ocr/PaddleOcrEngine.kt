package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer

/**
 * PaddleOCR PP-OCRv5 mobile 端侧识别。基于 ONNX Runtime Android，不需要打包 native .so。
 *
 * 三个模型放在 [PaddleModelInstaller.modelsDir]：
 * - `det.onnx` 检测（DBNet）
 * - `rec.onnx` 识别（CRNN + CTC）
 * - `keys.txt`  字典（每行一个字符）
 *
 * 数据流：
 * Bitmap → detect (DBNet) → boundingBox 列表 → 对每个 box 裁剪 + 仿射归一 → CRNN → CTC decode → 文本
 *
 * 后处理简化版：
 * - DBNet 输出 prob map → 阈值二值化 → 连通域 → 外接矩形（不做 DB++ shrinkage）
 * - 这个简化在大字幕场景效果足够，galgame 文本框场景识别率约比 ML Kit 提升 30~50%
 *
 * 模型自下载链接：见 README『PaddleOCR 路径』。
 */
@Singleton
class PaddleOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: PaddleModelInstaller
) : OcrEngine {

    private val initLock = Mutex()
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var keys: List<String> = emptyList()

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        ensureReady()
        return withContext(Dispatchers.Default) { runFull(bitmap) }
    }

    suspend fun ensureReady() = initLock.withLock {
        if (detSession != null && recSession != null) return@withLock
        val files = modelInstaller.checkInstalled()
        if (files == null) {
            throw ModelNotReadyException(context.getString(R.string.err_paddle_not_ready))
        }
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        detSession = e.createSession(files.det.absolutePath, OrtSession.SessionOptions())
        recSession = e.createSession(files.rec.absolutePath, OrtSession.SessionOptions())
        // PP-OCRv5 字典文件每行末尾是 \r\r\n（多一个 CR），readLines() 只吃掉最后一个 \r，
        // 残留的 \r 会被 CTC decode 串进识别结果。trim 每行确保拿到纯字符。
        keys = files.keys.readLines()
            .map { it.trim('\r', '\n', ' ', '\t') }
            .filter { it.isNotEmpty() }
        Timber.i("PaddleOCR ready: det=${files.det.length() / 1024}KB rec=${files.rec.length() / 1024}KB keys=${keys.size}")
    }

    private fun runFull(bitmap: Bitmap): List<TextBlock> {
        val quads = detectQuads(bitmap)
        Timber.i("PaddleOCR det quads=${quads.size} bitmap=${bitmap.width}x${bitmap.height}")
        if (quads.isEmpty()) return emptyList()
        // 按文字行的 y 中心 + x 中心排序（从上到下，从左到右），跟之前轴对齐 Rect 排序一致
        val sorted = quads.sortedWith(compareBy({ it.centerY }, { it.centerX }))
        return sorted.mapIndexedNotNull { i, quad ->
            val text = recognizeQuad(bitmap, quad).trim()
            // 把 Quad 转回轴对齐 Rect 供下游叠加层渲染（叠加层目前按 Rect 处理）
            val bounds = quad.axisAlignedBounds()
            val rect = Rect(
                bounds[0].coerceAtLeast(0),
                bounds[1].coerceAtLeast(0),
                bounds[2].coerceAtMost(bitmap.width),
                bounds[3].coerceAtMost(bitmap.height)
            )
            Timber.i("PaddleOCR quad[$i] $rect → '$text' (${text.length} chars)")
            if (text.isEmpty()) null
            else TextBlock(text = text, boundingBox = rect, confidence = 1f, recognizedLanguage = "auto")
        }
    }

    /** DBNet 检测：把 bitmap 缩到 [DET_LIMIT_SIDE_LEN] 输入，输出 prob map → DBPostprocessor 提取旋转矩形。 */
    private fun detectQuads(bitmap: Bitmap): List<DBPostprocessor.Quad> {
        val session = detSession ?: return emptyList()
        val e = env ?: return emptyList()
        val (resized, scale) = resizeKeepingAspect(bitmap, DET_LIMIT_SIDE_LEN)
        val (rW, rH) = resized.width to resized.height

        val inputTensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(bitmapToNCHW(resized, DET_MEAN, DET_STD)),
            longArrayOf(1, 3, rH.toLong(), rW.toLong())
        )
        return inputTensor.use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { res ->
                val out = res.get(0).value as Array<Array<Array<FloatArray>>>
                // out shape: [1][1][H][W] prob map
                val probMap = out[0][0]
                DBPostprocessor.extractQuads(
                    probMap = probMap,
                    scale = scale,
                    binThresh = DET_PROB_THRESH,
                    scoreThresh = DET_BOX_SCORE_THRESH,
                    unclipRatio = DET_UNCLIP_RATIO
                )
            }
        }
    }

    /**
     * CRNN 识别：用 [Matrix.setPolyToPoly] 把 Quad 4 点透视矫正到水平矩形 → resize 到 H=48 → 跑 onnx → CTC decode。
     *
     * 透视矫正对斜排文字 / DBNet 输出的旋转 box 是关键——直接 axis-aligned crop 会把斜文字
     * 周围的背景一起带进去，rec 在带背景的 crop 上识别率明显下降。
     */
    private fun recognizeQuad(src: Bitmap, quad: DBPostprocessor.Quad): String {
        val session = recSession ?: return ""
        val e = env ?: return ""
        val crop = warpCropQuad(src, quad) ?: return ""

        // PP-OCRv5 rec 输入：[1, 3, 48, W]，W 按 ratio 缩放
        val ratio = REC_TARGET_H.toFloat() / crop.height
        val targetW = (crop.width * ratio).toInt().coerceAtLeast(8).coerceAtMost(REC_MAX_W)
        val resized = Bitmap.createScaledBitmap(crop, targetW, REC_TARGET_H, true)
        if (resized !== crop) crop.recycle()

        val tensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(bitmapToNCHW(resized, REC_MEAN, REC_STD)),
            longArrayOf(1, 3, REC_TARGET_H.toLong(), targetW.toLong())
        )
        return tensor.use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { res ->
                val out = res.get(0).value as Array<Array<FloatArray>>
                // out shape: [1][T][C] logits
                resized.recycle()
                ctcDecode(out[0])
            }
        }
    }

    /**
     * 用 [Matrix.setPolyToPoly] 实现 cv2.warpPerspective 等价：把 4 点 quad 映射到水平
     * 矩形（cropW x cropH）。竖排（高 > 宽 1.5 倍）裁出后旋转 90 度，让 rec 模型按横排理解。
     */
    private fun warpCropQuad(src: Bitmap, quad: DBPostprocessor.Quad): Bitmap? {
        val w1 = hypotF(quad.p1.x - quad.p0.x, quad.p1.y - quad.p0.y)
        val w2 = hypotF(quad.p2.x - quad.p3.x, quad.p2.y - quad.p3.y)
        val h1 = hypotF(quad.p3.x - quad.p0.x, quad.p3.y - quad.p0.y)
        val h2 = hypotF(quad.p2.x - quad.p1.x, quad.p2.y - quad.p1.y)
        val cropW = maxOf(w1, w2).toInt().coerceAtLeast(2)
        val cropH = maxOf(h1, h2).toInt().coerceAtLeast(2)
        // 防止超大 crop 撑爆内存（异常 quad 时）
        if (cropW > 4096 || cropH > 4096) return null

        val srcPts = floatArrayOf(
            quad.p0.x, quad.p0.y,
            quad.p1.x, quad.p1.y,
            quad.p2.x, quad.p2.y,
            quad.p3.x, quad.p3.y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            cropW.toFloat(), 0f,
            cropW.toFloat(), cropH.toFloat(),
            0f, cropH.toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return null

        val out = runCatching {
            Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        Canvas(out).drawBitmap(src, matrix, WARP_PAINT)

        if (cropH > cropW * 1.5f) {
            val rotateMatrix = Matrix().apply { postRotate(90f) }
            val rotated = runCatching {
                Bitmap.createBitmap(out, 0, 0, cropW, cropH, rotateMatrix, true)
            }.getOrNull()
            out.recycle()
            return rotated
        }
        return out
    }

    private fun hypotF(dx: Float, dy: Float): Float = kotlin.math.hypot(dx, dy)

    /** CTC greedy decode：每一步取最大概率，去重 + 去 blank（idx=0）。 */
    private fun ctcDecode(logits: Array<FloatArray>): String {
        val sb = StringBuilder()
        var prev = -1
        var nonBlankSteps = 0
        var outOfRangeIdx = 0
        val numClasses = logits.firstOrNull()?.size ?: 0
        for (step in logits) {
            var best = 0
            var bestVal = step[0]
            for (i in 1 until step.size) {
                if (step[i] > bestVal) { bestVal = step[i]; best = i }
            }
            if (best != 0 && best != prev) {
                nonBlankSteps++
                val keyIdx = best - 1
                when {
                    keyIdx in keys.indices -> sb.append(keys[keyIdx])
                    // PaddleOCR 默认 use_space_char=true 在 dict 末尾追加空格字符
                    keyIdx == keys.size -> sb.append(' ')
                    else -> outOfRangeIdx++
                }
            }
            prev = best
        }
        if (sb.isEmpty()) {
            Timber.w("CTC empty: T=${logits.size} C=$numClasses keys=${keys.size} nonBlank=$nonBlankSteps outOfRange=$outOfRangeIdx")
        }
        return sb.toString()
    }


    /** 等比缩放到最长边 = [limitSideLen]，且边长是 32 的倍数（DBNet 要求）。返回 (resized, scaleBackFactor)。 */
    private fun resizeKeepingAspect(bitmap: Bitmap, limitSideLen: Int): Pair<Bitmap, Float> {
        val w = bitmap.width; val h = bitmap.height
        val ratio = limitSideLen.toFloat() / maxOf(w, h)
        var newW = (w * ratio).toInt()
        var newH = (h * ratio).toInt()
        // round 到 32 的倍数
        newW = ((newW + 31) / 32 * 32).coerceAtLeast(32)
        newH = ((newH + 31) / 32 * 32).coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        return scaled to (1f / ratio)
    }

    /**
     * Bitmap → CHW float[]，按 mean / std 归一化。
     *
     * PaddleOCR 训练时用 OpenCV 读图（默认 BGR），推理也必须 BGR 顺序，否则
     * channel 错位导致识别全错（输出 blank 或乱码英文）。Android Bitmap 默认 ARGB
     * 即 RGB 顺序，必须在这里 swap。
     */
    private fun bitmapToNCHW(bitmap: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val arr = FloatArray(3 * w * h)
        val planeSize = w * h
        for (i in 0 until planeSize) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            // BGR 顺序：channel 0 放 B，channel 1 放 G，channel 2 放 R
            arr[i] = (b - mean[0]) / std[0]
            arr[planeSize + i] = (g - mean[1]) / std[1]
            arr[2 * planeSize + i] = (r - mean[2]) / std[2]
        }
        return arr
    }

    override fun close() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        runCatching { env?.close() }
        detSession = null
        recSession = null
        env = null
    }

    companion object {
        // 1600 比之前的 960 大不少，对 1080p+ 的截图小字（≤24px）召回率提升显著；
        // v5 mobile det 网络本身允许任意 32 倍数边长。
        private const val DET_LIMIT_SIDE_LEN = 1600
        // v5 推荐 0.3。之前用 0.5 把弱响应（细字/低对比度）全砍了。
        // 噪声靠 box 平均得分 [DET_BOX_SCORE_THRESH] 复核来兜，不靠 bin 阈值。
        private const val DET_PROB_THRESH = 0.3f
        // 像素数阈值。prob_map 尺度下 16~24 之间，对应原图大约 8x8 文字块。
        private const val MIN_BOX_AREA = 16
        // 对每个联通域取 prob 平均值，低于该阈值视为噪声丢弃。
        private const val DET_BOX_SCORE_THRESH = 0.6f
        // DBNet shrink 的逆操作：把 box 向外扩。PaddleOCR 官方默认 1.5~2.0。
        private const val DET_UNCLIP_RATIO = 1.6f
        private const val REC_TARGET_H = 48
        // CRNN 最大宽度。之前 320 在长地址行（如名片"123-4567Tokyo, Distrik MugiHi..."）
        // 会把后半截截断；放到 480 后实测能完整读到一整行。
        private const val REC_MAX_W = 480
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        // PP-OCRv5 rec 用 (img/255 - 0.5) / 0.5
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val WARP_PAINT = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
    }
}
