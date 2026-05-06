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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
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
import com.xtrust.standalone.data.SessionSummaryEntity
import com.xtrust.standalone.data.WrapupJobEntity
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
import org.json.JSONArray

@Composable
fun HomeScreen(viewModel: XtrustViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    var focusedSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
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

    val effectiveFocusedSessionId = focusedSessionId
        ?.takeIf { targetId -> sessions.any { it.session.id == targetId } }
        ?: sessions.firstOrNull()?.session?.id
    val focusedSession = sessions.firstOrNull { it.session.id == effectiveFocusedSessionId }
    val focusedSessionCards = remember(cards, effectiveFocusedSessionId) {
        cards
            .filter { it.sessionId == effectiveFocusedSessionId }
            .sortedBy { it.recordedAt }
    }

    LaunchedEffect(sessions, uiState.vadDebugState.isListening) {
        when {
            uiState.vadDebugState.isListening && sessions.isNotEmpty() -> {
                focusedSessionId = sessions.first().session.id
            }
            focusedSessionId == null && sessions.isNotEmpty() -> {
                focusedSessionId = sessions.first().session.id
            }
            focusedSessionId != null && sessions.none { it.session.id == focusedSessionId } -> {
                focusedSessionId = sessions.firstOrNull()?.session?.id
            }
        }
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
            HomeHeader(lastError = uiState.lastError)

            Spacer(modifier = Modifier.height(Spacing.lg))

            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.weight(1f),
                    color = SurfaceSubtle,
                    shape = RoundedCornerShape(Radius.lg)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.lg)
                    ) {
                        item {
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
                        }
                        item {
                            Spacer(modifier = Modifier.height(Spacing.xl))
                        }
                        item {
                            MinutesListCard(
                                sessions = sessions,
                                selectedSessionId = effectiveFocusedSessionId,
                                wrapupJobBySession = uiState.wrapupJobBySession,
                                onSelectSession = { sessionId -> focusedSessionId = sessionId },
                                onCancelWrapup = { sessionId -> viewModel.cancelWrapup(sessionId) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(Spacing.xl))

                LazyColumn(
                    modifier = Modifier.weight(2f),
                    contentPadding = PaddingValues(bottom = Spacing.lg)
                ) {
                    item {
                        UtterancesCard(
                            summary = focusedSession,
                            cards = focusedSessionCards,
                            wrapupJob = effectiveFocusedSessionId?.let(uiState.wrapupJobBySession::get),
                            sessionSummary = effectiveFocusedSessionId?.let(uiState.sessionSummaryBySession::get),
                            isListening = focusedSession?.session?.status == "recording",
                            isSpeechDetected = focusedSession?.session?.status == "recording" &&
                                uiState.vadDebugState.isSpeechDetected,
                            onWrapup = {
                                effectiveFocusedSessionId?.let(viewModel::retryWrapup)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(lastError: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "AI議事録",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        lastError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = StatusError,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = Spacing.md),
            color = DividerStrong,
            thickness = Sizes.hairline
        )
    }
}

private fun buildRecordingStatusText(
    isListening: Boolean,
    isSpeechDetected: Boolean
): String {
    return when {
        isListening && isSpeechDetected -> "録音中"
        isListening -> "音声が検出されていません"
        else -> "停止中"
    }
}

@Composable
private fun VadDebugCard(
    vadState: VadDebugState,
    onToggleListening: () -> Unit
) {
    val parameterLine1 = buildString {
        append("レベル ${vadState.rmsDb.roundToInt()} dBFS")
        append("  ・  開始 ${vadState.speechStartMs}ms")
        append("  ・  無音分割 ${vadState.silenceSplitMs}ms")
    }
    val parameterLine2 = "状態 ${vadState.engineLabel}  ・  ${if (vadState.isEngineReady) "準備完了" else vadState.engineStatus}"

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            onClick = onToggleListening,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = AccentOnPrimary
            )
        ) {
            Text(if (vadState.isListening) "停止" else "開始")
        }
        Row(
            modifier = Modifier.padding(top = Spacing.md),
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
        Column(modifier = Modifier.padding(top = Spacing.md)) {
            Text(
                text = parameterLine1,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = parameterLine2,
                style = MaterialTheme.typography.bodySmall,
                color = if (vadState.isEngineReady) TextTertiary else StatusError,
                modifier = Modifier.padding(top = Spacing.xxs)
            )
        }
    }
    HorizontalDivider(
        color = DividerStrong,
        thickness = Sizes.hairline
    )
}

@Composable
private fun UtterancesCard(
    summary: RecordingSessionSummary?,
    cards: List<CardEntity>,
    wrapupJob: WrapupJobEntity?,
    sessionSummary: SessionSummaryEntity?,
    isListening: Boolean = false,
    isSpeechDetected: Boolean = false,
    onWrapup: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (summary == null) {
            Text(
                text = "表示できる議事録がまだありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
            return
        }

        val started = remember(summary.session.startedAt) {
            SimpleDateFormat("M月d日(E) HH:mm", Locale.JAPAN)
                .format(Date(summary.session.startedAt))
        }
        val ended = summary.session.endedAt?.let {
            remember(it) {
                SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(it))
            }
        } ?: "進行中"
        val isWrapupActive = wrapupJob?.status in listOf(
            WrapupJobEntity.STATUS_PENDING,
            WrapupJobEntity.STATUS_RUNNING
        )
        val wrapupButtonLabel = when {
            isWrapupActive -> "要約中…"
            wrapupJob?.status == WrapupJobEntity.STATUS_COMPLETED -> "再要約する"
            wrapupJob?.status == WrapupJobEntity.STATUS_FAILED -> "再実行する"
            wrapupJob?.status == WrapupJobEntity.STATUS_CANCELED -> "再実行する"
            else -> "要約する"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "$started → $ended  ・  発言 ${summary.segmentCount}  ・  文字起こし ${summary.transcribedCount}  ・  ${buildSessionStatusText(summary.session.status)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            OutlinedButton(
                onClick = onWrapup,
                enabled = summary.transcribedCount > 0 && !isWrapupActive
            ) {
                Text(
                    text = wrapupButtonLabel,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        AiSummarySection(
            wrapupJob = wrapupJob,
            sessionSummary = sessionSummary,
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.md)
        )

        val showPlaceholder = isListening && isSpeechDetected
        if (!showPlaceholder && cards.isEmpty()) {
            Text(
                text = "この議事録にはまだ発言がありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showPlaceholder) {
                    LiveRecordingPlaceholderRow()
                    if (cards.isNotEmpty()) {
                        HorizontalDivider(color = DividerSubtle, thickness = Sizes.hairline)
                    }
                }
                cards.forEachIndexed { index, card ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = DividerSubtle,
                            thickness = Sizes.hairline
                        )
                    }
                    MinutesDrawerUtteranceRow(card = card)
                }
            }
        }
    }
}

