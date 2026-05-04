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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xtrust.standalone.data.CardEntity
import com.xtrust.standalone.data.RecordingSessionSummary
import com.xtrust.standalone.ui.theme.AccentOnPrimary
import com.xtrust.standalone.ui.theme.AccentPrimary
import com.xtrust.standalone.ui.theme.DividerStrong
import com.xtrust.standalone.ui.theme.DividerSubtle
import com.xtrust.standalone.ui.theme.Radius
import com.xtrust.standalone.ui.theme.Sizes
import com.xtrust.standalone.ui.theme.Spacing
import com.xtrust.standalone.ui.theme.StatusError
import com.xtrust.standalone.ui.theme.StatusRecording
import com.xtrust.standalone.ui.theme.SurfaceBackground
import com.xtrust.standalone.ui.theme.SurfaceSelected
import com.xtrust.standalone.ui.theme.SurfaceSubtle
import com.xtrust.standalone.ui.theme.TextPrimary
import com.xtrust.standalone.ui.theme.TextSecondary
import com.xtrust.standalone.ui.theme.TextTertiary
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
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl)
        ) {
            uiState.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusError,
                    modifier = Modifier.padding(bottom = Spacing.md)
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

            Spacer(modifier = Modifier.height(Spacing.xl))

            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(2f),
                    contentPadding = PaddingValues(bottom = Spacing.lg)
                ) {
                    item {
                        UtterancesCard(
                            segments = uiState.vadDebugState.savedSegments
                        )
                    }
                }

                Spacer(modifier = Modifier.width(Spacing.xl))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = Spacing.lg)
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
                    .background(Color.Black.copy(alpha = 0.12f))
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.lg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AI議事録",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(Spacing.sm)
                            .clip(CircleShape)
                            .background(
                                if (vadState.isListening) StatusRecording else TextTertiary
                            )
                    )
                    Text(
                        text = buildRecordingStatusText(
                            isListening = vadState.isListening,
                            isSpeechDetected = vadState.isSpeechDetected
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = vadState.isListening,
                    onCheckedChange = { onToggleListening() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentOnPrimary,
                        checkedTrackColor = AccentPrimary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = SurfaceSelected
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "レベル ${vadState.rmsDb.roundToInt()} dBFS  ・  開始 ${vadState.thresholdDb.roundToInt()} dBFS  ・  継続 ${vadState.continueThresholdDb.roundToInt()} dBFS",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        Text(
            text = "検出 ${vadState.detectedSegments}  ・  最終発話 ${vadState.lastSpeechDurationMs} ms  ・  無音分割 ${vadState.pauseSplitMs} ms",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        HorizontalDivider(
            color = DividerStrong,
            thickness = Sizes.hairline
        )
    }
}

@Composable
private fun UtterancesCard(
    segments: List<AudioSegment>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "発言",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        if (segments.isEmpty()) {
            Text(
                text = "ただいま発言はありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                segments.forEachIndexed { index, segment ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = DividerSubtle,
                            thickness = Sizes.hairline
                        )
                    }
                    UtteranceRow(segment = segment)
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "議事録一覧",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            Text(
                text = "${sessions.size}",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        if (sessions.isEmpty()) {
            Text(
                text = "保存済みの議事録はありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                sessions.forEachIndexed { index, summary ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = DividerSubtle,
                            thickness = Sizes.hairline
                        )
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
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Article,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(Spacing.lg)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.session.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "$started → $ended",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = Spacing.xxs)
            )
            Text(
                text = "発言 ${summary.segmentCount}  ・  文字起こし ${summary.transcribedCount}  ・  ${buildSessionStatusText(summary.session.status)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = Spacing.xxs)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "議事録を開く",
            tint = TextTertiary
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
        color = SurfaceBackground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left hairline divider for the slide-in panel
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(Sizes.hairline)
                    .background(DividerStrong)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.xl,
                    vertical = Spacing.xl
                )
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = summary.session.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "閉じる",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(Spacing.lg)
                                .clickable(onClick = onClose)
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        text = "開始  $started",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "終了  $ended",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = Spacing.xxs)
                    )
                    Text(
                        text = "状態  ${buildSessionStatusText(summary.session.status)}  ・  発言 ${summary.segmentCount}  ・  文字起こし ${summary.transcribedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = Spacing.xxs)
                    )
                    summary.session.errorMessage?.takeIf { it.isNotBlank() }?.let { errorMessage ->
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError,
                            modifier = Modifier.padding(top = Spacing.sm)
                        )
                    }
                    Text(
                        text = "ID  ${summary.session.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
                }
                if (cards.isEmpty()) {
                    item {
                        Text(
                            text = "この議事録にはまだ発言がありません。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary,
                            modifier = Modifier.padding(top = Spacing.md)
                        )
                    }
                } else {
                    items(cards) { card ->
                        MinutesDrawerUtteranceRow(card = card)
                        HorizontalDivider(color = DividerSubtle, thickness = Sizes.hairline)
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    Text(
                        text = "今後はこの発言一覧をもとに、ローカル LLM で要約や整理を行います。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
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
        "長さ ${card.durationMs} ms  ・  文字起こし ${card.transcriptionMs?.let { "${it} ms" } ?: "待機中"}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = card.transcript?.takeIf { it.isNotBlank() } ?: "文字起こし待ちです。",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.padding(top = Spacing.sm)
        )
    }
}

@Composable
private fun UtteranceRow(segment: AudioSegment) {
    val timeText = remember(segment.createdAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(segment.createdAt))
    }
    val metaText = remember(segment.durationMs, segment.sizeBytes) {
        "長さ ${segment.durationMs} ms  ・  サイズ ${segment.sizeBytes / 1024} KB"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(SurfaceSubtle)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (segment.isTranscribing) {
                Text(
                    text = "文字起こし中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        }
        segment.transcript?.let { transcript ->
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
        segment.transcriptionMs?.let { duration ->
            Text(
                text = "文字起こし ${duration} ms  ・  RTF ${segment.realTimeFactor?.let { "%.2f".format(it) } ?: "-"}",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
        segment.asrError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = StatusError,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
    }
}
