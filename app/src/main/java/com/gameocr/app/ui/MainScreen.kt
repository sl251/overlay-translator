package com.gameocr.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gameocr.app.BuildConfig
import com.gameocr.app.R
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canDrawOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var region by remember { mutableStateOf<CaptureRegion?>(null) }
    var shizukuAvail by remember { mutableStateOf(ShizukuCapabilities.Availability.NOT_INSTALLED) }
    var batteryOk by remember { mutableStateOf(false) }
    val serviceRunning by CaptureServiceState.running.collectAsState()
    var startMode by remember { mutableStateOf(StartMode.MEDIA_PROJECTION) }
    var userOverrodeMode by remember { mutableStateOf(false) }
    var showClearRegionDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 主屏一进就触发自动检查更新。autoCheckIfDue 内部 24h 节流，频繁进出主屏不会浪费 API
     // 额度；只有 hasUpdate 时才弹 dialog，已最新 / 失败 静默不打扰。
    // hiltViewModel<UpdateViewModel>() 与 AboutContent 内部那个调用拿到同一实例，dialog 共享 state。
    val updateVm: com.gameocr.app.update.UpdateViewModel = hiltViewModel()
    val topUpdateState by updateVm.state.collectAsState()
    LaunchedEffect(Unit) { updateVm.autoCheckIfDue() }
    // dialog 必须挂在 MainScreen 顶层，否则用户没滚到"关于"卡看不到自动检测的弹窗。
    // 0.3.0 及之前 dialog 只在 AboutContent 内挂载，导致"自动检测确实跑了但用户感觉没反应"。
    UpdateResultDialog(
        state = topUpdateState,
        onDismiss = { updateVm.reset() },
        onOpenRelease = { url ->
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            updateVm.reset()
        }
    )

    // Shizuku 就绪时默认选 Shizuku（用户未手动切换过的前提下）。
    // 进入页面时 shizukuAvail 还在初始 NOT_INSTALLED，等 ON_RESUME 探测完才真实；
    // 这里跟着变化走，确保用户进来直接看到最优选项。
    LaunchedEffect(shizukuAvail) {
        if (!userOverrodeMode) {
            startMode = if (shizukuAvail == ShizukuCapabilities.Availability.READY ||
                shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED
            ) StartMode.SHIZUKU else StartMode.MEDIA_PROJECTION
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    canDrawOverlay = Settings.canDrawOverlays(context)
                    region = viewModel.currentRegion()
                    shizukuAvail = viewModel.shizukuAvailability(context)
                    // 国产 ROM 在用户点"允许"后，PowerManager 白名单状态向 caller
                    // 传播有几百毫秒延迟，ON_RESUME 那一刻常读到旧值。短轮询命中即停。
                    batteryOk = RomHelper.isIgnoringBatteryOptimizations(context)
                    if (!batteryOk) {
                        repeat(5) {
                            delay(200)
                            if (RomHelper.isIgnoringBatteryOptimizations(context)) {
                                batteryOk = true
                                return@launch
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showClearRegionDialog) {
        AlertDialog(
            onDismissRequest = { showClearRegionDialog = false },
            shape = AppleCardShape,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            title = { Text(stringResource(R.string.main_clear_region_dialog_title)) },
            text = { Text(stringResource(R.string.main_clear_region_dialog_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearRegionDialog = false
                    scope.launch {
                        viewModel.clearRegion()
                        region = null
                    }
                }) { Text(stringResource(R.string.main_clear_region_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearRegionDialog = false }) {
                    Text(stringResource(R.string.main_clear_region_dialog_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    TextButton(
                        onClick = onOpenLogs,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Text(" ${stringResource(R.string.main_logs)}", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Text(" ${stringResource(R.string.main_settings)}", modifier = Modifier.padding(start = 4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                    scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            val shizukuUsable = shizukuAvail == ShizukuCapabilities.Availability.READY ||
                shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED
            val grantOverlay: () -> Unit = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
                context.startActivity(intent)
            }
            val startCapture: () -> Unit = {
                when (startMode) {
                    StartMode.MEDIA_PROJECTION ->
                        context.startActivity(MediaProjectionRequestActivity.newIntent(context))
                    StartMode.SHIZUKU -> scope.launch {
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
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.main_snack_shizuku_denied)
                            )
                        }
                    }
                }
            }

            MinimalHero(
                canDrawOverlay = canDrawOverlay,
                serviceRunning = serviceRunning,
                onGrantOverlay = grantOverlay,
                onStart = startCapture,
                onStop = { context.startService(CaptureService.stopIntent(context)) },
                modifier = Modifier.fillMaxWidth()
            )

            MinimalAccordion(
                title = stringResource(R.string.main_advanced_status_title),
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded }
            ) {
                MinimalSection(title = stringResource(R.string.main_label_start_mode)) {
                    MinimalModeToggle(
                        selected = startMode,
                        shizukuEnabled = shizukuUsable,
                        serviceRunning = serviceRunning,
                        onSelect = {
                            startMode = it
                            userOverrodeMode = true
                        }
                    )
                    MinimalMetaText(
                        text = stringResource(
                            when {
                                startMode == StartMode.MEDIA_PROJECTION -> R.string.main_hint_media_projection
                                shizukuAvail == ShizukuCapabilities.Availability.READY -> R.string.main_hint_shizuku_ready
                                shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> R.string.main_hint_shizuku_not_granted
                                shizukuAvail == ShizukuCapabilities.Availability.NOT_RUNNING -> R.string.main_hint_shizuku_not_running
                                else -> R.string.main_hint_shizuku_not_installed
                            }
                        )
                    )
                }
                MinimalSection(title = stringResource(R.string.main_section_region)) {
                    MinimalOutlineButton(
                        text = stringResource(R.string.main_btn_pick_region),
                        onClick = { context.startActivity(RegionPickerActivity.newIntent(context)) }
                    )
                    if (region != null) {
                        MinimalOutlineButton(
                            text = stringResource(R.string.main_btn_clear_region),
                            onClick = { showClearRegionDialog = true }
                        )
                    }
                }
                MinimalSection(title = stringResource(R.string.main_status_title)) {
                    MinimalStatusRows(
                        canDrawOverlay = canDrawOverlay,
                        region = region,
                        shizukuAvail = shizukuAvail,
                        batteryOk = batteryOk,
                        serviceRunning = serviceRunning
                    )
                }
                MinimalSection(title = stringResource(R.string.main_section_rom_guide)) {
                    MinimalOutlineButton(
                        text = stringResource(R.string.main_btn_open_autostart),
                        onClick = {
                            RomHelper.launchFirstAvailable(context, RomHelper.autoStartIntents(context))
                        }
                    )
                    MinimalOutlineButton(
                        text = stringResource(
                            if (batteryOk) R.string.main_btn_battery_already_ok
                            else R.string.main_btn_open_battery_whitelist
                        ),
                        enabled = !batteryOk,
                        onClick = {
                            RomHelper.launchFirstAvailable(context, RomHelper.batteryWhitelistIntents(context))
                        }
                    )
                }
                MinimalSection(title = stringResource(R.string.settings_section_about)) {
                    AboutContent()
                }
            }
            Box(Modifier.size(48.dp))
            return@Column

            CaptureControlCard(
                canDrawOverlay = canDrawOverlay,
                serviceRunning = serviceRunning,
                startMode = startMode,
                shizukuAvail = shizukuAvail,
                shizukuUsable = shizukuUsable,
                onGrantOverlay = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName)
                    )
                    context.startActivity(intent)
                },
                onStop = { context.startService(CaptureService.stopIntent(context)) },
                onStart = {
                    when (startMode) {
                        StartMode.MEDIA_PROJECTION ->
                            context.startActivity(MediaProjectionRequestActivity.newIntent(context))
                        StartMode.SHIZUKU -> scope.launch {
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
                            } else {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.main_snack_shizuku_denied)
                                )
                            }
                        }
                    }
                },
                onSelectMode = {
                    startMode = it
                    userOverrodeMode = true
                }
            )

            StatusCard(
                canDrawOverlay = canDrawOverlay,
                region = region,
                shizukuAvail = shizukuAvail,
                batteryOk = batteryOk,
                serviceRunning = serviceRunning
            )

            // 区域
            ActionCard(title = stringResource(R.string.main_section_region)) {
                AppleActionButton(
                    text = stringResource(R.string.main_btn_pick_region),
                    modifier = Modifier.fillMaxWidth(),
                    tone = ButtonTone.Secondary,
                    onClick = { context.startActivity(RegionPickerActivity.newIntent(context)) }
                )
                if (region != null) {
                    // 跟「选择截屏区域」按钮同样的 OutlinedButton 样式，视觉对等；
                    // 清除是破坏性操作，弹二次确认避免误触。
                    AppleActionButton(
                        text = stringResource(R.string.main_btn_clear_region),
                        modifier = Modifier.fillMaxWidth(),
                        tone = ButtonTone.Secondary,
                        onClick = { showClearRegionDialog = true }
                    )
                }
            }

            // 系统兼容：自启动 + 电池白名单是两件事，拆成两个按钮分别引导。
            // 电池白名单可通过 PowerManager 检测当前状态，已加入时按钮显示已开启并禁用，
            // 让用户清楚下一步该点哪个；自启动没有公开 API 可探测，按钮始终可点。
            ActionCard(title = stringResource(R.string.main_section_rom_guide)) {
                AppleActionButton(
                    text = stringResource(R.string.main_btn_open_autostart),
                    modifier = Modifier.fillMaxWidth(),
                    tone = ButtonTone.Secondary,
                    onClick = {
                        RomHelper.launchFirstAvailable(context, RomHelper.autoStartIntents(context))
                    }
                )
                AppleActionButton(
                    text = stringResource(
                        if (batteryOk) R.string.main_btn_battery_already_ok
                        else R.string.main_btn_open_battery_whitelist
                    ),
                    enabled = !batteryOk,
                    modifier = Modifier.fillMaxWidth(),
                    tone = ButtonTone.Secondary,
                    onClick = {
                        RomHelper.launchFirstAvailable(context, RomHelper.batteryWhitelistIntents(context))
                    }
                )
            }

            // 关于：放在主屏底部，方便用户一眼看到版本号 / GitHub
            ActionCard(title = stringResource(R.string.settings_section_about)) {
                AboutContent()
            }

            // 底部留空
            Box(Modifier.size(24.dp))
        }
    }
}

private const val GITHUB_URL = "https://github.com/ciddwd/overlay-translator"
private val MinimalPillShape = RoundedCornerShape(999.dp)
private val MinimalLineColor: Color
    @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = if (isDarkThemeSurface()) 0.46f else 0.36f)