@Composable
private fun AiSummarySection(
    wrapupJob: WrapupJobEntity?,
    sessionSummary: SessionSummaryEntity?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
        Text(
            text = "AIによる要約",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            modifier = Modifier.padding(top = Spacing.md)
        )

        wrapupJob?.let { job ->
            val (label, color) = when (job.status) {
                WrapupJobEntity.STATUS_PENDING -> "要約待ち" to TextTertiary
                WrapupJobEntity.STATUS_RUNNING -> (job.stepDetail ?: "要約中…") to AccentPrimary
                WrapupJobEntity.STATUS_COMPLETED -> "要約完了" to Color(0xFF22A06B)
                WrapupJobEntity.STATUS_FAILED -> "要約失敗: ${job.lastError?.take(80)}" to StatusError
                WrapupJobEntity.STATUS_CANCELED -> "キャンセル済み" to TextTertiary
                else -> null to TextTertiary
            }
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            buildWrapupDurationLabel(job)?.let { durationLabel ->
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = Spacing.xxs)
                )
            }
        }

        if (sessionSummary == null) {
            Text(
                text = "まだ要約はありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(top = Spacing.md)
            )
            return
        }

        sessionSummary.generatedTitle?.let { generatedTitle ->
            SummaryBlock(
                label = "タイトル",
                value = generatedTitle
            )
        }
        sessionSummary.theme?.let { theme ->
            SummaryBlock(
                label = "概要",
                value = theme
            )
        }
        val agendaItems = remember(sessionSummary.agendaJson) {
            parseAgendaItems(sessionSummary.agendaJson)
        }
        if (agendaItems.isNotEmpty()) {
            SummaryAgendaBlock(items = agendaItems)
        }
    }
}

@Composable
private fun LiveRecordingPlaceholderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(StatusRecording)
        )
        Text(
            text = "録音中…",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusRecording
        )
    }
}

