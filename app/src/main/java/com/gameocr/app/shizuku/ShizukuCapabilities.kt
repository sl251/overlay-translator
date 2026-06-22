package com.gameocr.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 能力探测。把"包是否装、服务是否运行、是否已授权"三个状态合并成 [Availability]。
 */
@Singleton
class ShizukuCapabilities @Inject constructor(
    private val manager: ShizukuManager
) {

    fun isShizukuInstalled(context: Context): Boolean = listOf(
        "moe.shizuku.privileged.api",  // 当前 GitHub 版
        "moe.shizuku.api"              // 旧版
    ).any { pkg ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }

    fun isShizukuReady(context: Context): Boolean = manager.isServiceRunning() && manager.hasPermission()

    enum class Availability { READY, INSTALLED_NOT_GRANTED, NOT_INSTALLED, NOT_RUNNING }

    fun availability(context: Context): Availability {
        if (!isShizukuInstalled(context)) return Availability.NOT_INSTALLED
        if (!manager.isServiceRunning()) return Availability.NOT_RUNNING
        if (!manager.hasPermission()) return Availability.INSTALLED_NOT_GRANTED
        return Availability.READY
    }
}
