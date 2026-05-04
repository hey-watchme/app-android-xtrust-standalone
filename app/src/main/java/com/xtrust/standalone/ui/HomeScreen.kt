package com.xtrust.standalone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xtrust.standalone.data.CardEntity
import com.xtrust.standalone.data.RecordingSessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(viewModel: XtrustViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    var selectedSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
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
    val hasAudioPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val selectedSession = sessions.firstOrNull { it.session.id == selectedSessionId }
    val selectedSessionCards = remember(cards, selectedSessionId) {
        cards
            .filter { it.sessionId == selectedSessionId }
            .sortedBy { it.recordedAt }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            uiState.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

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
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(2f),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item {
                        UtterancesCard(
                            segments = uiState.vadDebugState.savedSegments
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item {
                        MinutesListCard(
                            sessions = sessions,
                            onSelectSession = { sessionId -> selectedSessionId = sessionId }
                        )
                    }
                }
            }
        }

        if (selectedSession != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable { selectedSessionId = null }
            )
            MinutesDetailDrawer(
                summary = selectedSession,
                cards = selectedSessionCards,
                modifier = Modifier.align(Alignment.CenterEnd),
                onClose = { selectedSessionId = null }
            )
        }
    }
}

private fun buildRecordingStatusText(
    isListening: Boolean,
    isSpeechDetected: Boolean
): String {
    return when {
        isListening && isSpeechDetected -> "録音中・音声を検出しています"
        isListening -> "録音中・音声を待機しています"
        else -> "停止中"
    }
}

@Composable
private fun VadDebugCard(
    vadState: VadDebugState,
    onToggleListening: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AI議事録",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = buildRecordingStatusText(
                        isListening = vadState.isListening,
                        isSpeechDetected = vadState.isSpeechDetected
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vadState.isListening) {
                        Color(0xFF2E7D32)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Switch(
                    checked = vadState.isListening,
                    onCheckedChange = { onToggleListening() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2E7D32),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF9E9E9E)
                    )
                )
            }
        }
        Text(
            text = "レベル ${vadState.rmsDb.roundToInt()} dBFS  開始 ${vadState.thresholdDb.roundToInt()} dBFS  継続 ${vadState.continueThresholdDb.roundToInt()} dBFS",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = "検出 ${vadState.detectedSegments}  最終発話 ${vadState.lastSpeechDurationMs} ms  無音分割 ${vadState.pauseSplitMs} ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun UtterancesCard(
    segments: List<AudioSegment>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "発言",
                style = MaterialTheme.typography.titleMedium
            )
            if (segments.isEmpty()) {
                Text(
                    text = "ただいま発言はありません。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    segments.forEachIndexed { index, segment ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        UtteranceRow(segment = segment)
                    }
                }
            }
        }
    }
}

@Composable
private fun MinutesListCard(
    sessions: List<RecordingSessionSummary>,
    onSelectSession: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "議事録一覧 ${sessions.size}件",
                style = MaterialTheme.typography.titleMedium
            )
            if (sessions.isEmpty()) {
                Text(
                    text = "保存済みの議事録はありません。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    sessions.forEachIndexed { index, summary ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        MinutesRow(
                            summary = summary,
                            onClick = { onSelectSession(summary.session.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinutesRow(
    summary: RecordingSessionSummary,
    onClick: () -> Unit
) {
    val started = remember(summary.session.startedAt) {
        SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
            .format(Date(summary.session.startedAt))
    }
    val ended = summary.session.endedAt?.let {
        remember(it) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
        }
    } ?: "進行中"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.session.title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "$started -> $ended",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = "発言 ${summary.segmentCount}件  文字起こし ${summary.transcribedCount}件  状態 ${buildSessionStatusText(summary.session.status)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = "議事録を開く",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun buildSessionStatusText(status: String): String {
    return when (status) {
        "recording" -> "録音中"
        "completed" -> "完了"
        "error", "interrupted" -> "エラー"
        else -> status
    }
}

@Composable
private fun MinutesDetailDrawer(
    summary: RecordingSessionSummary,
    cards: List<CardEntity>,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val started = remember(summary.session.startedAt) {
        SimpleDateFormat("yyyy年M月d日(E) HH:mm", Locale.JAPAN)
            .format(Date(summary.session.startedAt))
    }
    val ended = summary.session.endedAt?.let {
        remember(it) {
            SimpleDateFormat("yyyy年M月d日(E) HH:mm", Locale.JAPAN)
                .format(Date(it))
        }
    } ?: "進行中"

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.66f),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary.session.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "閉じる",
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(onClick = onClose)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    text = "開始: $started  /  終了: $ended",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "状態: ${buildSessionStatusText(summary.session.status)}  /  発言数: ${summary.segmentCount}件  /  文字起こし済み: ${summary.transcribedCount}件",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                summary.session.errorMessage?.takeIf { it.isNotBlank() }?.let { errorMessage ->
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    text = "ID: ${summary.session.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            if (cards.isEmpty()) {
                item {
                    Text(
                        text = "この議事録にはまだ発言がありません。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            } else {
                items(cards) { card ->
                    Spacer(modifier = Modifier.height(12.dp))
                    MinutesDrawerUtteranceRow(card = card)
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    text = "今後はこの発言一覧をもとに、ローカル LLM で要約や整理を行います。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MinutesDrawerUtteranceRow(card: CardEntity) {
    val timeText = remember(card.recordedAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(card.recordedAt))
    }
    val metaText = remember(card.durationMs, card.transcriptionMs) {
        "長さ ${card.durationMs} ms / 文字起こし ${card.transcriptionMs?.let { "${it} ms" } ?: "待機中"}"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "($metaText)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = card.transcript?.takeIf { it.isNotBlank() } ?: "文字起こし待ちです。",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun UtteranceRow(segment: AudioSegment) {
    val timeText = remember(segment.createdAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(segment.createdAt))
    }
    val metaText = remember(segment.durationMs, segment.sizeBytes) {
        "長さ ${segment.durationMs} ms / サイズ ${segment.sizeBytes / 1024} KB"
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "($metaText)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (segment.isTranscribing) {
                Text(
                    text = "文字起こし中...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        segment.transcript?.let { transcript ->
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        segment.transcriptionMs?.let { duration ->
            Text(
                text = "文字起こし ${duration} ms  RTF ${segment.realTimeFactor?.let { "%.2f".format(it) } ?: "-"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        segment.asrError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