@Composable
private fun MinutesListCard(
    sessions: List<RecordingSessionSummary>,
    selectedSessionId: Long?,
    wrapupJobBySession: Map<Long, WrapupJobEntity>,
    onSelectSession: (Long) -> Unit,
    onCancelWrapup: (Long) -> Unit
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
                        isSelected = summary.session.id == selectedSessionId,
                        wrapupJob = wrapupJobBySession[summary.session.id],
                        onClick = { onSelectSession(summary.session.id) },
                        onCancelWrapup = { onCancelWrapup(summary.session.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MinutesRow(
    summary: RecordingSessionSummary,
    isSelected: Boolean,
    wrapupJob: WrapupJobEntity?,
    onClick: () -> Unit,
    onCancelWrapup: () -> Unit
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

    val isWrapupActive = wrapupJob?.status in listOf(
        WrapupJobEntity.STATUS_PENDING, WrapupJobEntity.STATUS_RUNNING
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(if (isSelected) Color.White else Color.Transparent)
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
            if (isWrapupActive) {
                androidx.compose.material3.IconButton(
                    onClick = onCancelWrapup,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "要約をキャンセル",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "議事録を開く",
                    tint = TextTertiary
                )
            }
        }
        if (wrapupJob != null) {
            WrapupStatusBadge(job = wrapupJob)
        }
    }
}

@Composable
private fun WrapupStatusBadge(job: WrapupJobEntity) {
    val (label, color) = when (job.status) {
        WrapupJobEntity.STATUS_PENDING -> "要約待ち" to TextTertiary
        WrapupJobEntity.STATUS_RUNNING -> (job.stepDetail ?: "要約中…") to AccentPrimary
        WrapupJobEntity.STATUS_COMPLETED -> "要約完了" to Color(0xFF22A06B)
        WrapupJobEntity.STATUS_FAILED -> "要約失敗: ${job.lastError?.take(30)}" to StatusError
        WrapupJobEntity.STATUS_CANCELED -> "キャンセル済み" to TextTertiary
        else -> return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = Spacing.sm, bottom = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (job.status == WrapupJobEntity.STATUS_RUNNING) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(AccentPrimary)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
    wrapupJob: WrapupJobEntity?,
    sessionSummary: SessionSummaryEntity?,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onWrapup: () -> Unit
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
                    Spacer(modifier = Modifier.height(Spacing.md))
                    val isWrapupActive = wrapupJob?.status in listOf(
                        WrapupJobEntity.STATUS_PENDING, WrapupJobEntity.STATUS_RUNNING
                    )
                    if (isWrapupActive) {
                        val detail = wrapupJob?.stepDetail ?: "要約中…"
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AccentPrimary)
                            )
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentPrimary
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = onWrapup,
                            enabled = summary.transcribedCount > 0
                        ) {
                            Text(
                                text = "この議事録を要約する",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        text = "開始  $started  →  $ended",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
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
                    if (wrapupJob != null) {
                        val (wrapupLabel, wrapupColor) = when (wrapupJob.status) {
                            WrapupJobEntity.STATUS_PENDING -> "要約待ち" to TextTertiary
                            WrapupJobEntity.STATUS_RUNNING -> (wrapupJob.stepDetail ?: "要約中…") to AccentPrimary
                            WrapupJobEntity.STATUS_COMPLETED -> "要約完了" to androidx.compose.ui.graphics.Color(0xFF22A06B)
                            WrapupJobEntity.STATUS_FAILED -> "要約失敗: ${wrapupJob.lastError?.take(30)}" to StatusError
                            WrapupJobEntity.STATUS_CANCELED -> "キャンセル済み" to TextTertiary
                            else -> null to TextTertiary
                        }
                        if (wrapupLabel != null) {
                            Text(
                                text = "AI要約  $wrapupLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = wrapupColor,
                                modifier = Modifier.padding(top = Spacing.xxs)
                            )
                        }
                    }
                    if (sessionSummary != null) {
                        val wrapupDurationLabel = buildWrapupDurationLabel(wrapupJob)
                        Spacer(modifier = Modifier.height(Spacing.lg))
                        HorizontalDivider(color = DividerStrong, thickness = Sizes.hairline)
                        Spacer(modifier = Modifier.height(Spacing.lg))
                        Text(
                            text = "AI要約",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        wrapupDurationLabel?.let { durationLabel ->
                            Text(
                                text = durationLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = Spacing.xxs)
                            )
                        }
                        sessionSummary.generatedTitle?.let { generatedTitle ->
                            SummaryBlock(
                                label = "タイトル",
                                value = generatedTitle
                            )
                        }
                        sessionSummary.theme?.let { theme ->
                            SummaryBlock(
                                label = "概要",
                                value = theme
                            )
                        }
                        val agendaItems = remember(sessionSummary.agendaJson) {
                            parseAgendaItems(sessionSummary.agendaJson)
                        }
                        if (agendaItems.isNotEmpty()) {
                            SummaryAgendaBlock(items = agendaItems)
                        }
                    }
                    Text(
                        text = "ID  ${summary.session.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(top = Spacing.lg)
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
                }
            }
        }
    }
}

@Composable
private fun SummaryBlock(
    label: String,
    value: String
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary,
        modifier = Modifier.padding(top = Spacing.md)
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyLarge,
        color = TextPrimary,
        modifier = Modifier.padding(top = Spacing.xxs)
    )
}

@Composable
private fun SummaryAgendaBlock(items: List<String>) {
    Text(
        text = "トピック",
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary,
        modifier = Modifier.padding(top = Spacing.md)
    )
    Column(
        modifier = Modifier.padding(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        items.forEach { item ->
            Text(
                text = "・$item",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
    }
}

private fun parseAgendaItems(agendaJson: String?): List<String> {
    if (agendaJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val agenda = JSONArray(agendaJson)
        buildList {
            for (index in 0 until agenda.length()) {
                val item = agenda.optString(index).trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }.getOrElse { emptyList() }
}

private fun buildWrapupDurationLabel(job: WrapupJobEntity?): String? {
    if (job?.status != WrapupJobEntity.STATUS_COMPLETED) return null
    val finishedAt = job.finishedAt ?: return null
    val startedAt = job.startedAt ?: job.enqueuedAt
    val durationMs = (finishedAt - startedAt).coerceAtLeast(0L)
    return if (durationMs > 0L) {
        "完了時間  ${formatElapsedDuration(durationMs)}"
    } else {
        null
    }
}

private fun formatElapsedDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}分${seconds}秒"
    } else {
        "${seconds}秒"
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
