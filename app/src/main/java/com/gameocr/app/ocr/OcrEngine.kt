package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.data.OcrEngineKind

interface OcrEngine {
    /** 在 [bitmap] 上跑 OCR。区域识别由调用方裁剪后传入。 */
    suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind = OcrEngineKind.ML_KIT_AUTO): List<TextBlock>

    /** 释放底层资源（ML Kit recognizer / Paddle ONNX session 等）。 */
    fun close()
}

/** 端侧模型未安装 / 损坏时抛出。CaptureService 看到这个异常会引导用户去设置页下载模型。 */
class ModelNotReadyException(message: String) : RuntimeException(message)
