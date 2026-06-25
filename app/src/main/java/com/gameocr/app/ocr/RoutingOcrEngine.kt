package com.gameocr.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gameocr.app.data.LogRepository
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
    private val youdao: YoudaoOcrEngine,
    private val settingsRepository: SettingsRepository,
    private val logRepository: LogRepository
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val raw = when (kind) {
            OcrEngineKind.BAIDU -> baidu.recognize(bitmap, kind)
            OcrEngineKind.TENCENT -> tencent.recognize(bitmap, kind)
            OcrEngineKind.YOUDAO -> youdao.recognize(bitmap, kind)
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
            "strength=%s, %d -> %d blocks (final)",
            settings.mergeStrength, raw.size, merged.size
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
        youdao.close()
    }

    /**
     * 两阶段段落聚类：
     *
     *  阶段 1：**同行/同列邻接合并** —— 把"主轴方向相邻"的小 box 合成一段。
     *           横排：同一行内左右紧邻 → 空格拼接（"WHEN SHE" + "SOBERS UP." → "WHEN SHE SOBERS UP."）
     *           竖排：同一列内上下接续 → 换行拼接
     *
     *  阶段 2：**跨主轴段落合并** —— 把"主轴对齐 + 次轴邻接"的两段合成一个段落。
     *           横排：水平区间相交 + 上下邻接 → 换行拼接（漫画气泡 3 行小字 → 单条 3 行）
     *           竖排：垂直区间相交 + 左右邻接 → 换行拼接，且**列拼接顺序按 right-to-left**（日文竖排）
     *
     * 方向通过 [detectOrientation] 自动探测（按 box 高宽比中位数 / portrait 占比）。这样
     * OCR 行级输出 → 视觉段落级输出，下游叠加层只看到几个大 box，不会因"一段话被拆成 5
     * 个相邻框"导致译文层互相重叠。
     */
    private fun mergeAdjacentBlocks(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val orientation = detectOrientation(blocks)
        return when (orientation) {
            Orientation.HORIZONTAL -> {
                val lineMerged = mergeSameLine(blocks, params)
                val msg1 = "[H] stage1 sameLine: ${blocks.size} -> ${lineMerged.size}"
                Timber.tag("OcrMerge").i(msg1)
                logRepository.info(LogRepository.Category.OCR, msg1)
                val paraMerged = mergeParagraph(lineMerged, params)
                val msg2 = "[H] stage2 paragraph: ${lineMerged.size} -> ${paraMerged.size}"
                Timber.tag("OcrMerge").i(msg2)
                logRepository.info(LogRepository.Category.OCR, msg2)
                paraMerged
            }
            Orientation.VERTICAL -> {
                // 竖排日文专属：先丢振假名（ふりがな汉字注音小列），避免译文里出现
                // "しっぱい/失敗"读音+汉字重复，也让 overlay 不再两列叠在一起。
                val deFurigana = removeFurigana(blocks)
                if (deFurigana.size != blocks.size) {
                    val msg = "[V] removeFurigana: ${blocks.size} -> ${deFurigana.size}"
                    Timber.tag("OcrMerge").i(msg)
                    logRepository.info(LogRepository.Category.OCR, msg)
                }
                val columnMerged = mergeSameColumn(deFurigana, params)
                val msg1 = "[V] stage1 sameColumn: ${deFurigana.size} -> ${columnMerged.size}"
                Timber.tag("OcrMerge").i(msg1)
                logRepository.info(LogRepository.Category.OCR, msg1)
                val paraMerged = mergeColumnsToParagraph(columnMerged, params)
                val msg2 = "[V] stage2 columnsToPara (R→L): ${columnMerged.size} -> ${paraMerged.size}"
                Timber.tag("OcrMerge").i(msg2)
                logRepository.info(LogRepository.Category.OCR, msg2)
                paraMerged
            }
        }
    }

    /**
     * 探测当前帧排版方向。
     *
     * 判据：把 box 按 h/w 比分成 portrait（h > w * 1.3）与其它，portrait 占比 ≥ 50% → 竖排。
     * 阈值留 30% 缓冲（h/w∈[0.77, 1.3]）算"中性"——单字符 box / 漫画拟声词常落在这区间，
     * 不影响判定。
     *
     * 同时打日志，让 logcat 能溯源到为什么走了某条路径——竖排日漫一旦被误判为横排，stage2
     * 就用错了"水平相交"判据，导致即使激进档也合不上。
     */
    private fun detectOrientation(blocks: List<TextBlock>): Orientation {
        val portrait = blocks.count {
            val r = it.boundingBox
            r.height() > r.width() * 1.3f
        }
        val landscape = blocks.count {
            val r = it.boundingBox
            r.width() > r.height() * 1.3f
        }
        val ratio = portrait.toFloat() / blocks.size
        val orientation = if (portrait > landscape && ratio >= 0.5f)
            Orientation.VERTICAL else Orientation.HORIZONTAL
        val msg = "[detect] orientation=$orientation, portrait=$portrait landscape=$landscape " +
            "total=${blocks.size} (portraitRatio=${"%.2f".format(ratio)})"
        Timber.tag("OcrMerge").i(msg)
        logRepository.info(LogRepository.Category.OCR, msg)
        return orientation
    }

    private enum class Orientation { HORIZONTAL, VERTICAL }

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

    /**
     * 竖排日文振假名（ふりがな汉字注音）过滤。
     *
     * 判据（同时满足才算振假名，丢掉）：
     *  - 紧贴某更宽 box（水平 gap ≤ 自身宽度）
     *  - 宽度比小（self.w / big.w < 0.6）
     *  - 高度比够大（self.h / big.h > 0.25，注音覆盖足够汉字范围；过滤孤立小段如「ため」）
     *  - 垂直区间完全被大 box 包住（注音只标自己范围内的字，不会越界）
     *
     * 调过的样本：百度高精度+位置版输出的「しっぱい」「けんこうてき」「にんげん」「はんにん」
     * 都能命中；同帧的孤立小段「ため」（h/big.h=0.12）「おる」（h/big.h=0.16）保留。
     */
    private fun removeFurigana(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size < 2) return blocks
        return blocks.filter { small ->
            val sb = small.boundingBox
            val isFurigana = blocks.any { other ->
                if (other === small) return@any false
                val bb = other.boundingBox
                if (bb.width() <= sb.width()) return@any false
                if (sb.width().toFloat() / bb.width() >= 0.6f) return@any false
                if (sb.height().toFloat() / bb.height() <= 0.25f) return@any false
                val hGap = if (sb.left >= bb.left) sb.left - bb.right else bb.left - sb.right
                if (hGap > sb.width()) return@any false
                if (sb.top < bb.top - 10 || sb.bottom > bb.bottom + 10) return@any false
                true
            }
            !isFurigana
        }
    }

    /**
     * 竖排阶段 1：把"同一列内上下接续"的 box 用 \n 串成单段。
     *
     * 镜像 [mergeSameLine]：主轴从"水平 + 高度参考"换到"垂直 + 宽度参考"。判据用宽度
     * 而非高度——竖排里 box 宽度 ≈ 单字大小，与横排里 box 高度同义。
     */
    private fun mergeSameColumn(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.left }, { it.boundingBox.top }))
        val result = mutableListOf<TextBlock>()
        for (b in sorted) {
            val last = result.lastOrNull()
            if (last != null && sameColumnAdjacent(last, b, params)) {
                result[result.size - 1] = unionMerge(last, b, separator = "\n")
            } else {
                result.add(b)
            }
        }
        return result
    }

    /**
     * 竖排阶段 2：把"垂直区间相交 + 左右邻接"的两列合成一段。
     *
     * 关键：列拼接顺序 **right-to-left** —— 日文竖排从最右一列起读。sortedByDescending(left)
     * 让右列先成为 acc，后续的左列 union 进去时 acc 在前 sep 在后 → 文本顺序正确。
     */
    private fun mergeColumnsToParagraph(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        var current = blocks
        repeat(10) {
            val (next, merged) = mergeColumnsOnce(current, params)
            if (!merged) return current
            current = next
        }
        return current
    }

    private fun mergeColumnsOnce(blocks: List<TextBlock>, params: MergeParams): Pair<List<TextBlock>, Boolean> {
        val sorted = blocks.sortedWith(
            compareByDescending<TextBlock> { it.boundingBox.left }.thenBy { it.boundingBox.top }
        )
        val used = BooleanArray(sorted.size)
        val result = mutableListOf<TextBlock>()
        var anyMerged = false
        for (i in sorted.indices) {
            if (used[i]) continue
            var acc = sorted[i]
            used[i] = true
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                if (columnsHorizontallyAdjacent(acc, sorted[j], params)) {
                    // acc 是右列（sort desc by left 保证），sorted[j] 是左列 → 文本顺序 acc 在前
                    acc = unionMerge(acc, sorted[j], separator = "\n")
                    used[j] = true
                    anyMerged = true
                }
            }
            result.add(acc)
        }
        return result to anyMerged
    }

    private fun sameColumnAdjacent(a: TextBlock, b: TextBlock, params: MergeParams): Boolean {
        // 上下 normalize：让 ra 总是更上的，rb 总是更下的；与 sameLineAdjacent 镜像。
        val (ra, rb) = if (a.boundingBox.top <= b.boundingBox.top)
            a.boundingBox to b.boundingBox
        else
            b.boundingBox to a.boundingBox
        val wa = ra.width().coerceAtLeast(1)
        val wb = rb.width().coerceAtLeast(1)
        val avgW = (wa + wb) / 2
        if (maxOf(wa, wb).toFloat() / minOf(wa, wb) > params.heightRatioLimit) return false
        val sameCol = kotlin.math.abs(ra.left - rb.left) < avgW * params.sameLineTopTolerance
        if (!sameCol) return false
        val gap = rb.top - ra.bottom
        return gap >= -5 && gap <= avgW * params.adjacentGapRatio
    }

    /**
     * 竖排"左右邻接 + 垂直区间相交"判据。与 [verticallyAdjacent] 严格镜像：把"高度"
     * 全部换成"宽度"，"水平相交"换成"垂直相交"。
     */
    private fun columnsHorizontallyAdjacent(a: TextBlock, b: TextBlock, params: MergeParams): Boolean {
        val ra = a.boundingBox; val rb = b.boundingBox
        val wa = ra.width().coerceAtLeast(1)
        val wb = rb.width().coerceAtLeast(1)
        val colW = minOf(wa, wb)
        // 左右先 normalize：rR 是右列、rL 是左列
        val (rR, rL) = if (ra.left >= rb.left) ra to rb else rb to ra
        // 镜像 verticallyAdjacent 的 "rb.top < ra.bottom - lineH * 0.3"：允许小重叠
        if (rL.right < rR.left - colW * 0.3f) return false
        val hGap = rR.left - rL.right
        if (hGap > colW * params.verticalGapRatio) return false
        val overlapTop = maxOf(ra.top, rb.top)
        val overlapBottom = minOf(ra.bottom, rb.bottom)
        val overlapH = overlapBottom - overlapTop
        if (overlapH <= 0) return false
        val minH = minOf(ra.height(), rb.height())
        if (overlapH.toFloat() / minH < params.horizontalOverlapRatio) return false
        // 同 verticallyAdjacent：stage2 不再做 size ratio limit
        return true
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
        // 注意：不再做 heightRatioLimit 检查——stage2 是跨行/跨列合并，acc 累积后段
        // 高/段宽天然就远大于单行/单列，再用 ratio 反而会让剩余孤立小列接不进段。
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
