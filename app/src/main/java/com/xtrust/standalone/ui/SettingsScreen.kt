package com.xtrust.standalone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
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
import com.xtrust.standalone.ui.theme.DividerStrong
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
    val selectedLlm = uiState.availableLlmOptions.firstOrNull { it.id == uiState.selectedLlmId }
    val loadedLlm = uiState.availableLlmOptions.firstOrNull { it.id == uiState.loadedLlmId }
    val selectedLlmFileName = selectedLlm?.modelPath?.substringAfterLast('/') ?: "model.litertlm"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
    ) {
        SectionLabel(text = "ローカル LLM")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "チャット用モデル",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        StatusRow(
            ready = uiState.llmReady,
            readyText = "${loadedLlm?.displayName ?: "ローカル LLM"} をロード中",
            notReadyText = "未ロード"
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "比較モデル",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        uiState.availableLlmOptions.forEach { option ->
            LlmOptionCard(
                option = option,
                isSelected = option.id == uiState.selectedLlmId,
                isLoaded = option.id == uiState.loadedLlmId,
                onSelect = { viewModel.selectLlmModel(option.id) }
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        Text(
            text = "4GB 端末を前提に、モデル切替時は先にロード済みモデルを解放します。会話履歴も切り替え時にリセットされます。",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        SectionLabel(text = "選択中のモデルパス")
        Spacer(modifier = Modifier.height(Spacing.xs))
        MonoBlock(text = selectedLlm?.modelPath ?: uiState.llmModelPath)

        selectedLlm?.implementationNote?.let { note ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        uiState.lastError?.let { error ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            ErrorBlock(text = error)
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Button(
                onClick = { viewModel.loadLlmModel() },
                enabled = !uiState.isProcessing && (selectedLlm?.isLoadable == true),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = AccentOnPrimary
                )
            ) {
                Text(
                    when {
                        uiState.isProcessing -> "読み込み中…"
                        uiState.llmReady && loadedLlm?.id == selectedLlm?.id -> "読み込み直す"
                        else -> "選択中モデルを読み込む"
                    }
                )
            }

            OutlinedButton(
                onClick = { viewModel.releaseLlmModel() },
                enabled = uiState.llmReady && !uiState.isProcessing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Radius.md),
                border = BorderStroke(Sizes.hairline, DividerStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("モデルを解放")
            }
        }

        if (loadedLlm != null && selectedLlm != null && loadedLlm.id != selectedLlm.id) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "現在ロード中: ${loadedLlm.displayName} / 切替先: ${selectedLlm.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        if (uiState.llmRuntimeInfo.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.md))
            SectionLabel(text = "ランタイム情報")
            Spacer(modifier = Modifier.height(Spacing.xs))
            MonoBlock(text = uiState.llmRuntimeInfo.trim())
        }

        if (uiState.llmReady) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedButton(
                    onClick = { viewModel.runLlmBenchmark() },
                    enabled = !uiState.isProcessing && !uiState.isRunningBenchmark,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.md),
                    border = BorderStroke(Sizes.hairline, DividerStrong),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text(if (uiState.isRunningBenchmark) "速度計測中…" else "速度ベンチを実行")
                }
            }
        }

        uiState.llmBenchmarkResult?.let { benchmark ->
            Spacer(modifier = Modifier.height(Spacing.md))
            SectionLabel(text = "速度結果")
            Spacer(modifier = Modifier.height(Spacing.xs))
            MonoBlock(text = benchmark.trim())
        }

        SectionDivider()

        SectionLabel(text = "ローカル ASR")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "文字起こしモデル",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        StatusRow(
            ready = uiState.asrDebugState.isReady,
            readyText = "Sherpa-ONNX SenseVoice をロード中",
            notReadyText = "Sherpa-ONNX SenseVoice は未ロード"
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        SectionLabel(text = "モデルディレクトリ")
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
            Text(if (uiState.asrDebugState.isLoadingModel) "読み込み中…" else "Sherpa-ONNX ASR を読み込む")
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "上のディレクトリに次の 2 ファイルを配置してください。\n- model.int8.onnx\n- tokens.txt\n\n`files/asr/` 配下の既知 SenseVoice フォルダは自動検出します。`adb shell` で shell 所有フォルダに転送した場合は、次も実行してください。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        MonoBlock(
            text = "adb shell chmod 777 /sdcard/Android/data/com.xtrust.standalone/files/asr\n" +
                "adb shell chmod 777 ${uiState.asrDebugState.modelDirPath}"
        )

        SectionDivider()

        SectionLabel(text = "モデル配置")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "ADB 配置コマンド",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "LLM モデルを配置する場合:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        MonoBlock(
            text = "adb push $selectedLlmFileName \\\n  ${selectedLlm?.modelPath ?: uiState.llmModelPath}"
        )

        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Sherpa-ONNX SenseVoice を配置する場合:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        MonoBlock(
            text = "adb push model.int8.onnx \\\n  ${uiState.asrDebugState.modelDirPath}/model.int8.onnx\n\n" +
                "adb push tokens.txt \\\n  ${uiState.asrDebugState.modelDirPath}/tokens.txt"
        )

        SectionDivider()

        SectionLabel(text = "データ")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "保存方針",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "音声、文字起こし、要約結果は端末内にのみ保存します。クラウド送信は行いません。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun LlmOptionCard(
    option: LlmModelOption,
    isSelected: Boolean,
    isLoaded: Boolean,
    onSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (isSelected) SurfaceMuted else SurfaceSubtle)
            .clickable(onClick = onSelect)
            .padding(Spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "${option.providerLabel} / ${option.runtimeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            StatusRow(
                ready = isLoaded,
                readyText = "使用中",
                notReadyText = if (option.isLoadable) "待機中" else "準備中"
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = option.description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        MonoBlock(text = option.modelPath)

        if (!option.isLoadable && option.implementationNote != null) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = option.implementationNote,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
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
