package com.gameocr.app.trigger

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.service.CaptureService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 可选的无障碍触发器：监听 **音量+ 与 音量- 同时按下 300ms** 作为全局快捷键，触发一次截屏。
 *
 * 状态机（围绕 [comboLatched]）：
 *  - 单键按下：[comboLatched]=false，DOWN 都 return false 让系统正常调音量
 *  - 两键都按下时翻到 [comboLatched]=true 并启动 300ms 触发器
 *  - **一旦 latch，就一直拦截所有音量键事件**直到两键都松开。这点很关键：
 *    MIUI / 多数 ROM 上音量是按 auto-repeat DOWN 持续调的；用户先松一键、再松另一键的
 *    瞬间，剩下那键的 repeat DOWN 不能放过去，否则音量会一直加 / 减直到用户手动反向操作。
 *
 * 不读取屏幕内容、不解析 View 树。所需能力仅 [flagRequestFilterKeyEvents]。
 */
@AndroidEntryPoint
class GameOcrAccessibilityService : AccessibilityService() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var enabled = false

    // 各键当前是否按着；latch 是"是否处于双键拦截会话"
    private var volumeUpDown = false
    private var volumeDownDown = false
    private var comboLatched = false

    private val triggerRunnable = Runnable {
        // 触发时只要 latch 还在就 fire（即使期间用户已松开一键也算成功——按住够 300ms 就行）
        if (comboLatched) {
            Timber.i("A11y combo fired: vol+ vol- long-press ${COMBO_HOLD_MS}ms")
            triggerCapture()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 用 Flow 订阅 settings 变化，避免之前 runBlocking 阻塞主线程 + 缓存不刷新的 bug
        scope.launch {
            settingsRepository.settings
                .map { it.a11yVolumeTrigger }
                .distinctUntilChanged()
                .collect { enabled = it }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        val isVolUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolUp && !isVolDown) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 首次 DOWN 才更新 latch 检测；auto-repeat 时 repeatCount > 0，跳过状态更新但
                // 仍要按当前 latch 决定是否拦截（关键：拦截 auto-repeat 才能停掉持续调音量）
                if (event.repeatCount == 0) {
                    if (isVolUp) volumeUpDown = true
                    if (isVolDown) volumeDownDown = true
                    // 两键都按下了：开 latch + 启动 300ms 定时器
                    if (!comboLatched && volumeUpDown && volumeDownDown) {
                        comboLatched = true
                        mainHandler.removeCallbacks(triggerRunnable)
                        mainHandler.postDelayed(triggerRunnable, COMBO_HOLD_MS)
                    }
                }
                // 拦截策略：latch 期间所有音量键 DOWN / repeat 全吞；否则放给系统调音量
                return comboLatched
            }
            KeyEvent.ACTION_UP -> {
                if (isVolUp) volumeUpDown = false
                if (isVolDown) volumeDownDown = false
                val wasLatched = comboLatched
                // 两键都松开了 → 退出拦截会话
                if (!volumeUpDown && !volumeDownDown) {
                    comboLatched = false
                    mainHandler.removeCallbacks(triggerRunnable)
                }
                // latch 期间的 UP 也吞掉，避免部分 ROM 在 UP 时再调一次音量
                return wasLatched
            }
        }
        return false
    }

    private fun triggerCapture() {
        val svc = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_TRIGGER_ONCE
        }
        startService(svc)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(triggerRunnable)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 双键同时按下持续这么久后才触发。300ms 是响应快 vs 误触低之间的常见折中。 */
        private const val COMBO_HOLD_MS = 300L
    }
}
