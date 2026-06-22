package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 Shizuku 的截屏实现（experimental）：通过反射调用 Shizuku 的 hidden `newProcess`
 * API 在 shell uid 下执行 `screencap -p`，读 PNG 字节流后解码成 Bitmap。
 *
 * 优势：
 * - 免 MediaProjection 每次系统授权窗（Android 14+ 强制弹）
 *
 * 代价：
 * - 反射 hidden API，未来 Shizuku 大版本变动可能失效
 * - 每次截屏 ~150-300ms，帧率上限 ~5 FPS，仅适合"按需触发"
 *
 * 生产路径建议改为 ShizukuUserService + aidl，本类作为最小可用 PoC。
 */
class ShizukuScreenshotter : Screenshotter {

    private val released = AtomicBoolean(false)

    override val isReady: Boolean
        get() = !released.get() && runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext null
        try {
            val process = invokeNewProcess(arrayOf("sh", "-c", "screencap -p"))
                ?: return@withContext null
            val out = ByteArrayOutputStream(8 * 1024 * 1024)
            val inStream = process.javaClass.getMethod("getInputStream").invoke(process) as java.io.InputStream
            inStream.use { it.copyTo(out) }
            val ec = runCatching {
                process.javaClass.getMethod("waitFor").invoke(process) as Int
            }.getOrDefault(-1)
            if (ec != 0) {
                Timber.w("shizuku screencap exit=$ec")
                return@withContext null
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) {
                Timber.w("shizuku screencap empty payload")
                return@withContext null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            Timber.w(t, "ShizukuScreenshotter capture failed")
            null
        }
    }

    /** 反射调用 `Shizuku.newProcess(String[], String[]?, String?)`，因为它在 13.x 被标 @hide。 */
    private fun invokeNewProcess(cmd: Array<String>): Any? {
        val cls = Shizuku::class.java
        val method = cls.declaredMethods.firstOrNull { it.name == "newProcess" } ?: return null
        method.isAccessible = true
        return method.invoke(null, cmd, null, null)
    }

    override fun release() {
        released.set(true)
    }
}
