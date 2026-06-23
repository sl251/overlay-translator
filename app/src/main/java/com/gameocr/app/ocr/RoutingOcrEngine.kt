package com.gameocr.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

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
        return if (settings.mergeAdjacentBlocks) mergeAdjacentBlocks(raw) else raw
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
    private fun mergeAdjacentBlocks(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val lineMerged = mergeSameLine(blocks)
        return mergeParagraph(lineMerged)
    }

    private fun mergeSameLine(blocks: List<TextBlock>): List<TextBlock> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val result = mutableListOf<TextBlock>()
        for (b in sorted) {
            val last = result.lastOrNull()
            if (last != null && sameLineAdjacent(last, b)) {
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
    private fun mergeParagraph(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        var current = blocks
        repeat(10) {
            val (next, merged) = mergeParagraphOnce(current)
            if (!merged) return current
            current = next
        }
        return current
    }

    private fun mergeParagraphOnce(blocks: List<TextBlock>): Pair<List<TextBlock>, Boolean> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val used = BooleanArray(sorted.size)
        val result = mutableListOf<TextBlock>()
        var anyMerged = false
        for (i in sorted.indices) {
            if (used[i]) continue
            var acc = sorted[i]
            used[i] = true
            // 找最佳"在 acc 正下方"候选：垂直最近 + 水平相交 + 高度相近
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                if (verticallyAdjacent(acc, sorted[j])) {
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

    private fun sameLineAdjacent(a: TextBlock, b: TextBlock): Boolean {
        val ra = a.boundingBox; val rb = b.boundingBox
        val ha = ra.height().coerceAtLeast(1)
        val hb = rb.height().coerceAtLeast(1)
        val avgH = (ha + hb) / 2
        // 高度差不能太大（不同字号的两段不该合并）
        if (maxOf(ha, hb).toFloat() / minOf(ha, hb) > 1.6f) return false
        val sameLine = kotlin.math.abs(ra.top - rb.top) < avgH * SAME_LINE_TOP_TOLERANCE
        if (!sameLine) return false
        val gap = rb.left - ra.right
        return gap >= -5 && gap <= avgH * ADJACENT_GAP_RATIO
    }

    private fun verticallyAdjacent(a: TextBlock, b: TextBlock): Boolean {
        val ra = a.boundingBox; val rb = b.boundingBox
        val ha = ra.height().coerceAtLeast(1)
        val hb = rb.height().coerceAtLeast(1)
        // 单行高度估算（合并后的多行 box 用平均行高 ≈ height / lineCount，但我们不知道
        // line count，所以用 max(原始行高, height / 4) 兜底，避免长段落被一直合并）。
        val lineH = minOf(ha, hb)
        // b 在 a 下方？
        if (rb.top < ra.bottom - lineH * 0.3f) return false
        // 垂直间距：相邻一行（间距 < lineH * 0.8）
        val vGap = rb.top - ra.bottom
        if (vGap > lineH * VERTICAL_GAP_RATIO) return false
        // 水平相交：a 和 b 的 [left, right] 区间必须有相交且重叠 > 30%
        val overlapLeft = maxOf(ra.left, rb.left)
        val overlapRight = minOf(ra.right, rb.right)
        val overlapW = overlapRight - overlapLeft
        if (overlapW <= 0) return false
        val minW = minOf(ra.width(), rb.width())
        if (overlapW.toFloat() / minW < HORIZONTAL_OVERLAP_RATIO) return false
        // 行高一致性：避免标题（大字）跟正文（小字）合并
        if (maxOf(ha, hb).toFloat() / minOf(ha, hb) > 1.6f) return false
        return true
    }

    companion object {
        private const val SAME_LINE_TOP_TOLERANCE = 0.5f
        private const val ADJACENT_GAP_RATIO = 1.2f
        // 垂直间距 / 行高 上限：< 0.8 表示行间距正常，超过认为是两段
        private const val VERTICAL_GAP_RATIO = 0.8f
        // 水平重叠占短边宽度的比例阈值
        private const val HORIZONTAL_OVERLAP_RATIO = 0.3f
    }
}
