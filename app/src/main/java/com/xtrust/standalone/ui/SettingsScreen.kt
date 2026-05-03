package com.xtrust.standalone.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(viewModel: XtrustViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "LLM Engine", style = MaterialTheme.typography.titleMedium)

        Text(
            text = if (uiState.llmReady) "Gemma 4 E2B — ready" else "Not loaded",
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.llmReady)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Text(
            text = "Model path:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = uiState.llmModelPath,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        uiState.lastError?.let { error ->
            Text(
                text = "Error: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Button(
            onClick = { viewModel.loadLlmModel() },
            enabled = !uiState.isProcessing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isProcessing) "Loading…" else "Reload Gemma 4 E2B")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text(text = "ASR Engine", style = MaterialTheme.typography.titleMedium)

        Text(
            text = if (uiState.asrDebugState.isReady) "Sherpa-ONNX SenseVoice — ready" else "Sherpa-ONNX SenseVoice — not loaded",
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.asrDebugState.isReady)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Text(
            text = "Model directory:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = uiState.asrDebugState.modelDirPath,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        Button(
            onClick = { viewModel.loadAsrModel() },
            enabled = !uiState.asrDebugState.isLoadingModel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.asrDebugState.isLoadingModel) "Loading…" else "Reload Sherpa-ONNX ASR")
        }

        Text(
            text = "Place these files in the directory above:\n- model.int8.onnx\n- tokens.txt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text(text = "Model file location", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Push the model file via adb:\n\nadb push gemma-4-E2B-it.litertlm \\\n  ${uiState.llmModelPath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        Text(
            text = "\nPush Sherpa-ONNX SenseVoice files via adb:\n\nadb push model.int8.onnx \\\n  ${uiState.asrDebugState.modelDirPath}/model.int8.onnx\n\nadb push tokens.txt \\\n  ${uiState.asrDebugState.modelDirPath}/tokens.txt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text(text = "Data", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "All data is stored locally. No network access.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
