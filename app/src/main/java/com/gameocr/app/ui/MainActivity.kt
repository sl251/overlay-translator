package com.gameocr.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gameocr.app.ui.theme.GameOcrTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameOcrTheme {
                AppRoot()
            }
        }
    }
}

private enum class Route { Main, Settings, Logs }

@Composable
private fun AppRoot() {
    var route by remember { mutableStateOf(Route.Main) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (route) {
            Route.Main -> MainScreen(
                onOpenSettings = { route = Route.Settings },
                onOpenLogs = { route = Route.Logs }
            )
            Route.Settings -> SettingsScreen(onBack = { route = Route.Main })
            Route.Logs -> LogScreen(onBack = { route = Route.Main })
        }
    }
}
