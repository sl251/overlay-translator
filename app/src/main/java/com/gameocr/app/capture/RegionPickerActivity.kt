package com.gameocr.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gameocr.app.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 全屏透明 Activity，拉框选区后保存到 [SettingsRepository] 并 finish。
 * CaptureService 下次截屏时会读 settings.captureRegion 做裁剪。
 */
@AndroidEntryPoint
class RegionPickerActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = RegionPickerView(
            context = this,
            onRegionPicked = { rect -> saveAndFinish(rect) },
            onCancel = { finish() }
        )
        setContentView(view)
    }

    private fun saveAndFinish(rect: Rect) {
        scope.launch {
            val region = if (rect.width() < 20 || rect.height() < 20) {
                null
            } else {
                CaptureRegion(rect.left, rect.top, rect.right, rect.bottom)
            }
            settingsRepository.update { it.copy(captureRegion = region) }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, RegionPickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
