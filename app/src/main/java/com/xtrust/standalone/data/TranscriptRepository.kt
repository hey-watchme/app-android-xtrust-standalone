package com.xtrust.standalone.data

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TranscriptRepository(context: Context) {

    private val dbHelper = AppDatabaseHelper(context)

    private val _cards = MutableStateFlow<List<CardEntity>>(emptyList())
    private val _topics = MutableStateFlow<List<TopicEntity>>(emptyList())
    private val _sessions = MutableStateFlow<List<RecordingSessionSummary>>(emptyList())

    val cards: Flow<List<CardEntity>> = _cards.asStateFlow()
    val topics: Flow<List<TopicEntity>> = _topics.asStateFlow()
    val sessions: Flow<List<RecordingSessionSummary>> = _sessions.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _cards.value = loadCards()
        _topics.value = loadTopics()
        _sessions.value = loadSessionSummaries()
    }

    suspend fun closeDanglingSessions(endedAt: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("ended_at", endedAt)
            put("status", STATUS_INTERRUPTED)
            put("updated_at", endedAt)
        }
        dbHelper.writableDatabase.update(
            "sessions",
            values,
            "status = ? AND ended_at IS NULL",
            arrayOf(STATUS_RECORDING)
        )
        _sessions.value = loadSessionSummaries()
    }

    suspend fun startSession(startedAt: Long): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("started_at", startedAt)
            putNull("ended_at")
            put("status", STATUS_RECORDING)
            put("created_at", now)
            put("updated_at", now)
        }
        val sessionId = dbHelper.writableDatabase.insertOrThrow("sessions", null, values)
        _sessions.value = loadSessionSummaries()
        sessionId
    }

    suspend fun completeSession(sessionId: Long, endedAt: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("ended_at", endedAt)
            put("status", STATUS_COMPLETED)
            put("updated_at", endedAt)
        }
        dbHelper.writableDatabase.update(
            "sessions",
            values,
            "id = ?",
            arrayOf(sessionId.toString())
        )
        _sessions.value = loadSessionSummaries()
    }

    suspend fun saveCard(card: CardEntity): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("session_id", card.sessionId)
            put("audio_path", card.audioPath)
            put("transcript", card.transcript)
            put("asr_provider", card.asrProvider)
            put("asr_model", card.asrModel)
            put("duration_ms", card.durationMs)
            put("size_bytes", card.sizeBytes)
            put("recorded_at", card.recordedAt)
            put("transcription_ms", card.transcriptionMs)
            put("real_time_factor", card.realTimeFactor)
            put("created_at", card.createdAt)
            put("updated_at", now)
        }
        val id = dbHelper.writableDatabase.insertOrThrow("cards", null, values)
        _cards.value = loadCards()
        _sessions.value = loadSessionSummaries()
        id
    }

    suspend fun updateCardTranscription(
        cardId: Long,
        transcript: String,
        transcriptionMs: Long,
        realTimeFactor: Double
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("transcript", transcript)
            put("transcription_ms", transcriptionMs)
            put("real_time_factor", realTimeFactor)
            put("updated_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.update(
            "cards",
            values,
            "id = ?",
            arrayOf(cardId.toString())
        )
        _cards.value = loadCards()
        _sessions.value = loadSessionSummaries()
    }

    suspend fun saveTopic(topic: TopicEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("session_id", topic.sessionId)
            put("title", topic.title)
            put("summary", topic.summary)
            put("start_at", topic.startAt)
            put("end_at", topic.endAt)
            put("llm_provider", topic.llmProvider)
            put("llm_model", topic.llmModel)
            put("created_at", topic.createdAt)
        }
        val id = dbHelper.writableDatabase.insertOrThrow("topics", null, values)
        _topics.value = loadTopics()
        id
    }

    private fun loadCards(): List<CardEntity> {
        val db = dbHelper.readableDatabase
        val cards = mutableListOf<CardEntity>()
        db.query(
            "cards",
            null,
            null,
            null,
            null,
            null,
            "recorded_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cards += CardEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                    audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                    transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript")),
                    asrProvider = cursor.getString(cursor.getColumnIndexOrThrow("asr_provider")),
                    asrModel = cursor.getString(cursor.getColumnIndexOrThrow("asr_model")),
                    durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
                    recordedAt = cursor.getLong(cursor.getColumnIndexOrThrow("recorded_at")),
                    transcriptionMs = cursor.getLongOrNull("transcription_ms"),
                    realTimeFactor = cursor.getDoubleOrNull("real_time_factor"),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                )
            }
        }
        return cards
    }

    private fun loadTopics(): List<TopicEntity> {
        val db = dbHelper.readableDatabase
        val topics = mutableListOf<TopicEntity>()
        db.query(
            "topics",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                topics += TopicEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    sessionId = cursor.getLongOrNull("session_id"),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                    startAt = cursor.getLong(cursor.getColumnIndexOrThrow("start_at")),
                    endAt = cursor.getLong(cursor.getColumnIndexOrThrow("end_at")),
                    llmProvider = cursor.getString(cursor.getColumnIndexOrThrow("llm_provider")),
                    llmModel = cursor.getString(cursor.getColumnIndexOrThrow("llm_model")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return topics
    }

    private fun loadSessionSummaries(): List<RecordingSessionSummary> {
        val db = dbHelper.readableDatabase
        val sessions = mutableListOf<RecordingSessionSummary>()
        db.rawQuery(
            """
            SELECT
                s.id,
                s.started_at,
                s.ended_at,
                s.status,
                s.created_at,
                s.updated_at,
                COUNT(c.id) AS segment_count,
                SUM(CASE WHEN c.transcript IS NOT NULL AND c.transcript != '' THEN 1 ELSE 0 END) AS transcribed_count
            FROM sessions s
            LEFT JOIN cards c ON c.session_id = s.id
            GROUP BY s.id
            ORDER BY s.started_at DESC, s.id DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sessions += RecordingSessionSummary(
                    session = RecordingSessionEntity(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                        endedAt = cursor.getLongOrNull("ended_at"),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    ),
                    segmentCount = cursor.getInt(cursor.getColumnIndexOrThrow("segment_count")),
                    transcribedCount = cursor.getIntOrZero("transcribed_count")
                )
            }
        }
        return sessions
    }

    private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.getDoubleOrNull(columnName: String): Double? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun android.database.Cursor.getIntOrZero(columnName: String): Int {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) 0 else getInt(index)
    }

    companion object {
        const val STATUS_RECORDING = "recording"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_INTERRUPTED = "interrupted"
    }
}
