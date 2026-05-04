package com.xtrust.standalone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xtrust.standalone.ui.ChatScreen
import com.xtrust.standalone.ui.HomeScreen
import com.xtrust.standalone.ui.SettingsScreen
import com.xtrust.standalone.ui.SidebarItem
import com.xtrust.standalone.ui.XtrustShell
import com.xtrust.standalone.ui.XtrustViewModel
import com.xtrust.standalone.ui.theme.SurfaceBackground
import com.xtrust.standalone.ui.theme.XtrustTheme

class MainActivity : ComponentActivity() {

    private val viewModel: XtrustViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XtrustTheme {
                var selectedKey by rememberSaveable { mutableStateOf(KEY_HOME) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(SurfaceBackground)
                    ) {
                        XtrustShell(
                            primary = primaryItems,
                            secondary = secondaryItems,
                            selectedKey = selectedKey,
                            onSelect = { selectedKey = it }
                        ) {
                            when (selectedKey) {
                                KEY_HOME -> HomeScreen(viewModel, Modifier.fillMaxSize())
                                KEY_CHAT -> ChatScreen(viewModel, Modifier.fillMaxSize())
                                KEY_SETTINGS -> SettingsScreen(viewModel, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val KEY_HOME = "home"
        private const val KEY_CHAT = "chat"
        private const val KEY_SETTINGS = "settings"

        private val primaryItems = listOf(
            SidebarItem(KEY_HOME, "AI議事録", Icons.Outlined.GraphicEq),
            SidebarItem(KEY_CHAT, "チャット", Icons.Outlined.ChatBubbleOutline)
        )

        private val secondaryItems = listOf(
            SidebarItem(KEY_SETTINGS, "設定", Icons.Outlined.Settings)
        )
    }
}
