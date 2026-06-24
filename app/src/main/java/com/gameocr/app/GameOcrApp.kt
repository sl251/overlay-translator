package com.gameocr.app

import android.app.Application
import com.gameocr.app.data.CrashRecorder
import com.gameocr.app.data.LogRepository
import com.gameocr.app.data.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class GameOcrApp : Application() {

    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 全局未捕获异常 → 写 <filesDir>/crash/*.crash 文件。先装再读，确保 onCreate 内部
        // 后续逻辑或子模块初始化崩溃也能被记到。
        CrashRecorder.install(this)
        // 上次启动遗留的 crash 文件 + API 30+ 的 ExitReasons（native/ANR/OOM）一并加载到
        // LogRepository，让用户在日志页就能看到上次为什么挂的。
        CrashRecorder.loadPendingCrashes(this, logRepository)
        CrashRecorder.loadExitReasons(this, logRepository)
        // 持续把脱敏后的 settings 快照塞给 CrashRecorder，crash 时同步读这个内存值即可，
        // 避免 crash handler 走 DataStore IO 二次 crash。
        appScope.launch {
            settingsRepository.settings.collect { settings ->
                CrashRecorder.updateSettingsSummary(CrashRecorder.formatSettings(settings))
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
