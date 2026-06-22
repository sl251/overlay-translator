package com.gameocr.app.capture

import kotlinx.serialization.Serializable

/**
 * 屏幕坐标系下的截屏区域。null 表示整屏。
 * left/top/right/bottom 均为屏幕像素绝对坐标。
 */
@Serializable
data class CaptureRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun isValid(): Boolean = width > 8 && height > 8
}