@Composable
private fun MinimalHero(
    canDrawOverlay: Boolean,
    serviceRunning: Boolean,
    onGrantOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(top = 28.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MinimalMetaText(
                text = stringResource(if (serviceRunning) R.string.main_status_running else R.string.main_status_idle)
            )
            Text(
                text = stringResource(
                    if (serviceRunning) R.string.main_hero_running_title
                    else R.string.main_hero_ready_title
                ),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(
                    if (serviceRunning) R.string.main_hero_running_subtitle
                    else R.string.main_hero_ready_subtitle
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MinimalOutlineButton(
            text = stringResource(
                when {
                    serviceRunning -> R.string.main_action_stop
                    !canDrawOverlay -> R.string.main_action_grant_overlay_first
                    else -> R.string.main_action_start_overlay
                }
            ),
            emphasized = true,
            onClick = when {
                serviceRunning -> onStop
                !canDrawOverlay -> onGrantOverlay
                else -> onStart
            }
        )
    }
}

@Composable
private fun MinimalAccordion(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MinimalLineColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (expanded) "-" else "+",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                content = content
            )
        }
        HorizontalDivider(color = MinimalLineColor)
    }
}

@Composable
private fun MinimalSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun MinimalOutlineButton(
    text: String,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(220),
        label = "minimal-button-scale"
    )
    val foreground = MaterialTheme.colorScheme.onBackground
    val line = if (emphasized) foreground else MinimalLineColor
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (emphasized) 58.dp else 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(MinimalPillShape)
            .border(1.dp, line, MinimalPillShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = foreground
        )
    }
}

