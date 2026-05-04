package com.xtrust.standalone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xtrust.standalone.ui.theme.AccentOnPrimary
import com.xtrust.standalone.ui.theme.AccentPrimary
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
fun ChatScreen(viewModel: XtrustViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.chatMessages.collectAsState()
    val selectedLlm = uiState.availableLlmOptions.firstOrNull { it.id == uiState.selectedLlmId }
    val loadedLlm = uiState.availableLlmOptions.firstOrNull { it.id == uiState.loadedLlmId }
    val assistantLabel = loadedLlm?.assistantLabel ?: selectedLlm?.assistantLabel ?: "AI"
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(messages.size, uiState.isProcessing) {
        val itemCount = messages.size + if (uiState.isProcessing) 1 else 0
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .padding(Spacing.xl)
    ) {
        Text(
            text = "ローカルチャット",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "端末内 LLM の対話品質を確認する画面です。録音と文字起こしの検証はホームで行います。",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
        Spacer(modifier = Modifier.height(Spacing.lg))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = Spacing.lg)
        ) {
            item {
                ChatStatusCard(
                    uiState = uiState,
                    selectedLlm = selectedLlm,
                    loadedLlm = loadedLlm,
                    onResetChat = viewModel::resetChat
                )
            }

            item {
                Spacer(modifier = Modifier.height(Spacing.md))
            }

            if (messages.isEmpty()) {
                item {
                    EmptyChatState(
                        llmReady = uiState.llmReady,
                        selectedLlm = selectedLlm
                    )
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        assistantLabel = assistantLabel
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                }
            }

            if (uiState.isProcessing) {
                item {
                    ThinkingBubble(assistantLabel = assistantLabel)
                }
            }
        }

        uiState.lastError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = StatusError,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.llmReady && !uiState.isProcessing,
            minLines = 3,
            maxLines = 6,
            shape = RoundedCornerShape(Radius.md),
            label = { Text("メッセージ") },
            placeholder = { Text("日本語で質問や指示を入力") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = DividerStrong,
                focusedLabelColor = TextSecondary,
                unfocusedLabelColor = TextTertiary,
                cursorColor = AccentPrimary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedButton(
                onClick = { draft = "" },
                enabled = draft.isNotEmpty() && !uiState.isProcessing,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Radius.md),
                border = BorderStroke(Sizes.hairline, DividerStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("入力を消去")
            }

            Button(
                onClick = {
                    viewModel.sendChatMessage(draft)
                    draft = ""
                },
                enabled = uiState.llmReady && !uiState.isProcessing && draft.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = AccentOnPrimary
                )
            ) {
                Text("送信")
            }
        }
    }
}

@Composable
private fun ChatStatusCard(
    uiState: UiState,
    selectedLlm: LlmModelOption?,
    loadedLlm: LlmModelOption?,
    onResetChat: () -> Unit
) {
    val memory = uiState.memorySnapshot
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceMuted)
            .padding(Spacing.lg)
    ) {
        Text(
            text = if (uiState.llmReady) {
                "${loadedLlm?.displayName ?: "ローカル LLM"} を使用中"
            } else {
                "${selectedLlm?.displayName ?: "ローカル LLM"} は未ロードです"
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (uiState.llmReady) StatusReady else StatusError
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "端末メモリ ${memory.deviceUsedMb} / ${memory.deviceTotalMb} MB 使用中",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "アプリヒープ ${memory.appHeapUsedMb} / ${memory.appHeapMaxMb} MB",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "ネイティブヒープ ${memory.nativeHeapMb} MB  空き ${memory.deviceAvailableMb} MB",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        if (selectedLlm != null && loadedLlm != null && selectedLlm.id != loadedLlm.id) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "切替先として ${selectedLlm.displayName} が選択されています。切替は設定画面で実行します。",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
        if (memory.lowMemory) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "システムから低メモリ警告を受けています",
                style = MaterialTheme.typography.labelSmall,
                color = StatusError
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onResetChat,
                enabled = uiState.llmReady && !uiState.isProcessing,
                shape = RoundedCornerShape(Radius.md),
                border = BorderStroke(Sizes.hairline, DividerStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("会話をリセット")
            }
        }
    }
}

@Composable
private fun EmptyChatState(llmReady: Boolean, selectedLlm: LlmModelOption?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceMuted)
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (llmReady) "会話を始めてください" else "モデルを読み込んでください",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = if (llmReady) {
                "複数ターンの対話品質を、端末上だけで確認できます。"
            } else if (selectedLlm?.isLoadable == false) {
                "${selectedLlm.displayName} はまだ未接続です。設定画面で実装済みモデルを選んでください。"
            } else {
                "自動ロードされていない場合は、設定画面からモデルを読み込んでください。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, assistantLabel: String) {
    val isUser = message.role == ChatRole.User
    val bubbleColor = if (isUser) AccentPrimary else SurfaceSubtle
    val textColor = if (isUser) AccentOnPrimary else TextPrimary
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val label = if (isUser) "あなた" else assistantLabel
    val bubbleShape = if (isUser) {
        RoundedCornerShape(
            topStart = Radius.lg,
            topEnd = Radius.lg,
            bottomStart = Radius.lg,
            bottomEnd = Radius.sm
        )
    } else {
        RoundedCornerShape(
            topStart = Radius.lg,
            topEnd = Radius.lg,
            bottomStart = Radius.sm,
            bottomEnd = Radius.lg
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )
        Box(
            modifier = Modifier
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
    }
}

@Composable
private fun ThinkingBubble(assistantLabel: String) {
    val bubbleShape = RoundedCornerShape(
        topStart = Radius.lg,
        topEnd = Radius.lg,
        bottomStart = Radius.sm,
        bottomEnd = Radius.lg
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = assistantLabel,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )
        Row(
            modifier = Modifier
                .clip(bubbleShape)
                .background(SurfaceSubtle)
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "応答を生成しています…",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}
