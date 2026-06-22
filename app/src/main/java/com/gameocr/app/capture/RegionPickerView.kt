package com.gameocr.app.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * 全屏拉框选择区域。手指按下定起点，移动定终点，松开通知调用方。
 */
class RegionPickerView(
    context: Context,
    private val onRegionPicked: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val mask = Paint().apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val rectStroke = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val clearInside = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val tipPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
    }

    private var startX = -1f
    private var startY = -1f
    private var endX = -1f
    private var endY = -1f
    private var picking = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 暗色遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)

        if (picking && startX >= 0 && endX >= 0) {
            val r = currentRect()
            // 挖空选框
            canvas.drawRect(r, clearInside)
            // 边框
            canvas.drawRect(r, rectStroke)
        } else {
            canvas.drawText("拖动框选要翻译的区域，双击空白处使用整屏", 80f, 160f, tipPaint)
        }
    }

    private fun currentRect(): Rect {
        val l = minOf(startX, endX).toInt().coerceAtLeast(0)
        val t = minOf(startY, endY).toInt().coerceAtLeast(0)
        val r = maxOf(startX, endX).toInt().coerceAtMost(width)
        val b = maxOf(startY, endY).toInt().coerceAtMost(height)
        return Rect(l, t, r, b)
    }

    private var lastTapTime = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = startX
                endY = startY
                picking = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val rect = currentRect()
                if (rect.width() < 20 || rect.height() < 20) {
                    // 当成点击：双击算整屏
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) {
                        onRegionPicked(Rect(0, 0, width, height))
                    } else {
                        lastTapTime = now
                        picking = false
                        invalidate()
                    }
                } else {
                    onRegionPicked(rect)
                }
            }
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }
}