@Composable
private fun MinimalModeToggle(
    selected: StartMode,
    shizukuEnabled: Boolean,
    serviceRunning: Boolean,
    onSelect: (StartMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        MinimalToggleItem(
            label = stringResource(R.string.main_mode_standard),
            selected = selected == StartMode.MEDIA_PROJECTION,
            enabled = !serviceRunning,
            modifier = Modifier.weight(1f)
        ) { onSelect(StartMode.MEDIA_PROJECTION) }
        MinimalToggleItem(
            label = stringResource(R.string.main_mode_quiet),
            selected = selected == StartMode.SHIZUKU,
            enabled = !serviceRunning && shizukuEnabled,
            modifier = Modifier.weight(1f)
        ) { onSelect(StartMode.SHIZUKU) }
    }
}

@Composable
private fun MinimalToggleItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(220), label = "minimal-toggle-scale")
    val foreground = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
    val line = if (selected) MaterialTheme.colorScheme.onBackground else MinimalLineColor
    Box(
        modifier = modifier
            .height(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.34f
            }
            .clip(MinimalPillShape)
            .border(1.dp, line, MinimalPillShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MinimalStatusRows(
    canDrawOverlay: Boolean,
    region: CaptureRegion?,
    shizukuAvail: ShizukuCapabilities.Availability,
    batteryOk: Boolean,
    serviceRunning: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        MinimalStatusRow(
            stringResource(R.string.main_status_capture_service),
            stringResource(if (serviceRunning) R.string.main_status_running else R.string.main_status_idle)
        )
        MinimalStatusRow(
            stringResource(R.string.main_status_overlay_perm),
            stringResource(if (canDrawOverlay) R.string.main_status_enabled else R.string.main_status_disabled)
        )
        MinimalStatusRow(
            stringResource(R.string.main_status_region),
            region?.let {
                stringResource(R.string.main_status_region_format, it.width, it.height, it.left, it.top)
            } ?: stringResource(R.string.main_status_region_full)
        )
        MinimalStatusRow(
            stringResource(R.string.main_status_shizuku),
            stringResource(
                when (shizukuAvail) {
                    ShizukuCapabilities.Availability.READY -> R.string.main_status_shizuku_ready
                    ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> R.string.main_status_shizuku_not_granted
                    ShizukuCapabilities.Availability.NOT_RUNNING -> R.string.main_status_shizuku_not_running
                    ShizukuCapabilities.Availability.NOT_INSTALLED -> R.string.main_status_shizuku_not_installed
                }
            )
        )
        MinimalStatusRow(
            stringResource(R.string.main_status_battery_whitelist),
            stringResource(if (batteryOk) R.string.main_status_enabled else R.string.main_status_disabled)
        )
    }
}

@Composable
private fun MinimalStatusRow(label: String, value: String) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = MinimalLineColor)
    }
}

