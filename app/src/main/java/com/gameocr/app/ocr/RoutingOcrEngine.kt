package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.data.OcrEngineKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OCR 路由：按 [OcrEngineKind] 选 ML Kit / 百度 / 腾讯云 / NCNN 竖排日文 / PaddleOCR PP-OCRv4。
 */
@Singleton
class RoutingOcrEngine @Inject constructor(
    private val mlKit: MlKitOcrEngine,
    private val baidu: BaiduOcrEngine,
    private val tencent: TencentOcrEngine,
    private val ncnn: NcnnVerticalOcrEngine,
    private val paddle: PaddleOcrEngine
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> =
        when (kind) {
            OcrEngineKind.BAIDU -> baidu.recognize(bitmap, kind)
            OcrEngineKind.TENCENT -> tencent.recognize(bitmap, kind)
            OcrEngineKind.NCNN_JAPANESE_VERTICAL -> ncnn.recognize(bitmap, kind)
            OcrEngineKind.PADDLE_ONNX -> paddle.recognize(bitmap, kind)
            else -> mlKit.recognize(bitmap, kind)
        }

    override fun close() {
        mlKit.close()
        baidu.close()
        tencent.close()
        ncnn.close()
        paddle.close()
    }
}
