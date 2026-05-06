package com.xtrust.standalone

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xtrust.standalone.ui.ChatScreen
import com.xtrust.standalone.ui.HomeScreen
import com.xtrust.standalone.ui.UiState
import com.xtrust.standalone.ui.SettingsScreen
import com.xtrust.standalone.ui.SidebarItem
import com.xtrust.standalone.ui.XtrustShell
import com.xtrust.standalone.ui.XtrustViewModel
import com.xtrust.standalone.ui.theme.AccentOnPrimary
import com.xtrust.standalone.ui.theme.Sizes
import com.xtrust.standalone.ui.theme.Spacing
import com.xtrust.standalone.ui.theme.SurfaceBackground
import com.xtrust.standalone.ui.theme.XtrustTheme

class MainActivity : ComponentActivity() {

    private val viewModel: XtrustViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            XtrustTheme {
                var selectedKey by rememberSaveable { mutableStateOf(KEY_HOME) }
                val uiState by viewModel.uiState.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
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

                    if (uiState.isLoadingLlmModel || uiState.asrDebugState.isLoadingModel) {
                        ModelLoadingBanner(
                            uiState = uiState,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .navigationBarsPadding()
                                .padding(start = Sizes.sidebarWidth + Spacing.lg, bottom = Spacing.lg)
                        )
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

@Composable
private fun ModelLoadingBanner(
    uiState: UiState,
    modifier: Modifier = Modifier
) {
    val loadingTargets = buildList {
        if (uiState.isLoadingLlmModel) add("LLM")
        if (uiState.asrDebugState.isLoadingModel) add("ASR")
    }
    val title = when (loadingTargets.size) {
        0 -> "モデルを確認中…"
        1 -> "${loadingTargets.first()} を読み込み中…"
        else -> "${loadingTargets.joinToString(" / ")} を読み込み中…"
    }

    Surface(
        modifier = modifier,
        color = Color(0xF21F1F1F),
        shape = MaterialTheme.shapes.large,
        shadowElevation = Spacing.md
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(AccentOnPrimary.copy(alpha = 0.9f))
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentOnPrimary
                )
                Text(
                    text = "完了まで一部の操作は利用できません",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentOnPrimary.copy(alpha = 0.76f)
                )
            }
        }
    }
}