@Composable
private fun MinimalMetaText(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

private val AppleCardShape = RoundedCornerShape(24.dp)
private val ApplePanelShape = RoundedCornerShape(28.dp)
private val ApplePillShape = RoundedCornerShape(999.dp)

@Composable
private fun isDarkThemeSurface(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
private fun Modifier.appleGlass(
    shape: RoundedCornerShape = AppleCardShape,
    elevation: Dp = 22.dp
): Modifier {
    val dark = isDarkThemeSurface()
    val glassColor = if (dark) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    } else {
        Color.White.copy(alpha = 0.78f)
    }
    val highlight = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.86f)
    return this
        .shadow(elevation, shape, clip = false)
        .shadow(3.dp, shape, clip = false)
        .clip(shape)
        .background(glassColor, shape)
        .border(1.dp, highlight, shape)
}

@Composable
private fun AppleGlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = AppleCardShape,
    elevation: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.appleGlass(shape = shape, elevation = elevation).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun AppleActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ButtonTone = ButtonTone.Primary,
    icon: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "button-scale"
    )
    val offset by animateDpAsState(
        targetValue = if (pressed) 1.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "button-offset"
    )
    val dark = isDarkThemeSurface()
    val targetColor = when (tone) {
        ButtonTone.Primary -> if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ButtonTone.Danger -> MaterialTheme.colorScheme.errorContainer
        ButtonTone.Secondary -> if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.62f)
    }
    val targetContent = when (tone) {
        ButtonTone.Primary -> if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ButtonTone.Danger -> MaterialTheme.colorScheme.onErrorContainer
        ButtonTone.Secondary -> MaterialTheme.colorScheme.onSurface
    }
    val container by animateColorAsState(targetColor, tween(300), label = "button-color")
    val contentColor by animateColorAsState(targetContent, tween(300), label = "button-content")
    val outline = if (tone == ButtonTone.Secondary) {
        if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.74f)
    } else {
        Color.White.copy(alpha = 0.18f)
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
    Row(
        modifier = modifier
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = offset.toPx()
                alpha = if (enabled) 1f else 0.56f
            }
            .shadow(
                elevation = if (tone == ButtonTone.Secondary) 8.dp else 16.dp,
                shape = ApplePillShape,
                clip = false
            )
            .clip(ApplePillShape)
            .background(container, ApplePillShape)
            .border(1.dp, outline, ApplePillShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Box(Modifier.size(8.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    }
}

private enum class ButtonTone { Primary, Secondary, Danger }

@Composable
private fun AppleModeToggle(
    selected: StartMode,
    shizukuEnabled: Boolean,
    serviceRunning: Boolean,
    onSelect: (StartMode) -> Unit
) {
    val dark = isDarkThemeSurface()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ApplePillShape)
            .background(if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFFE9EEF5).copy(alpha = 0.72f), ApplePillShape)
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.70f), ApplePillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeToggleItem(
            label = stringResource(R.string.main_mode_standard),
            selected = selected == StartMode.MEDIA_PROJECTION,
            enabled = !serviceRunning,
            modifier = Modifier.weight(1f)
        ) { onSelect(StartMode.MEDIA_PROJECTION) }
        ModeToggleItem(
            label = stringResource(R.string.main_mode_quiet),
            selected = selected == StartMode.SHIZUKU,
            enabled = !serviceRunning && shizukuEnabled,
            modifier = Modifier.weight(1f)
        ) { onSelect(StartMode.SHIZUKU) }
    }
}

