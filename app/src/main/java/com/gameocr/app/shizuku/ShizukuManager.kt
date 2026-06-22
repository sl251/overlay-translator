package com.gameocr.app.shizuku

import android.content.pm.PackageManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Shizuku 权限与可用性管理。
 *
 * Shizuku 服务安装后通过 ADB / 无线调试启动，本 App 调用 [Shizuku.requestPermission] 申请使用其 IBinder。
 * 拿到权限后可以用 [Shizuku.newProcess] 在 shell uid 下执行 `screencap -p`，实现免 MediaProjection 弹窗截屏。
 */
@Singleton
class ShizukuManager @Inject constructor() {

    private val PERMISSION_REQUEST_CODE = 0xC4A

    /** Shizuku 服务是否就绪（用户已通过 ADB / 无线调试启动）。 */
    fun isServiceRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** 当前是否已被 Shizuku 授予权限。 */
    fun hasPermission(): Boolean = try {
        if (!isServiceRunning()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /**
     * 请求 Shizuku 权限。挂起到用户授权 / 拒绝。
     * 返回 true = 授权成功，false = 拒绝 / 未就绪。
     */
    suspend fun requestPermission(): Boolean {
        if (!isServiceRunning()) return false
        if (hasPermission()) return true
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Timber.w("Shizuku says we should explain — but we just ask anyway")
        }
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (t: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                cont.resume(false)
            }
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }
    }
}
