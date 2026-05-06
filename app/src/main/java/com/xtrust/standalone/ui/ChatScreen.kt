package com.xtrust.standalone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
    val selectedThread = uiState.chatThreads.firstOrNull { it.isSelected }
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val submitMessage = {
        val message = draft.trim()
        if (uiState.llmReady && !uiState.isProcessing && message.isNotEmpty()) {
            viewModel.sendChatMessage(message)
            draft = ""
        }
    }

    LaunchedEffect(messages.size, uiState.isGeneratingChatResponse) {
        val itemCount = messages.size + if (uiState.isGeneratingChatResponse) 1 else 0
        if (itemCount > 0) {
            listState.scrollToItem(itemCount - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg)
    ) {
        ChatHeader(
            uiState = uiState,
            selectedLlm = selectedLlm,
            loadedLlm = loadedLlm
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
        Spacer(modifier = Modifier.height(Spacing.md))

        Row(
            modifier = Modifier
                .fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            ChatThreadSidebar(
                threads = uiState.chatThreads,
                isProcessing = uiState.isProcessing,
                onCreateThread = viewModel::createChatThread,
                onSelectThread = viewModel::selectChatThread
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(Sizes.hairline)
                    .background(DividerStrong)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ThreadHeader(selectedThread = selectedThread)
                    Spacer(modifier = Modifier.height(Spacing.md))
                    HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
                    Spacer(modifier = Modifier.height(Spacing.md))

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = Spacing.sm)
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                EmptyChatState(
                                    llmReady = uiState.llmReady,
                                    selectedLlm = selectedLlm,
                                    isLoadingLlmModel = uiState.isLoadingLlmModel
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

                        if (uiState.isGeneratingChatResponse) {
                            item {
                                ThinkingBubble(
                                    assistantLabel = assistantLabel,
                                    startedAt = uiState.chatResponseStartedAt
                                )
                            }
                        }
                    }

                    ChatComposer(
                        draft = draft,
                        onDraftChange = { draft = it },
                        onSubmit = submitMessage,
                        enabled = uiState.llmReady && !uiState.isProcessing,
                        canSend = uiState.llmReady && !uiState.isProcessing && draft.isNotBlank()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    canSend: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceBackground)
            .imePadding()
            .padding(top = Spacing.sm),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            minLines = 1,
            maxLines = 4,
            shape = RoundedCornerShape(Radius.md),
            placeholder = { Text("メッセージを入力") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = DividerStrong,
                cursorColor = AccentPrimary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedPlaceholderColor = TextTertiary,
                unfocusedPlaceholderColor = TextTertiary
            )
        )

        Button(
            onClick = onSubmit,
            enabled = canSend,
            modifier = Modifier.height(56.dp),
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

@Composable
private fun ChatThreadSidebar(
    threads: List<ChatThreadItem>,
    isProcessing: Boolean,
    onCreateThread: () -> Unit,
    onSelectThread: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .width(248.dp)
            .fillMaxHeight()
    ) {
        Text(
            text = "トピックス",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Button(
            onClick = onCreateThread,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = AccentOnPrimary
            )
        ) {
            Text("新しいチャット")
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(threads, key = { it.id }) { thread ->
                ChatThreadRow(
                    thread = thread,
                    enabled = !isProcessing,
                    onClick = { onSelectThread(thread.id) }
                )
            }
        }
    }
}

@Composable
private fun ChatThreadRow(
    thread: ChatThreadItem,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (thread.isSelected) SurfaceMuted else SurfaceSubtle)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Spacing.md)
    ) {
        Text(
            text = thread.title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = thread.updatedAtLabel,
            style = MaterialTheme.typography.bodySmall,
            color = if (thread.isSelected) TextSecondary else TextTertiary
        )
    }
}

@Composable
private fun ChatHeader(
    uiState: UiState,
    selectedLlm: LlmModelOption?,
    loadedLlm: LlmModelOption?
) {
    val memory = uiState.memorySnapshot
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "チャット",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = if (uiState.llmReady) {
                "${loadedLlm?.displayName ?: "ローカル LLM"} を使用中"
            } else if (uiState.isLoadingLlmModel) {
                "${selectedLlm?.displayName ?: "ローカル LLM"} を読み込み中…"
            } else {
                "${selectedLlm?.displayName ?: "ローカル LLM"} は未ロードです"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                uiState.llmReady -> StatusReady
                uiState.isLoadingLlmModel -> TextSecondary
                else -> StatusError
            }
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "端末 ${memory.deviceUsedMb}/${memory.deviceTotalMb}MB  ・  空き ${memory.deviceAvailableMb}MB  ・  アプリ ${memory.appHeapUsedMb}/${memory.appHeapMaxMb}MB  ・  ネイティブ ${memory.nativeHeapMb}MB",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        if (memory.lowMemory) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "システムから低メモリ警告を受けています",
                style = MaterialTheme.typography.bodySmall,
                color = StatusError
            )
        }
        uiState.lastError?.let { error ->
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = StatusError
            )
        }
    }
}

@Composable
private fun ThreadHeader(selectedThread: ChatThreadItem?) {
    Text(
        text = selectedThread?.title ?: "新しいチャット",
        style = MaterialTheme.typography.titleLarge,
        color = TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun EmptyChatState(
    llmReady: Boolean,
    selectedLlm: LlmModelOption?,
    isLoadingLlmModel: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceMuted)
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when {
                llmReady -> "会話を始めてください"
                isLoadingLlmModel -> "モデルを読み込み中です"
                else -> "モデルを読み込んでください"
            },
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = if (llmReady) {
                "このスレッドは空です。最初のメッセージを送ると会話が保存されます。"
            } else if (isLoadingLlmModel) {
                "起動時に前回のモデルを自動で読み込んでいます。完了まで少し待ってください。"
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
    val metadata = message.responseMs?.let(::formatResponseTimeLabel)
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
        if (metadata != null) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun ThinkingBubble(assistantLabel: String, startedAt: Long?) {
    val bubbleShape = RoundedCornerShape(
        topStart = Radius.lg,
        topEnd = Radius.lg,
        bottomStart = Radius.sm,
        bottomEnd = Radius.lg
    )
    val elapsedSeconds by produceState(initialValue = 0L, key1 = startedAt) {
        if (startedAt == null) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L)) / 1000L
            delay(250)
        }
    }
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
                text = "応答を生成しています… ${elapsedSeconds}秒",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

private fun formatResponseTimeLabel(responseMs: Long): String {
    val seconds = responseMs / 1000.0
    return if (seconds >= 10.0) {
        String.format("%.0f秒で応答", seconds)
    } else {
        String.format("%.1f秒で応答", seconds)
    }
}
