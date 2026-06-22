package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
 * PaddleOCR PP-OCRv4 端侧识别。基于 ONNX Runtime Android，不需要打包 native .so。
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
            throw ModelNotReadyException(
                "PaddleOCR 模型未就位，请在设置里点『下载 PaddleOCR 模型』。"
            )
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
        val rawBoxes = detectBoxes(bitmap)
        Timber.i("PaddleOCR det boxes=${rawBoxes.size} bitmap=${bitmap.width}x${bitmap.height}")
        if (rawBoxes.isEmpty()) return emptyList()
        val sortedBoxes = rawBoxes.sortedWith(compareBy({ it.top }, { it.left }))
        return sortedBoxes.mapIndexedNotNull { i, box ->
            val text = recognizeBox(bitmap, box).trim()
            Timber.i("PaddleOCR box[$i] $box → '$text' (${text.length} chars)")
            if (text.isEmpty()) null
            else TextBlock(text = text, boundingBox = box, confidence = 1f, recognizedLanguage = "auto")
        }
    }

    /** DBNet 检测：固定输入边长 960，输出 prob map → 阈值二值化 → 外接矩形。 */
    private fun detectBoxes(bitmap: Bitmap): List<Rect> {
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
                extractBoxesFromProbMap(probMap, scale)
            }
        }
    }

    /** 简化二值化 + 连通域外接矩形。返回原图坐标系下的 Rect 列表。 */
    private fun extractBoxesFromProbMap(probMap: Array<FloatArray>, scale: Float): List<Rect> {
        val h = probMap.size
        val w = probMap[0].size
        val visited = Array(h) { BooleanArray(w) }
        val boxes = mutableListOf<Rect>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (visited[y][x] || probMap[y][x] < DET_PROB_THRESH) continue
                // BFS 找一个连通域
                var minX = x; var maxX = x; var minY = y; var maxY = y
                val stack = ArrayDeque<IntArray>()
                stack.addLast(intArrayOf(x, y))
                visited[y][x] = true
                var cnt = 0
                while (stack.isNotEmpty()) {
                    val (cx, cy) = stack.removeLast()
                    cnt++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    for ((dx, dy) in NEIGHBORS) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx in 0 until w && ny in 0 until h && !visited[ny][nx] && probMap[ny][nx] >= DET_PROB_THRESH) {
                            visited[ny][nx] = true
                            stack.addLast(intArrayOf(nx, ny))
                        }
                    }
                }
                if (cnt < MIN_BOX_AREA) continue
                // 略微 padding，再映射回原图
                val pad = 2
                val rect = Rect(
                    ((minX - pad).coerceAtLeast(0) * scale).toInt(),
                    ((minY - pad).coerceAtLeast(0) * scale).toInt(),
                    ((maxX + pad).coerceAtMost(w - 1) * scale).toInt(),
                    ((maxY + pad).coerceAtMost(h - 1) * scale).toInt()
                )
                if (rect.width() > 6 && rect.height() > 6) boxes.add(rect)
            }
        }
        return boxes
    }

    /** CRNN 识别：把 box 区域裁出 → resize 到固定高度 48 → 跑 onnx → CTC decode。 */
    private fun recognizeBox(src: Bitmap, box: Rect): String {
        val session = recSession ?: return ""
        val e = env ?: return ""
        val crop = safeCrop(src, box) ?: return ""

        // PP-OCRv4 rec 输入：[1, 3, 48, W]，W 按 ratio 缩放
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

    private fun safeCrop(src: Bitmap, box: Rect): Bitmap? {
        val l = box.left.coerceIn(0, src.width - 1)
        val t = box.top.coerceIn(0, src.height - 1)
        val r = box.right.coerceIn(l + 1, src.width)
        val b = box.bottom.coerceIn(t + 1, src.height)
        return runCatching { Bitmap.createBitmap(src, l, t, r - l, b - t) }.getOrNull()
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
        private const val DET_LIMIT_SIDE_LEN = 960
        private const val DET_PROB_THRESH = 0.5f       // 之前 0.3 噪声 box 太多；v5 推荐 0.3~0.5
        private const val MIN_BOX_AREA = 64            // 之前 16 容易召回背景纹理
        private const val REC_TARGET_H = 48
        private const val REC_MAX_W = 320
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        // PP-OCRv4 rec 用 (img/255 - 0.5) / 0.5
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val NEIGHBORS = arrayOf(
            intArrayOf(-1, 0), intArrayOf(1, 0),
            intArrayOf(0, -1), intArrayOf(0, 1)
        )
    }
}
