package com.gameocr.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.capture.MediaProjectionRequestActivity
import com.gameocr.app.capture.RegionPickerActivity
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.rom.RomHelper
import com.gameocr.app.service.CaptureService
import com.gameocr.app.service.CaptureServiceState
import com.gameocr.app.shizuku.ShizukuCapabilities
import com.gameocr.app.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canDrawOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var region by remember { mutableStateOf<CaptureRegion?>(null) }
    var shizukuAvail by remember { mutableStateOf(ShizukuCapabilities.Availability.NOT_INSTALLED) }
    var batteryOk by remember { mutableStateOf(false) }
    val serviceRunning by CaptureServiceState.running.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    canDrawOverlay = Settings.canDrawOverlays(context)
                    region = viewModel.currentRegion()
                    shizukuAvail = viewModel.shizukuAvailability(context)
                    batteryOk = RomHelper.isIgnoringBatteryOptimizations(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "屏译",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Text(" 设置", modifier = Modifier.padding(start = 4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态卡
            StatusCard(
                canDrawOverlay = canDrawOverlay,
                region = region,
                shizukuAvail = shizukuAvail,
                batteryOk = batteryOk,
                serviceRunning = serviceRunning
            )

            // 主操作：启动
            ActionCard(title = if (serviceRunning) "服务运行中" else "启动截屏服务") {
                if (!canDrawOverlay) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                            context.startActivity(intent)
                        }
                    ) { Text("先授权悬浮窗") }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !serviceRunning,
                        onClick = {
                            context.startActivity(MediaProjectionRequestActivity.newIntent(context))
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(
                            if (serviceRunning) "  已在运行" else "  MediaProjection 启动",
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    if (shizukuAvail != ShizukuCapabilities.Availability.NOT_INSTALLED) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !serviceRunning,
                            onClick = {
                                scope.launch {
                                    val ok = viewModel.ensureShizukuPermission()
                                    shizukuAvail = viewModel.shizukuAvailability(context)
                                    if (ok) {
                                        val svc = Intent(context, CaptureService::class.java).apply {
                                            action = CaptureService.ACTION_START
                                            putExtra(CaptureService.EXTRA_USE_SHIZUKU, true)
                                        }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            ContextCompat.startForegroundService(context, svc)
                                        } else {
                                            context.startService(svc)
                                        }
                                    }
                                }
                            }
                        ) { Text("Shizuku 启动（免每次弹窗）") }
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = serviceRunning,
                        onClick = { context.startService(CaptureService.stopIntent(context)) }
                    ) { Text(if (serviceRunning) "停止服务" else "服务未运行") }
                }
            }

            // 区域
            ActionCard(title = "截屏区域") {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { context.startActivity(RegionPickerActivity.newIntent(context)) }
                ) { Text("框选区域") }
                if (region != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                viewModel.clearRegion()
                                region = null
                            }
                        }
                    ) { Text("清除区域，恢复整屏") }
                }
            }

            // 系统兼容
            ActionCard(title = "系统兼容引导") {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        RomHelper.launchFirstAvailable(context, RomHelper.autoStartIntents(context))
                    }
                ) { Text("打开自启动 / 电池白名单设置") }
            }

            // 底部留空
            Box(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun StatusCard(
    canDrawOverlay: Boolean,
    region: CaptureRegion?,
    shizukuAvail: ShizukuCapabilities.Availability,
    batteryOk: Boolean,
    serviceRunning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "当前状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StatusRow(
                "截屏服务",
                ok = serviceRunning,
                detail = if (serviceRunning) "运行中" else "未运行"
            )
            StatusRow("悬浮窗权限", canDrawOverlay)
            StatusRow(
                "截屏区域",
                ok = true,
                detail = region?.let { "${it.width}×${it.height} @ (${it.left},${it.top})" } ?: "整屏"
            )
            StatusRow(
                "Shizuku",
                ok = shizukuAvail == ShizukuCapabilities.Availability.READY,
                detail = when (shizukuAvail) {
                    ShizukuCapabilities.Availability.READY -> "就绪"
                    ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> "未授权"
                    ShizukuCapabilities.Availability.NOT_RUNNING -> "服务未运行"
                    ShizukuCapabilities.Availability.NOT_INSTALLED -> "未安装"
                }
            )
            StatusRow("电池白名单", batteryOk)
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(8.dp)
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        )
        Text(
            text = detail ?: if (ok) "已开" else "未开",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val shizukuManager: ShizukuManager,
    private val shizukuCapabilities: ShizukuCapabilities
) : ViewModel() {
    suspend fun currentRegion(): CaptureRegion? = repo.get().captureRegion
    suspend fun clearRegion() {
        repo.update { it.copy(captureRegion = null) }
    }
    fun shizukuAvailability(context: android.content.Context): ShizukuCapabilities.Availability =
        shizukuCapabilities.availability(context)
    suspend fun ensureShizukuPermission(): Boolean = shizukuManager.requestPermission()
}
