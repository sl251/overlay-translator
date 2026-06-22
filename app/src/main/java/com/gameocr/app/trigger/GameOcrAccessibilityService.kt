package com.gameocr.app.trigger

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 可选的无障碍触发器。仅监听音量上 / 下键作为全局快捷键，触发一次截屏 → OCR → 翻译。
 * 不读取屏幕内容、不解析 View 树，因此不涉及隐私敏感能力。
 *
 * 启用条件：用户在设置里打开 [a11yVolumeTrigger] + 在系统无障碍设置里启用本服务。
 * 任一不满足时 onKeyEvent 返回 false 让按键正常工作。
 */
@AndroidEntryPoint
class GameOcrAccessibilityService : AccessibilityService() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var enabled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch {
            // 启动后读一次最新开关
            enabled = runCatching { settingsRepository.get().a11yVolumeTrigger }.getOrDefault(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 同步读 enabled。settings 变更通过 onServiceConnected + 周期性 refresh 兜底。
        if (!enabled) {
            enabled = runCatching { runBlocking { settingsRepository.get().a11yVolumeTrigger } }.getOrDefault(false)
            if (!enabled) return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                triggerCapture()
                true // 拦截，不让系统改音量
            }
            else -> false
        }
    }

    private fun triggerCapture() {
        val svc = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_TRIGGER_ONCE
        }
        startService(svc)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
