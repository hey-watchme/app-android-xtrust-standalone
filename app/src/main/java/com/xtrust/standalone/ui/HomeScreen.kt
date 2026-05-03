package com.xtrust.standalone.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: XtrustViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.chatMessages.collectAsState()
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVadMonitoring()
        } else {
            viewModel.reportAudioPermissionDenied()
        }
    }
    val hasAudioPermission = remember(uiState.vadDebugState.isListening) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(messages.size, uiState.isProcessing) {
        val itemCount = messages.size + if (uiState.isProcessing) 1 else 0
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ChatStatusCard(
            uiState = uiState,
            onResetChat = viewModel::resetChat
        )

        Spacer(modifier = Modifier.height(12.dp))

        VadDebugCard(
            vadState = uiState.vadDebugState,
            onToggleListening = {
                if (uiState.vadDebugState.isListening) {
                    viewModel.stopVadMonitoring()
                } else if (hasAudioPermission) {
                    viewModel.startVadMonitoring()
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onClearSegments = viewModel::clearSavedSegments
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyChatState(llmReady = uiState.llmReady)
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }
            }

            if (uiState.isProcessing) {
                item {
                    ThinkingBubble()
                }
            }
        }

        uiState.lastError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.llmReady && !uiState.isProcessing,
            minLines = 3,
            maxLines = 6,
            label = { Text("Message") },
            placeholder = { Text("日本語で質問や指示を入力") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { draft = "" },
                enabled = draft.isNotEmpty() && !uiState.isProcessing,
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear")
            }

            Button(
                onClick = {
                    viewModel.sendChatMessage(draft)
                    draft = ""
                },
                enabled = uiState.llmReady && !uiState.isProcessing && draft.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun ChatStatusCard(uiState: UiState, onResetChat: () -> Unit) {
    val memory = uiState.memorySnapshot
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (uiState.llmReady) "Gemma 4 E2B ready" else "Gemma 4 E2B not loaded",
                style = MaterialTheme.typography.titleMedium,
                color = if (uiState.llmReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(
                text = "Device RAM ${memory.deviceUsedMb} / ${memory.deviceTotalMb} MB used",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "App heap ${memory.appHeapUsedMb} / ${memory.appHeapMaxMb} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Native heap ${memory.nativeHeapMb} MB  Available ${memory.deviceAvailableMb} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (memory.lowMemory) {
                Text(
                    text = "System low-memory signal detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onResetChat,
                    enabled = uiState.llmReady && !uiState.isProcessing
                ) {
                    Text("New chat")
                }
            }
        }
    }
}

@Composable
private fun VadDebugCard(
    vadState: VadDebugState,
    onToggleListening: () -> Unit,
    onClearSegments: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Local VAD monitor",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (vadState.isListening) {
                    if (vadState.isSpeechDetected) "Speech detected" else "Listening for speech"
                } else {
                    "Stopped"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (vadState.isSpeechDetected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Level ${vadState.rmsDb.roundToInt()} dBFS  Threshold ${vadState.thresholdDb.roundToInt()} dBFS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Segments ${vadState.detectedSegments}  Last speech ${vadState.lastSpeechDurationMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (vadState.savedSegments.isNotEmpty()) {
                Text(
                    text = "Saved wav segments",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vadState.savedSegments, key = { it.id }) { segment ->
                        SavedSegmentRow(segment)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = onClearSegments,
                    enabled = vadState.savedSegments.isNotEmpty() && !vadState.isListening
                ) {
                    Text("Clear segments")
                }
                Button(onClick = onToggleListening) {
                    Text(if (vadState.isListening) "Stop VAD" else "Start VAD")
                }
            }
        }
    }
}

@Composable
private fun SavedSegmentRow(segment: AudioSegment) {
    val timeText = remember(segment.createdAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(segment.createdAt))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = segment.fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            Text(
                text = "$timeText  ${segment.durationMs} ms  ${segment.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyChatState(llmReady: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (llmReady) "Start a local chat" else "Load the model first",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (llmReady) {
                    "Use this screen to test multi-turn local chat on the device."
                } else {
                    "Open Settings if auto-load did not complete."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.User
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val label = if (isUser) "You" else "Gemma"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Gemma",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(
                text = "Generating response...",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
