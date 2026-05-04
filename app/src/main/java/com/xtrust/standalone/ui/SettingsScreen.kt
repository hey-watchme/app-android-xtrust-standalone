package com.xtrust.standalone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import com.xtrust.standalone.ui.theme.AccentOnPrimary
import com.xtrust.standalone.ui.theme.AccentPrimary
import com.xtrust.standalone.ui.theme.DividerSubtle
import com.xtrust.standalone.ui.theme.Radius
import com.xtrust.standalone.ui.theme.Sizes
import com.xtrust.standalone.ui.theme.Spacing
import com.xtrust.standalone.ui.theme.StatusError
import com.xtrust.standalone.ui.theme.StatusReady
import com.xtrust.standalone.ui.theme.SurfaceBackground
import com.xtrust.standalone.ui.theme.SurfaceMuted
import com.xtrust.standalone.ui.theme.SurfaceSubtle
import com.xtrust.standalone.ui.theme.TextPrimary
import com.xtrust.standalone.ui.theme.TextSecondary
import com.xtrust.standalone.ui.theme.TextTertiary

@Composable
fun SettingsScreen(viewModel: XtrustViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
    ) {
        SectionLabel(text = "LLM ENGINE")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "LLM Engine",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        StatusRow(
            ready = uiState.llmReady,
            readyText = "Gemma 4 E2B — ready",
            notReadyText = "Not loaded"
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        SectionLabel(text = "Model path:")
        Spacer(modifier = Modifier.height(Spacing.xs))
        MonoBlock(text = uiState.llmModelPath)

        uiState.lastError?.let { error ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            ErrorBlock(text = "Error: $error")
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        Button(
            onClick = { viewModel.loadLlmModel() },
            enabled = !uiState.isProcessing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = AccentOnPrimary
            )
        ) {
            Text(if (uiState.isProcessing) "Loading…" else "Reload Gemma 4 E2B")
        }

        SectionDivider()

        SectionLabel(text = "ASR ENGINE")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "ASR Engine",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        StatusRow(
            ready = uiState.asrDebugState.isReady,
            readyText = "Sherpa-ONNX SenseVoice — ready",
            notReadyText = "Sherpa-ONNX SenseVoice — not loaded"
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        SectionLabel(text = "Model directory:")
        Spacer(modifier = Modifier.height(Spacing.xs))
        MonoBlock(text = uiState.asrDebugState.modelDirPath)

        if (uiState.asrDebugState.modelAccessSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = uiState.asrDebugState.modelAccessSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        Button(
            onClick = { viewModel.loadAsrModel() },
            enabled = !uiState.asrDebugState.isLoadingModel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = AccentOnPrimary
            )
        ) {
            Text(if (uiState.asrDebugState.isLoadingModel) "Loading…" else "Reload Sherpa-ONNX ASR")
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Place these files in the directory above:\n- model.int8.onnx\n- tokens.txt\n\nThe app auto-detects known SenseVoice folders under `files/asr/` if the files exist there.\nIf files were pushed from `adb shell` into a shell-owned folder, also run:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        MonoBlock(
            text = "adb shell chmod 777 /sdcard/Android/data/com.xtrust.standalone/files/asr\n" +
                "adb shell chmod 777 ${uiState.asrDebugState.modelDirPath}"
        )

        SectionDivider()

        SectionLabel(text = "MODEL FILE LOCATION")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "Model file location",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "Push the model file via adb:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        MonoBlock(
            text = "adb push gemma-4-E2B-it.litertlm \\\n  ${uiState.llmModelPath}"
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Push Sherpa-ONNX SenseVoice files via adb:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        MonoBlock(
            text = "adb push model.int8.onnx \\\n  ${uiState.asrDebugState.modelDirPath}/model.int8.onnx\n\n" +
                "adb push tokens.txt \\\n  ${uiState.asrDebugState.modelDirPath}/tokens.txt"
        )

        SectionDivider()

        SectionLabel(text = "DATA")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "Data",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "All data is stored locally. No network access.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary
    )
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(Spacing.xl))
    HorizontalDivider(color = DividerSubtle, thickness = Sizes.hairline)
    Spacer(modifier = Modifier.height(Spacing.xl))
}

@Composable
private fun StatusRow(ready: Boolean, readyText: String, notReadyText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.sm)
                .clip(CircleShape)
                .background(if (ready) StatusReady else TextTertiary)
        )
        Text(
            text = if (ready) readyText else notReadyText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ready) TextPrimary else TextSecondary
        )
    }
}

@Composable
private fun MonoBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(SurfaceMuted)
            .padding(Spacing.sm)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ErrorBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(SurfaceSubtle)
            .padding(Spacing.sm)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = StatusError
        )
    }
}