@Composable
private fun ModeToggleItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(300), label = "mode-scale")
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surface.copy(alpha = if (isDarkThemeSurface()) 0.16f else 0.92f)
        else Color.Transparent,
        tween(300),
        label = "mode-color"
    )
    val textColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(300),
        label = "mode-text"
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.42f
            }
            .shadow(
                elevation = if (selected) 8.dp else 0.dp,
                shape = ApplePillShape,
                clip = false
            )
            .clip(ApplePillShape)
            .background(color, ApplePillShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CaptureControlCard(
    canDrawOverlay: Boolean,
    serviceRunning: Boolean,
    startMode: StartMode,
    shizukuAvail: ShizukuCapabilities.Availability,
    shizukuUsable: Boolean,
    onGrantOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectMode: (StartMode) -> Unit
) {
    val hintRes = when {
        startMode == StartMode.MEDIA_PROJECTION -> R.string.main_hint_media_projection
        shizukuAvail == ShizukuCapabilities.Availability.READY -> R.string.main_hint_shizuku_ready
        shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> R.string.main_hint_shizuku_not_granted
        shizukuAvail == ShizukuCapabilities.Availability.NOT_RUNNING -> R.string.main_hint_shizuku_not_running
        else -> R.string.main_hint_shizuku_not_installed
    }
    val titleRes = if (serviceRunning) R.string.main_hero_running_title else R.string.main_hero_ready_title
    val subtitleRes = if (serviceRunning) R.string.main_hero_running_subtitle else R.string.main_hero_ready_subtitle

    AppleGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = ApplePanelShape,
        elevation = 28.dp
    ) {
            StatusBadge(
                label = stringResource(
                    if (serviceRunning) R.string.main_status_running else R.string.main_status_idle
                ),
                active = serviceRunning
            )
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (serviceRunning) {
                AppleActionButton(
                    text = stringResource(R.string.main_action_stop),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    tone = ButtonTone.Danger,
                    icon = { Icon(Icons.Default.Stop, contentDescription = null) },
                    onClick = onStop
                )
            } else if (!canDrawOverlay) {
                AppleActionButton(
                    text = stringResource(R.string.main_action_grant_overlay_first),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onClick = onGrantOverlay
                )
            } else {
                AppleActionButton(
                    text = stringResource(R.string.main_action_start_overlay),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    onClick = onStart
                )
            }

            if (canDrawOverlay) {
                Text(
                    stringResource(R.string.main_label_start_mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppleModeToggle(
                    selected = startMode,
                    shizukuEnabled = shizukuUsable,
                    serviceRunning = serviceRunning,
                    onSelect = onSelectMode
                )
                Text(
                    stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
    }
}

@Composable
private fun StatusBadge(label: String, active: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
            )
            .border(
                1.dp,
                if (isDarkThemeSurface()) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.66f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(7.dp)
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    val updateVm: com.gameocr.app.update.UpdateViewModel = hiltViewModel()
    val updateState by updateVm.state.collectAsState()

    Text(
        text = stringResource(R.string.settings_about_tagline),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(R.string.settings_about_version_format, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(R.string.settings_about_github_label),
        style = MaterialTheme.typography.labelLarge
    )
    Text(
        text = GITHUB_URL,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
    MinimalOutlineButton(
        text = stringResource(R.string.settings_about_open_github),
        onClick = {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    )

    // 检查更新：调 GitHub Releases API，失败让用户手动打开 Release 页（国内访问 api.github.com 偶尔抽风）
    MinimalOutlineButton(
        text = stringResource(
            if (updateState is com.gameocr.app.update.UpdateViewModel.State.Checking)
                R.string.update_btn_checking
            else R.string.update_btn_check
        ),
        enabled = updateState !is com.gameocr.app.update.UpdateViewModel.State.Checking,
        onClick = { updateVm.check() }
    )

    // 注意：UpdateResultDialog 已提到 MainScreen 顶层（避免自动检测弹窗被关于卡折叠遮住），
    // 这里不再重复挂——同一 ViewModel state，顶层 dialog 也响应"检查更新"按钮触发的 check()。
}

@Composable
private fun UpdateResultDialog(
    state: com.gameocr.app.update.UpdateViewModel.State,
    onDismiss: () -> Unit,
    onOpenRelease: (String) -> Unit
) {
    when (state) {
        is com.gameocr.app.update.UpdateViewModel.State.Loaded -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = onDismiss,
                shape = AppleCardShape,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                title = {
                    Text(stringResource(
                        if (info.hasUpdate) R.string.update_dialog_title_new
                        else R.string.update_dialog_title_uptodate
                    ))
                },
                text = {
                    Column {
                        Text(stringResource(
                            R.string.update_dialog_versions_format,
                            info.currentVersion, info.latestVersion
                        ))
                        if (info.hasUpdate && !info.changelog.isNullOrBlank()) {
                            Text(
                                text = info.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    if (info.hasUpdate) {
                        TextButton(onClick = {
                            // 有 APK 直链就走直链让浏览器 / 系统下载器直接下；否则跳 release 页
                            onOpenRelease(info.apkUrl ?: info.releaseUrl)
                        }) { Text(stringResource(R.string.update_dialog_btn_download)) }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_dialog_btn_ok))
                        }
                    }
                },
                dismissButton = if (info.hasUpdate) {
                    {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_dialog_btn_later))
                        }
                    }
                } else null
            )
        }
        is com.gameocr.app.update.UpdateViewModel.State.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                shape = AppleCardShape,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                title = { Text(stringResource(R.string.update_dialog_title_failed)) },
                text = {
                    Text(stringResource(R.string.update_dialog_failed_format, state.errorMessage))
                },
                confirmButton = {
                    TextButton(onClick = {
                        onOpenRelease(com.gameocr.app.update.UpdateChecker.RELEASE_PAGE_URL)
                    }) { Text(stringResource(R.string.update_dialog_btn_open_release)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_dialog_btn_close))
                    }
                }
            )
        }
        else -> Unit
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StatusCard(
    canDrawOverlay: Boolean,
    region: CaptureRegion?,
    shizukuAvail: ShizukuCapabilities.Availability,
    batteryOk: Boolean,
    serviceRunning: Boolean
) {
    AppleGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        elevation = 18.dp
    ) {
            Text(
                stringResource(R.string.main_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    stringResource(R.string.main_status_capture_service),
                    ok = serviceRunning,
                    detail = stringResource(if (serviceRunning) R.string.main_status_running else R.string.main_status_idle)
                )
                StatusChip(stringResource(R.string.main_status_overlay_perm), canDrawOverlay)
                StatusChip(
                    stringResource(R.string.main_status_region),
                    ok = true,
                    detail = region?.let {
                        stringResource(R.string.main_status_region_format, it.width, it.height, it.left, it.top)
                    } ?: stringResource(R.string.main_status_region_full)
                )
                StatusChip(
                    stringResource(R.string.main_status_shizuku),
                    ok = shizukuAvail == ShizukuCapabilities.Availability.READY,
                    detail = stringResource(
                        when (shizukuAvail) {
                            ShizukuCapabilities.Availability.READY -> R.string.main_status_shizuku_ready
                            ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> R.string.main_status_shizuku_not_granted
                            ShizukuCapabilities.Availability.NOT_RUNNING -> R.string.main_status_shizuku_not_running
                            ShizukuCapabilities.Availability.NOT_INSTALLED -> R.string.main_status_shizuku_not_installed
                        }
                    )
                )
                StatusChip(stringResource(R.string.main_status_battery_whitelist), batteryOk)
            }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(18.dp),
                clip = false
            )
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isDarkThemeSurface()) 0.10f else 0.62f))
            .border(
                1.dp,
                if (isDarkThemeSurface()) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.74f),
                RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(8.dp)
        ) {}
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = detail ?: stringResource(if (ok) R.string.main_status_enabled else R.string.main_status_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionCard(title: String, content: @Composable () -> Unit) {
    AppleGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        elevation = 18.dp
    ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
    }
}

/** 用户在主屏选择的截屏服务启动方式。仅 App 进程内记忆，不持久化（用户每次启动后默认 MediaProjection）。 */
private enum class StartMode { MEDIA_PROJECTION, SHIZUKU }

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
