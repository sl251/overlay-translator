package com.gameocr.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.gameocr.app.R
import kotlin.math.abs

/**
 * 悬浮触发按钮。单击触发一次截屏 → OCR → 翻译，长按通知调用方切换循环模式。
 *
 * 拖动跟系统拨号一样：手指按住后跟随移动，松开后吸边可在 M1 再补。
 */
class FloatingButtonManager(
    private val context: Context,
    private val onSingleTap: () -> Unit,
    private val onLongPress: () -> Unit
) {
    @Volatile var sizeDp: Int = 56

    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var progressView: LoopProgressView? = null

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    fun isShown(): Boolean = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return

        val density = context.resources.displayMetrics.density
        val size = (sizeDp.coerceIn(28, 128) * density).toInt()
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_overlay_button)
            setBackgroundResource(R.drawable.bg_floating_button)
            setPadding(8, 8, 8, 8)
        }
        // 循环模式进度环：叠加在 iv 上层全尺寸，stop 状态不绘任何东西
        val progress = LoopProgressView(context)
        val container = FrameLayout(context).apply {
            addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(progress, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        progressView = progress

        // 用 currentWindowMetrics 拿当前实际屏幕尺寸（横屏 / 竖屏都对）
        val (screenW, screenH) = currentScreenSize()

        val params = WindowManager.LayoutParams(
            size, size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            // 初始放在屏幕顶部 1/4 处，竖屏横屏都看得见
            y = (screenH / 4).coerceIn(size, screenH - size * 2)
        }

        attachTouchListener(container, params)
        wm.addView(container, params)
        view = container
        layoutParams = params
    }

    /** 改完 [sizeDp] 后调用以即时生效，无需重启服务。 */
    fun applyResize() {
        val params = layoutParams ?: return
        val v = view ?: return
        val density = context.resources.displayMetrics.density
        val size = (sizeDp.coerceIn(28, 128) * density).toInt()
        params.width = size
        params.height = size
        runCatching { wm.updateViewLayout(v, params) }
    }

    /** 屏幕方向变了：把圆球重新 clamp 进可见区域。 */
    fun onConfigurationChanged() {
        val params = layoutParams ?: return
        val v = view ?: return
        val (screenW, screenH) = currentScreenSize()
        params.x = params.x.coerceIn(0, screenW - params.width)
        params.y = params.y.coerceIn(0, screenH - params.height)
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    fun hide() {
        progressView?.stop()
        progressView = null
        view?.let {
            runCatching { wm.removeView(it) }
            view = null
            layoutParams = null
        }
    }

    /** 循环模式开关：active=true 时 [LoopProgressView] 按 [intervalMs] 周期匀速转一圈；false 时停。 */
    fun setLoopActive(active: Boolean, intervalMs: Long) {
        val pv = progressView ?: return
        if (active) pv.start(intervalMs) else pv.stop()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener(target: View, params: WindowManager.LayoutParams) {
        // 用系统标准 touchSlop 的 2 倍，避免轻微抖动被误判为"拖动"导致单击丢失
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2f
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()

        var downX = 0f
        var downY = 0f
        var initX = 0
        var initY = 0
        var downTime = 0L
        var moved = false
        var longPressFired = false

        val longPressRunnable = Runnable {
            if (!moved) {
                longPressFired = true
                onLongPress()
            }
        }

        target.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    initX = params.x
                    initY = params.y
                    downTime = System.currentTimeMillis()
                    moved = false
                    longPressFired = false
                    v.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        v.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        params.x = (initX + dx).toInt()
                        params.y = (initY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    // 只要没明显拖动 + 长按 callback 还没烧 → 都算单击
                    if (!moved && !longPressFired) {
                        onSingleTap()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
