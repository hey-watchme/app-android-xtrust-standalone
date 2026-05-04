package com.xtrust.standalone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xtrust.standalone.ui.ChatScreen
import com.xtrust.standalone.ui.HomeScreen
import com.xtrust.standalone.ui.SettingsScreen
import com.xtrust.standalone.ui.XtrustViewModel
import com.xtrust.standalone.ui.theme.XtrustTheme

class MainActivity : ComponentActivity() {

    private val viewModel: XtrustViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XtrustTheme {
                var selectedDestination by rememberSaveable { mutableStateOf(AppDestination.Home) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        NavigationRail {
                            AppDestination.primaryItems.forEach { destination ->
                                NavigationRailItem(
                                    selected = selectedDestination == destination,
                                    onClick = { selectedDestination = destination },
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = destination.label
                                        )
                                    },
                                    label = { Text(destination.label) }
                                )
                            }

                            Box(modifier = Modifier.weight(1f))

                            NavigationRailItem(
                                selected = selectedDestination == AppDestination.Settings,
                                onClick = { selectedDestination = AppDestination.Settings },
                                icon = {
                                    Icon(
                                        imageVector = AppDestination.Settings.icon,
                                        contentDescription = AppDestination.Settings.label
                                    )
                                },
                                label = { Text(AppDestination.Settings.label) }
                            )
                        }

                        VerticalDivider(thickness = 1.dp)

                        Box(modifier = Modifier.fillMaxSize()) {
                            when (selectedDestination) {
                                AppDestination.Home -> HomeScreen(viewModel, Modifier.fillMaxSize())
                                AppDestination.Chat -> ChatScreen(viewModel, Modifier.fillMaxSize())
                                AppDestination.Settings -> SettingsScreen(viewModel, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: ImageVector
) {
    Home("ホーム", Icons.Default.Home),
    Chat("チャット", Icons.AutoMirrored.Filled.Chat),
    Settings("設定", Icons.Default.Settings);

    companion object {
        val primaryItems = listOf(Home, Chat)
    }
}
