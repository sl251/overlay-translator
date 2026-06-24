package com.gameocr.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * OCR 路由：按 [OcrEngineKind] 选 ML Kit / 百度 / 腾讯云 / PaddleOCR PP-OCRv5 mobile。
 *
 * 在底层 engine 之上还做 [mergeAdjacentBlocks]：把同一行内左右邻接的小 box 合并成一个，
 * 避免漫画 / 字幕场景一句话被拆成多段后译文层互相重叠。
 */
@Singleton
class RoutingOcrEngine @Inject constructor(
    private val mlKit: MlKitOcrEngine,
    private val baidu: BaiduOcrEngine,
    private val tencent: TencentOcrEngine,
    private val paddle: PaddleOcrEngine,
    private val settingsRepository: SettingsRepository
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val raw = when (kind) {
            OcrEngineKind.BAIDU -> baidu.recognize(bitmap, kind)
            OcrEngineKind.TENCENT -> tencent.recognize(bitmap, kind)
            OcrEngineKind.PADDLE_ONNX -> paddle.recognize(bitmap, kind)
            else -> mlKit.recognize(bitmap, kind)
        }
        val settings = settingsRepository.get()
        if (!settings.mergeAdjacentBlocks) return raw
        // 详细日志：打 box 坐标，用于诊断"为什么这两段没合"。仅 Timber（logcat），不写 LogRepository
        // 避免污染用户可见日志。tag = OcrMerge，过滤用。
        logBoxes("before", raw)
        val merged = mergeAdjacentBlocks(raw, MergeParams.forStrength(settings.mergeStrength))
        Timber.tag("OcrMerge").i(
            "strength=%s, %d -> %d blocks", settings.mergeStrength, raw.size, merged.size
        )
        logBoxes("after", merged)
        return merged
    }

    private fun logBoxes(label: String, blocks: List<TextBlock>) {
        blocks.forEachIndexed { i, b ->
            val r = b.boundingBox
            Timber.tag("OcrMerge").i(
                "%s #%d (%d,%d,%d,%d) h=%d w=%d: %s",
                label, i + 1, r.left, r.top, r.right, r.bottom,
                r.height(), r.width(),
                b.text.take(60).replace("\n", "⏎")
            )
        }
    }

    override fun close() {
        mlKit.close()
        baidu.close()
        tencent.close()
        paddle.close()
    }

    /**
     * 两阶段段落聚类：
     *
     *  阶段 1：**同行邻接合并** —— 把同一行内左右紧邻的小 box 合成一行（空格拼接）。
     *           例："WHEN SHE" + "SOBERS UP." → "WHEN SHE SOBERS UP."
     *
     *  阶段 2：**同列上下合并** —— 把上一阶段产物中"水平区间有相交 + 上下邻接 + 高度
     *           接近"的两行合成一个段落（换行符 \n 拼接）。
     *           例：漫画气泡内 3 行小字合并成单条 3 行段落，整段送翻译，语境完整。
     *
     * 这样 OCR 行级输出 → 视觉段落级输出，下游叠加层就只看到几个大 box，不会再因为
     * "一段话被拆成 5 个相邻框"导致译文层互相重叠。
     */
    private fun mergeAdjacentBlocks(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val lineMerged = mergeSameLine(blocks, params)
        return mergeParagraph(lineMerged, params)
    }

    private fun mergeSameLine(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val result = mutableListOf<TextBlock>()
        for (b in sorted) {
            val last = result.lastOrNull()
            if (last != null && sameLineAdjacent(last, b, params)) {
                result[result.size - 1] = unionMerge(last, b, separator = " ")
            } else {
                result.add(b)
            }
        }
        return result
    }

    /**
     * 阶段 2：把"上下邻接 + 水平区间相交"的两段合成一个段落。
     * 多轮合并直到稳定（一个气泡 4 行可能要 3 轮）。
     */
    private fun mergeParagraph(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        var current = blocks
        repeat(10) {
            val (next, merged) = mergeParagraphOnce(current, params)
            if (!merged) return current
            current = next
        }
        return current
    }

    private fun mergeParagraphOnce(blocks: List<TextBlock>, params: MergeParams): Pair<List<TextBlock>, Boolean> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val used = BooleanArray(sorted.size)
        val result = mutableListOf<TextBlock>()
        var anyMerged = false
        for (i in sorted.indices) {
            if (used[i]) continue
            var acc = sorted[i]
            used[i] = true
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                if (verticallyAdjacent(acc, sorted[j], params)) {
                    acc = unionMerge(acc, sorted[j], separator = "\n")
                    used[j] = true
                    anyMerged = true
                }
            }
            result.add(acc)
        }
        return result to anyMerged
    }

    private fun unionMerge(a: TextBlock, b: TextBlock, separator: String): TextBlock {
        val la = a.boundingBox; val rb = b.boundingBox
        val unionBox = Rect(
            minOf(la.left, rb.left),
            minOf(la.top, rb.top),
            maxOf(la.right, rb.right),
            maxOf(la.bottom, rb.bottom)
        )
        return a.copy(
            text = when {
                a.text.isBlank() -> b.text
                b.text.isBlank() -> a.text
                else -> a.text + separator + b.text
            },
            boundingBox = unionBox
        )
    }

    private fun sameLineAdjacent(a: TextBlock, b: TextBlock, params: MergeParams): Boolean {
        // 左右 normalize：让 ra 总是更左的，rb 总是更右的。否则 unionMerge 后 box 的 right 扩张，
        // 后续比较时 gap = b.left - last.right 会出现严重的负值（-100+），所有竖排日漫这种
        // "右列 top 反而更小、排序后被先处理"的场景全部漏合。
        val (ra, rb) = if (a.boundingBox.left <= b.boundingBox.left)
            a.boundingBox to b.boundingBox
        else
            b.boundingBox to a.boundingBox
        val ha = ra.height().coerceAtLeast(1)
        val hb = rb.height().coerceAtLeast(1)
        val avgH = (ha + hb) / 2
        if (maxOf(ha, hb).toFloat() / minOf(ha, hb) > params.heightRatioLimit) return false
        val sameLine = kotlin.math.abs(ra.top - rb.top) < avgH * params.sameLineTopTolerance
        if (!sameLine) return false
        val gap = rb.left - ra.right
        return gap >= -5 && gap <= avgH * params.adjacentGapRatio
    }

    private fun verticallyAdjacent(a: TextBlock, b: TextBlock, params: MergeParams): Boolean {
        val ra = a.boundingBox; val rb = b.boundingBox
        val ha = ra.height().coerceAtLeast(1)
        val hb = rb.height().coerceAtLeast(1)
        val lineH = minOf(ha, hb)
        if (rb.top < ra.bottom - lineH * 0.3f) return false
        val vGap = rb.top - ra.bottom
        if (vGap > lineH * params.verticalGapRatio) return false
        val overlapLeft = maxOf(ra.left, rb.left)
        val overlapRight = minOf(ra.right, rb.right)
        val overlapW = overlapRight - overlapLeft
        if (overlapW <= 0) return false
        val minW = minOf(ra.width(), rb.width())
        if (overlapW.toFloat() / minW < params.horizontalOverlapRatio) return false
        if (maxOf(ha, hb).toFloat() / minOf(ha, hb) > params.heightRatioLimit) return false
        return true
    }

    /**
     * 合并算法的 5 个阈值。封装成 data class 方便按 [MergeStrength] 切换三套预设，
     * 也方便日志 / 单元测试。各字段含义见各 [sameLineAdjacent] / [verticallyAdjacent] 用法。
     */
    private data class MergeParams(
        val sameLineTopTolerance: Float,
        val adjacentGapRatio: Float,
        val verticalGapRatio: Float,
        val horizontalOverlapRatio: Float,
        val heightRatioLimit: Float
    ) {
        companion object {
            fun forStrength(s: MergeStrength): MergeParams = when (s) {
                MergeStrength.CONSERVATIVE -> MergeParams(
                    sameLineTopTolerance = 0.35f,
                    adjacentGapRatio = 0.8f,
                    verticalGapRatio = 0.5f,
                    horizontalOverlapRatio = 0.5f,
                    heightRatioLimit = 1.4f
                )
                MergeStrength.STANDARD -> MergeParams(
                    sameLineTopTolerance = 0.5f,
                    adjacentGapRatio = 1.2f,
                    verticalGapRatio = 0.8f,
                    horizontalOverlapRatio = 0.3f,
                    heightRatioLimit = 1.6f
                )
                MergeStrength.AGGRESSIVE -> MergeParams(
                    sameLineTopTolerance = 0.7f,
                    adjacentGapRatio = 1.8f,
                    verticalGapRatio = 1.3f,
                    horizontalOverlapRatio = 0.15f,
                    // 竖排日漫一列字数差异大（4 字 vs 6 字 → 高度比 2.1），原 2.0 太严
                    heightRatioLimit = 2.5f
                )
            }
        }
    }
}
